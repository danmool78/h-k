/**
 * 수집 작업. 정기 실행(index.ts)과 관리자 수동 실행(admin.ts)이 같이 쓴다.
 * 등록된 모든 데이터 출처(providers/index.ts)를 돌며 공통 형식으로 합친다.
 * 결과는 config/syncState, 호출 수는 usage/{날짜}에 기록.
 */
import { logger } from "firebase-functions";
import { applyStatus, migrateStationKeys, upgradeLegacy } from "./chargers";
import { SyncResult, addUsage, kstDate, recordSync } from "./config";
import { takeCallCount } from "./evApi";
import { OccupancyMap, recordOccupancy } from "./occupancy";
import { PROVIDERS } from "./providers";
import { StationMap, StatusMap } from "./providers/types";
import {
  LAG_FILE, OCCUPANCY_FILE, STATIONS_FILE, STATUS_FILE, VALIDATION_FILE, deletePrefix, listNames, readJson, writeBuffer, writeJson,
} from "./storage";
import { LagMap, ValidationMap, validateAndShift } from "./validation";
import { KEEP_DAYS, encodeTick, orderVersion, restAreaOrder, tickName } from "./history";

async function run(kind: "stations" | "status", job: () => Promise<{ message: string; count: number }>): Promise<SyncResult> {
  takeCallCount();
  let result: SyncResult;
  try {
    const r = await job();
    result = { at: new Date().toISOString(), ok: true, ...r };
    logger.info(`${kind}: ${r.message}`);
  } catch (e) {
    result = { at: new Date().toISOString(), ok: false, message: e instanceof Error ? e.message : String(e) };
    logger.error(`${kind} 실패`, e);
  }
  await addUsage(takeCallCount());
  await recordSync(kind, result);
  return result;
}

/**
 * 저장된 목록·상태를 읽고, 출처 구분 전 형식이면 그 자리에서 현재 형식으로 바꿔 다시 저장한다
 * (혼잡도 통계·1시간 전 기록의 ID도 함께 이전). 네트워크를 쓰지 않는다.
 */
export async function loadSnapshot(): Promise<{ stations: StationMap | null; statuses: StatusMap }> {
  const stations = await readJson<StationMap>(STATIONS_FILE);
  const statuses = (await readJson<StatusMap>(STATUS_FILE)) ?? {};
  if (stations && upgradeLegacy(stations, statuses)) {
    await writeJson(STATIONS_FILE, stations);
    await writeJson(STATUS_FILE, statuses);
    const occ = await readJson<OccupancyMap>(OCCUPANCY_FILE);
    if (occ && migrateStationKeys(occ)) await writeJson(OCCUPANCY_FILE, occ);
    const lag = await readJson<LagMap>(LAG_FILE);
    if (lag && migrateStationKeys(lag)) await writeJson(LAG_FILE, lag);
    logger.info("저장 데이터를 출처 구분 형식으로 이전");
  }
  return { stations, statuses };
}

/** 모든 출처의 급속 충전소 목록 갱신. 한 출처가 실패하면 그 출처는 이전 목록을 유지. */
export function syncStationsNow(): Promise<SyncResult> {
  return run("stations", () => syncStationsJob());
}

async function syncStationsJob(): Promise<{ message: string; count: number }> {
  const loaded = await loadSnapshot();
  const prev = loaded.stations ?? {};
  const prevStatus = loaded.statuses;
  const stations: StationMap = {};
  const statuses: StatusMap = {};
  const parts: string[] = [];

  for (const p of PROVIDERS) {
    const mine = Object.fromEntries(Object.entries(prev).filter(([, s]) => s.provider === p.id));
    try {
      const snap = await p.fetchStations(mine);
      Object.assign(stations, snap.stations);
      Object.assign(statuses, snap.statuses);
      const fail = snap.failedRegions.length ? ` (실패 지역 ${snap.failedRegions.join(",")} 이전 데이터 유지)` : "";
      parts.push(`${p.country} ${Object.keys(snap.stations).length}곳${fail}`);
    } catch (e) {
      logger.error(`${p.id} 실패, 이전 목록 유지`, e);
      Object.assign(stations, mine);
      parts.push(`${p.country} 실패(이전 ${Object.keys(mine).length}곳 유지)`);
    }
  }
  if (Object.keys(stations).length === 0) throw new Error("모든 출처에서 충전소를 받지 못했습니다");

  // 더 최신 상태가 있으면 유지
  for (const [k, v] of Object.entries(prevStatus)) {
    if (!statuses[k] || statuses[k].updatedAt < v.updatedAt) statuses[k] = v;
  }
  await writeJson(STATIONS_FILE, stations);
  await writeJson(STATUS_FILE, statuses);
  const removed = await cleanupHistory().catch((e) => { logger.warn("이력 정리 실패", e); return 0; });
  if (removed) parts.push(`오래된 이력 ${removed}개 삭제`);
  return { count: Object.keys(stations).length, message: `급속 충전소 ${Object.keys(stations).length}곳 / ${Object.keys(statuses).length}대 · ${parts.join(" · ")}` };
}

/** 휴게소 충전기 상태를 [slotAt] 이 속한 5분 칸에 이력으로 저장 (실데이터 재현용). 반환: 기록한 충전기 수 */
async function recordHistory(stations: StationMap, statuses: StatusMap, now: number, slotAt: number): Promise<number> {
  const order = restAreaOrder(stations);
  const ver = orderVersion(order);
  const indexName = `history/index/${ver}.json`;
  if (!(await readJson<string[]>(indexName))) await writeJson(indexName, order);
  const { day, time } = tickName(slotAt);
  await writeBuffer(`history/${day}/${time}-${ver}.bin`, encodeTick(order, statuses, now));
  return order.length;
}

/** [KEEP_DAYS] 보다 오래된 이력 삭제 */
async function cleanupHistory(): Promise<number> {
  const cutoff = tickName(Date.now() - KEEP_DAYS * 86_400_000).day;
  const days = new Set((await listNames("history/2")).map((n) => n.split("/")[1]));
  let n = 0;
  for (const d of days) if (d < cutoff) n += await deletePrefix(`history/${d}/`);
  return n;
}

/** 모든 출처의 상태 변경분 반영 + 혼잡도 통계 갱신 + 예측 검증. */
export function syncStatusNow(): Promise<SyncResult> {
  return run("status", async () => {
    // 이력 칸은 실행 시작 시각으로 정한다. 응답이 느려 다음 칸으로 넘어가면 다음 실행이 덮어써 이 칸이 비기 때문
    const tickAt = Date.now();
    const { stations, statuses } = await loadSnapshot();
    if (!stations) throw new Error("충전소 목록이 없습니다. 충전소 목록 갱신을 먼저 실행하세요.");
    let total = 0;
    let applied = 0;
    let failed = 0;
    for (const p of PROVIDERS) {
      try {
        const updates = await p.fetchStatusChanges();
        total += updates.length;
        applied += applyStatus(statuses, stations, updates);
      } catch (e) {
        failed++;
        logger.error(`${p.id} 상태 갱신 실패`, e);
      }
    }
    // 새 관측이 하나도 없으면 지난 상태를 이력·통계에 또 쌓지 않는다 (재현·혼잡 예측이 멈춘 상태를 실제로 착각함)
    if (failed === PROVIDERS.length) throw new Error("모든 출처에서 상태를 받지 못해 이번 기록은 건너뜀");
    await writeJson(STATUS_FILE, statuses);

    const now = Date.now();
    const occ = (await readJson<OccupancyMap>(OCCUPANCY_FILE)) ?? {};
    // 이번 관측을 통계에 넣기 전에 예측과 비교 (미래 정보 누설 방지)
    const val = (await readJson<ValidationMap>(VALIDATION_FILE)) ?? {};
    const lag = (await readJson<LagMap>(LAG_FILE)) ?? {};
    const v = validateAndShift(val, lag, occ, stations, statuses, now, kstDate(now));
    await writeJson(VALIDATION_FILE, val);
    await writeJson(LAG_FILE, lag);

    const m = recordOccupancy(occ, stations, statuses, now);
    await writeJson(OCCUPANCY_FILE, occ);

    const h = await recordHistory(stations, statuses, now, tickAt);
    return { count: applied, message: `변경 ${total}건 중 급속 ${applied}건 반영, 혼잡도 ${m}곳 갱신, 예측 검증 ${v}곳, 이력 ${h}대` };
  });
}

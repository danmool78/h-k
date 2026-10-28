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
import { LAG_FILE, OCCUPANCY_FILE, STATIONS_FILE, STATUS_FILE, VALIDATION_FILE, readJson, writeJson } from "./storage";
import { LagMap, ValidationMap, validateAndShift } from "./validation";

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
  return { count: Object.keys(stations).length, message: `급속 충전소 ${Object.keys(stations).length}곳 / ${Object.keys(statuses).length}대 · ${parts.join(" · ")}` };
}

/** 모든 출처의 상태 변경분 반영 + 혼잡도 통계 갱신 + 예측 검증. */
export function syncStatusNow(): Promise<SyncResult> {
  return run("status", async () => {
    const { stations, statuses } = await loadSnapshot();
    if (!stations) throw new Error("충전소 목록이 없습니다. 충전소 목록 갱신을 먼저 실행하세요.");
    let total = 0;
    let applied = 0;
    for (const p of PROVIDERS) {
      try {
        const updates = await p.fetchStatusChanges();
        total += updates.length;
        applied += applyStatus(statuses, stations, updates);
      } catch (e) {
        logger.error(`${p.id} 상태 갱신 실패`, e);
      }
    }
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
    return { count: applied, message: `변경 ${total}건 중 급속 ${applied}건 반영, 혼잡도 ${m}곳 갱신, 예측 검증 ${v}곳` };
  });
}

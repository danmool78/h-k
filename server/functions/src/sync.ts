/**
 * 수집 작업. 정기 실행(index.ts)과 관리자 수동 실행(admin.ts)이 같이 쓴다.
 * 결과는 config/syncState, 호출 수는 usage/{날짜}에 기록.
 */
import { logger } from "firebase-functions";
import { StationMap, StatusMap, applyStatus, buildStations } from "./chargers";
import { SyncResult, addUsage, recordSync, requireApiKey } from "./config";
import { ZCODES, fetchChargerInfo, fetchChargerStatus, takeCallCount } from "./evApi";
import { OccupancyMap, recordOccupancy } from "./occupancy";
import { OCCUPANCY_FILE, STATIONS_FILE, STATUS_FILE, readJson, writeJson } from "./storage";

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

/** 전국 급속 충전소 목록 갱신. */
export function syncStationsNow(): Promise<SyncResult> {
  return run("stations", async () => {
    const key = await requireApiKey();
    const prevStations = (await readJson<StationMap>(STATIONS_FILE)) ?? {};
    const prevStatus = (await readJson<StatusMap>(STATUS_FILE)) ?? {};
    const stations: StationMap = {};
    const statuses: StatusMap = {};
    const failed: string[] = [];

    for (const z of ZCODES) {
      try {
        const built = buildStations(await fetchChargerInfo(z, key));
        Object.assign(stations, built.stations);
        Object.assign(statuses, built.statuses);
      } catch (e) {
        // 한 지역이 실패하면 그 지역은 이전 데이터를 유지
        logger.error(`zcode ${z} 실패, 이전 데이터 유지`, e);
        failed.push(z);
        for (const s of Object.values(prevStations)) {
          if (s.zcode === z) stations[s.id] = s;
        }
      }
    }
    if (failed.length === ZCODES.length) throw new Error("모든 지역 조회 실패");

    // 더 최신 상태가 있으면 유지
    for (const [k, v] of Object.entries(prevStatus)) {
      if (!statuses[k] || statuses[k].updDt < v.updDt) statuses[k] = v;
    }
    await writeJson(STATIONS_FILE, stations);
    await writeJson(STATUS_FILE, statuses);
    const count = Object.keys(stations).length;
    const fail = failed.length ? `, 실패 지역 ${failed.join(",")} (이전 데이터 유지)` : "";
    return { count, message: `급속 충전소 ${count}곳 / ${Object.keys(statuses).length}대${fail}` };
  });
}

/** 최근 10분 상태 변경분 반영 + 혼잡도 통계 갱신. */
export function syncStatusNow(): Promise<SyncResult> {
  return run("status", async () => {
    const stations = await readJson<StationMap>(STATIONS_FILE);
    if (!stations) throw new Error("충전소 목록이 없습니다. 충전소 목록 갱신을 먼저 실행하세요.");
    const key = await requireApiKey();
    const statuses = (await readJson<StatusMap>(STATUS_FILE)) ?? {};
    const items = await fetchChargerStatus(10, key);
    const n = applyStatus(statuses, stations, items);
    await writeJson(STATUS_FILE, statuses);

    const occ = (await readJson<OccupancyMap>(OCCUPANCY_FILE)) ?? {};
    const m = recordOccupancy(occ, stations, statuses, Date.now());
    await writeJson(OCCUPANCY_FILE, occ);
    return { count: n, message: `변경 ${items.length}건 중 급속 ${n}건 반영, 혼잡도 ${m}곳 갱신` };
  });
}

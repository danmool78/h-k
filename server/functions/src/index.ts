/**
 * H-Y Server: 전기차 급속 충전소 실시간 상태 수집 + 조회 API.
 *
 *  - syncStations : 매일 04:00 KST, 전국 급속 충전소 목록 갱신 (getChargerInfo)
 *  - syncStatus   : 5분마다, 최근 10분 상태 변경분 반영 (getChargerStatus)
 *  - api          : GET  /chargers/nearby?lat=&lng=&radiusKm=&limit=
 *                   POST /chargers/match   { points: [{lat, lng}], radiusKm? }  (내비 경로상 충전소 매칭)
 *                   GET  /health
 */
import { initializeApp } from "firebase-admin/app";
import { getStorage } from "firebase-admin/storage";
import { logger } from "firebase-functions";
import { defineSecret } from "firebase-functions/params";
import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { setGlobalOptions } from "firebase-functions/v2";
import {
  StationMap, StatusMap, applyStatus, buildStations, nearby,
} from "./chargers";
import { ZCODES, fetchChargerInfo, fetchChargerStatus } from "./evApi";

initializeApp();
setGlobalOptions({ region: "asia-northeast3" });

const DATA_GO_KR_KEY = defineSecret("DATA_GO_KR_KEY");
const BUCKET = "h-y-server-ev";
const STATIONS_FILE = "stations.json";
const STATUS_FILE = "status.json";

async function bucket() {
  const b = getStorage().bucket(BUCKET);
  const [exists] = await b.exists();
  if (!exists) await b.create({ location: "ASIA-NORTHEAST3" });
  return b;
}

async function readJson<T>(name: string): Promise<T | null> {
  const f = (await bucket()).file(name);
  const [exists] = await f.exists();
  if (!exists) return null;
  const [buf] = await f.download();
  return JSON.parse(buf.toString("utf8")) as T;
}

async function writeJson(name: string, data: unknown) {
  await (await bucket()).file(name).save(JSON.stringify(data), { contentType: "application/json", resumable: false });
}

export const syncStations = onSchedule(
  { schedule: "0 4 * * *", timeZone: "Asia/Seoul", secrets: [DATA_GO_KR_KEY], timeoutSeconds: 1800, memory: "2GiB" },
  async () => {
    const key = DATA_GO_KR_KEY.value();
    const prevStations = (await readJson<StationMap>(STATIONS_FILE)) ?? {};
    const prevStatus = (await readJson<StatusMap>(STATUS_FILE)) ?? {};
    const stations: StationMap = {};
    const statuses: StatusMap = {};

    for (const z of ZCODES) {
      try {
        const built = buildStations(await fetchChargerInfo(z, key));
        Object.assign(stations, built.stations);
        Object.assign(statuses, built.statuses);
      } catch (e) {
        // 한 지역이 실패하면 그 지역은 이전 데이터를 유지
        logger.error(`zcode ${z} 실패, 이전 데이터 유지`, e);
        for (const s of Object.values(prevStations)) {
          if (s.zcode === z) stations[s.id] = s;
        }
      }
    }
    // 더 최신 상태가 있으면 유지
    for (const [k, v] of Object.entries(prevStatus)) {
      if (!statuses[k] || statuses[k].updDt < v.updDt) statuses[k] = v;
    }
    await writeJson(STATIONS_FILE, stations);
    await writeJson(STATUS_FILE, statuses);
    logger.info(`stations ${Object.keys(stations).length}, chargers ${Object.keys(statuses).length}`);
  },
);

export const syncStatus = onSchedule(
  { schedule: "every 5 minutes", timeZone: "Asia/Seoul", secrets: [DATA_GO_KR_KEY], timeoutSeconds: 300, memory: "1GiB" },
  async () => {
    const stations = await readJson<StationMap>(STATIONS_FILE);
    if (!stations) {
      logger.warn("stations.json 없음 — syncStations 를 먼저 실행해야 합니다");
      return;
    }
    const statuses = (await readJson<StatusMap>(STATUS_FILE)) ?? {};
    const items = await fetchChargerStatus(10, DATA_GO_KR_KEY.value());
    const n = applyStatus(statuses, stations, items);
    await writeJson(STATUS_FILE, statuses);
    logger.info(`status 변경 ${items.length}건 중 급속 ${n}건 반영`);
  },
);

// 인스턴스 메모리 캐시 (60초)
let cache: { at: number; stations: StationMap; statuses: StatusMap } | null = null;

async function snapshot() {
  if (cache && Date.now() - cache.at < 60_000) return cache;
  const [stations, statuses] = await Promise.all([
    readJson<StationMap>(STATIONS_FILE),
    readJson<StatusMap>(STATUS_FILE),
  ]);
  cache = { at: Date.now(), stations: stations ?? {}, statuses: statuses ?? {} };
  return cache;
}

function num(v: unknown, def?: number): number | undefined {
  const n = Number(v);
  return v === undefined || v === "" || !Number.isFinite(n) ? def : n;
}

export const api = onRequest({ memory: "1GiB", cors: true }, async (req, res) => {
  try {
    const path = req.path.replace(/\/+$/, "");
    const now = Date.now();

    if (path === "/health") {
      const s = await snapshot();
      res.json({ stations: Object.keys(s.stations).length, chargers: Object.keys(s.statuses).length });
      return;
    }

    if (req.method === "GET" && path === "/chargers/nearby") {
      const lat = num(req.query.lat);
      const lng = num(req.query.lng);
      if (lat === undefined || lng === undefined) {
        res.status(400).json({ error: "lat, lng 필요" });
        return;
      }
      const radiusKm = Math.min(num(req.query.radiusKm, 5)!, 50);
      const limit = Math.min(num(req.query.limit, 20)!, 100);
      const s = await snapshot();
      res.json({ stations: nearby(s.stations, s.statuses, lat, lng, radiusKm, limit, now) });
      return;
    }

    if (req.method === "POST" && path === "/chargers/match") {
      const points: { lat: number; lng: number }[] = req.body?.points ?? [];
      if (!Array.isArray(points) || points.length === 0 || points.length > 50) {
        res.status(400).json({ error: "points 1~50개 필요" });
        return;
      }
      const radiusKm = Math.min(num(req.body?.radiusKm, 0.3)!, 5);
      const s = await snapshot();
      const matches = points.map((p) => nearby(s.stations, s.statuses, p.lat, p.lng, radiusKm, 1, now)[0] ?? null);
      res.json({ matches });
      return;
    }

    res.status(404).json({ error: "not found" });
  } catch (e) {
    logger.error(e);
    res.status(500).json({ error: "internal" });
  }
});

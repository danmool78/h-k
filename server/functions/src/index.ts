/**
 * H-Y Server: 전기차 급속 충전소 실시간 상태 수집 + 조회 API.
 *
 *  - syncStations : 매일 04:00 KST, 전국 급속 충전소 목록 갱신 (getChargerInfo)
 *  - syncStatus   : 5분마다, 최근 10분 상태 변경분 반영 (getChargerStatus)
 *                   + 충전소별 요일×시간대 평균 사용률 갱신 (occupancy.json)
 *  - api          : GET  /chargers/nearby?lat=&lng=&radiusKm=&limit=&etaMin=
 *                   POST /chargers/match   { points: [{lat, lng, etaMin?}], radiusKm? }  (내비 경로상 충전소 매칭)
 *                   etaMin: 도착까지 남은 분 → 그 시각의 과거 평균 사용률(forecast)을 함께 반환
 *                   GET  /health
 *  - admin*       : 관리자 페이지(public/admin)용. API 키 관리, 수집 상태, 수동 실행
 *
 * 공공데이터 API 키는 Firestore config/dataGoKr 에 있고 관리자 페이지에서 갱신한다.
 */
import { initializeApp } from "firebase-admin/app";
import { logger } from "firebase-functions";
import { setGlobalOptions } from "firebase-functions/v2";
import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { StationMap, StationView, StatusMap, nearby } from "./chargers";
import { daysUntil, getApiKeyConfig } from "./config";
import { OccupancyMap, forecast } from "./occupancy";
import { OCCUPANCY_FILE, STATIONS_FILE, STATUS_FILE, readJson } from "./storage";
import { syncStationsNow, syncStatusNow } from "./sync";

initializeApp();
setGlobalOptions({ region: "asia-northeast3" });

export { adminGetStatus, adminRunSync, adminSetKey } from "./admin";

export const syncStations = onSchedule(
  { schedule: "0 4 * * *", timeZone: "Asia/Seoul", timeoutSeconds: 1800, memory: "2GiB" },
  async () => {
    await syncStationsNow();
    const key = await getApiKeyConfig();
    if (key) {
      const days = daysUntil(key.expiresAt);
      if (days <= 30) logger.warn(`공공데이터 API 키 만료 ${days}일 전 (${key.expiresAt}) — 관리자 페이지에서 갱신 필요`);
    }
  },
);

export const syncStatus = onSchedule(
  { schedule: "every 5 minutes", timeZone: "Asia/Seoul", timeoutSeconds: 300, memory: "1GiB" },
  async () => {
    await syncStatusNow();
  },
);

// 인스턴스 메모리 캐시 (60초)
let cache: { at: number; stations: StationMap; statuses: StatusMap; occ: OccupancyMap } | null = null;

async function snapshot() {
  if (cache && Date.now() - cache.at < 60_000) return cache;
  const [stations, statuses, occ] = await Promise.all([
    readJson<StationMap>(STATIONS_FILE),
    readJson<StatusMap>(STATUS_FILE),
    readJson<OccupancyMap>(OCCUPANCY_FILE),
  ]);
  cache = { at: Date.now(), stations: stations ?? {}, statuses: statuses ?? {}, occ: occ ?? {} };
  return cache;
}

function withForecast(v: StationView, occ: OccupancyMap, now: number, etaMin: number | undefined) {
  return etaMin === undefined ? v : { ...v, forecast: forecast(occ, v.id, now + etaMin * 60_000) };
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
      const etaMin = num(req.query.etaMin);
      const s = await snapshot();
      const views = nearby(s.stations, s.statuses, lat, lng, radiusKm, limit, now);
      res.json({ stations: views.map((v) => withForecast(v, s.occ, now, etaMin)) });
      return;
    }

    if (req.method === "POST" && path === "/chargers/match") {
      const points: { lat: number; lng: number; etaMin?: number }[] = req.body?.points ?? [];
      if (!Array.isArray(points) || points.length === 0 || points.length > 50) {
        res.status(400).json({ error: "points 1~50개 필요" });
        return;
      }
      const radiusKm = Math.min(num(req.body?.radiusKm, 0.3)!, 5);
      const s = await snapshot();
      const matches = points.map((p) => {
        const v = nearby(s.stations, s.statuses, p.lat, p.lng, radiusKm, 1, now)[0];
        return v ? withForecast(v, s.occ, now, num(p.etaMin)) : null;
      });
      res.json({ matches });
      return;
    }

    res.status(404).json({ error: "not found" });
  } catch (e) {
    logger.error(e);
    res.status(500).json({ error: "internal" });
  }
});

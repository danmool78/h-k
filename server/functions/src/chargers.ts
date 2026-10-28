/**
 * 충전소 스냅샷 조회 로직 (출처 무관, Firebase 의존 없음 → 단위 테스트 가능).
 * 형식은 providers/types.ts.
 */
import { Station, StationMap, StatusMap, StatusUpdate, statusKey } from "./providers/types";

export type { Station, StationMap, StatusMap } from "./providers/types";

/** 상태 변경분 반영. 알려진 급속 충전기만, 더 최신일 때만 갱신. 반영 개수를 반환. */
export function applyStatus(statuses: StatusMap, stations: StationMap, updates: StatusUpdate[]): number {
  let n = 0;
  for (const u of updates) {
    if (!stations[u.stationId]?.chargers[u.evseId]) continue;
    const key = statusKey(u.stationId, u.evseId);
    const prev = statuses[key];
    if (prev && prev.updatedAt >= u.state.updatedAt) continue;
    statuses[key] = u.state;
    n++;
  }
  return n;
}

/** 충전 1회 평균 소요(분). 출력이 높을수록 짧다고 가정 — 이력 데이터로 교체 예정. */
export function typicalSessionMin(kw: number): number {
  if (kw >= 150) return 25;
  if (kw >= 100) return 32;
  return 45;
}

export interface ChargerView {
  id: string;
  kw: number;
  status: string;
  chargingMin: number | null;   // 충전 중이면 경과 분
}

export interface StationView {
  id: string;
  provider: string;
  country: string;
  name: string;
  addr: string;
  lat: number;
  lng: number;
  operator: string;
  highway: boolean;
  distanceKm: number;
  maxKw: number;
  total: number;
  available: number;
  charging: number;
  outOfService: number;
  /** 예상 대기(분). 빈 충전기가 있으면 0, 판단 불가면 null. */
  waitMin: number | null;
  chargers: ChargerView[];
}

export function stationView(s: Station, statuses: StatusMap, now: number, distanceKm: number): StationView {
  let available = 0;
  let charging = 0;
  let outOfService = 0;
  let maxKw = 0;
  const remaining: number[] = [];
  const chargers: ChargerView[] = [];

  for (const [cid, spec] of Object.entries(s.chargers)) {
    const st = statuses[statusKey(s.id, cid)];
    const status = st?.status ?? "UNKNOWN";
    maxKw = Math.max(maxKw, spec.kw);
    let chargingMin: number | null = null;
    if (status === "AVAILABLE") {
      available++;
    } else if (status === "CHARGING") {
      charging++;
      chargingMin = st?.sessionStartMs != null ? Math.max(0, Math.round((now - st.sessionStartMs) / 60_000)) : null;
      remaining.push(Math.max(5, typicalSessionMin(spec.kw) - (chargingMin ?? 0)));
    } else {
      outOfService++;
    }
    chargers.push({ id: cid, kw: spec.kw, status, chargingMin });
  }

  const waitMin = available > 0 ? 0 : remaining.length > 0 ? Math.min(...remaining) : null;
  return {
    id: s.id, provider: s.provider, country: s.country, name: s.name, addr: s.addr, lat: s.lat, lng: s.lng,
    operator: s.operator, highway: s.highway,
    distanceKm: Math.round(distanceKm * 100) / 100,
    maxKw, total: chargers.length, available, charging, outOfService, waitMin, chargers,
  };
}

export function haversineKm(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6371;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

export function nearby(
  stations: StationMap, statuses: StatusMap, lat: number, lng: number, radiusKm: number, limit: number, now: number,
): StationView[] {
  const hits: [Station, number][] = [];
  for (const s of Object.values(stations)) {
    const d = haversineKm(lat, lng, s.lat, s.lng);
    if (d <= radiusKm) hits.push([s, d]);
  }
  hits.sort((a, b) => a[1] - b[1]);
  return hits.slice(0, limit).map(([s, d]) => stationView(s, statuses, now, d));
}

/**
 * 출처 구분 전 형식의 충전소 ID 키(접두사 없는 한국 ID) → "KR:" 접두사 (혼잡도 통계·1시간 전 기록 이전용, 한 번만 필요).
 * 반환: 바꾼 게 있는지.
 */
export function migrateStationKeys<T>(map: Record<string, T>): boolean {
  let changed = false;
  for (const k of Object.keys(map)) {
    if (k.includes(":")) continue;
    map[`KR:${k}`] = map[k];
    delete map[k];
    changed = true;
  }
  return changed;
}

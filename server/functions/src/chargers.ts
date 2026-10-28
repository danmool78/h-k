/**
 * 충전소 스냅샷 모델과 조회 로직 (Firebase 의존 없음 → 단위 테스트 가능).
 */
import { ChargerInfoItem, ChargerStatusItem, isFastCharger } from "./evApi";

/** 충전기 상태 코드. */
export const Stat = {
  COMM_ERROR: "1",
  AVAILABLE: "2",
  CHARGING: "3",
  SUSPENDED: "4",
  MAINTENANCE: "5",
  UNKNOWN: "9",
} as const;

export interface ChargerSpec {
  type: string;
  kw: number;
}

export interface Station {
  id: string;
  name: string;
  addr: string;
  lat: number;
  lng: number;
  operator: string;
  highway: boolean;
  zcode: string;
  chargers: Record<string, ChargerSpec>;
}

/** 충전기별 동적 상태. key = `${statId}:${chgerId}` */
export interface ChargerStatus {
  stat: string;
  updDt: string;
  nowTsdt: string;
  lastTsdt: string;
  lastTedt: string;
}

export type StationMap = Record<string, Station>;
export type StatusMap = Record<string, ChargerStatus>;

export const statusKey = (statId: string, chgerId: string) => `${statId}:${chgerId}`;

/** getChargerInfo 결과에서 급속 충전소만 모아 스테이션/초기 상태 생성. */
export function buildStations(items: ChargerInfoItem[]): { stations: StationMap; statuses: StatusMap } {
  const stations: StationMap = {};
  const statuses: StatusMap = {};
  for (const it of items) {
    if (it.delYn === "Y" || !isFastCharger(it)) continue;
    const lat = Number(it.lat);
    const lng = Number(it.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat === 0) continue;

    const s = (stations[it.statId] ??= {
      id: it.statId,
      name: it.statNm,
      addr: it.addr,
      lat,
      lng,
      operator: it.busiNm,
      highway: it.trafficYn === "Y",
      zcode: it.zcode,
      chargers: {},
    });
    s.chargers[it.chgerId] = { type: it.chgerType, kw: Number(it.output) || 50 };
    statuses[statusKey(it.statId, it.chgerId)] = {
      stat: it.stat,
      updDt: it.statUpdDt,
      nowTsdt: it.nowTsdt,
      lastTsdt: it.lastTsdt,
      lastTedt: it.lastTedt,
    };
  }
  return { stations, statuses };
}

/** 상태 변경분을 반영. 알려진 급속 충전기만 갱신하고, 반영 개수를 반환. */
export function applyStatus(statuses: StatusMap, stations: StationMap, items: ChargerStatusItem[]): number {
  let n = 0;
  for (const it of items) {
    if (!stations[it.statId]?.chargers[it.chgerId]) continue;
    const key = statusKey(it.statId, it.chgerId);
    const prev = statuses[key];
    if (prev && prev.updDt >= it.statUpdDt) continue;
    statuses[key] = {
      stat: it.stat,
      updDt: it.statUpdDt,
      nowTsdt: it.nowTsdt,
      lastTsdt: it.lastTsdt,
      lastTedt: it.lastTedt,
    };
    n++;
  }
  return n;
}

/** "yyyyMMddHHmmss" (KST) → epoch ms. 빈 값이면 null. */
export function parseKst(s: string | undefined): number | null {
  if (!s || s.length < 12) return null;
  const iso = `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}T${s.slice(8, 10)}:${s.slice(10, 12)}:${s.slice(12, 14) || "00"}+09:00`;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
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
  stat: string;
  chargingMin: number | null;   // 충전 중이면 경과 분
}

export interface StationView {
  id: string;
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
    const stat = st?.stat ?? Stat.UNKNOWN;
    maxKw = Math.max(maxKw, spec.kw);
    let chargingMin: number | null = null;
    if (stat === Stat.AVAILABLE) {
      available++;
    } else if (stat === Stat.CHARGING) {
      charging++;
      const start = parseKst(st?.nowTsdt);
      chargingMin = start != null ? Math.max(0, Math.round((now - start) / 60_000)) : null;
      remaining.push(Math.max(5, typicalSessionMin(spec.kw) - (chargingMin ?? 0)));
    } else {
      outOfService++;
    }
    chargers.push({ id: cid, kw: spec.kw, stat, chargingMin });
  }

  const waitMin = available > 0 ? 0 : remaining.length > 0 ? Math.min(...remaining) : null;
  return {
    id: s.id,
    name: s.name,
    addr: s.addr,
    lat: s.lat,
    lng: s.lng,
    operator: s.operator,
    highway: s.highway,
    distanceKm: Math.round(distanceKm * 100) / 100,
    maxKw,
    total: chargers.length,
    available,
    charging,
    outOfService,
    waitMin,
    chargers,
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

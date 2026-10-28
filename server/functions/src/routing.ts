/**
 * 도로 경로 + 경로를 따라 있는 충전소 찾기.
 *
 * 경로는 OSRM (OpenStreetMap 기반, 전 세계) — 공개 서버는 개발·데모용이라 상용화 시 자체 서버나 다른 경로 API로 교체.
 * 차량 내비(Pleos NaviHelper)는 경로 좌표를 주지 않으므로 경로상 충전소 탐색은 여기서 한다.
 */
import { StationMap, haversineKm } from "./chargers";

export interface LatLng { lat: number; lng: number }

export interface Route {
  distanceKm: number;
  durationMin: number;
  /** [lat, lng] 목록 */
  coords: [number, number][];
  /** coords[i] 까지 누적 거리 (km) */
  cumKm: number[];
  /** 구간 i (coords[i] → coords[i+1]) 가 고속도로인지 (경로 API 속도 기준). 모르면 없음 */
  fastSeg?: boolean[];
  /** 구간 i 의 주행 시간 (초). 실제 우회 시간 계산용 */
  segSec?: number[];
}

/** 경로 API 교체용 규격 */
export interface RouteProvider {
  id: string;
  route(from: LatLng, to: LatLng): Promise<Route>;
  /** 여러 지점을 차례로 들르는 주행 시간 (분) */
  routeVia(points: LatLng[]): Promise<number>;
}

const OSRM = "https://router.project-osrm.org/route/v1/driving";

export function withCumulative(
  coords: [number, number][], distanceKm: number, durationMin: number, fastSeg?: boolean[], segSec?: number[],
): Route {
  const cumKm = [0];
  for (let i = 1; i < coords.length; i++) {
    cumKm.push(cumKm[i - 1] + haversineKm(coords[i - 1][0], coords[i - 1][1], coords[i][0], coords[i][1]));
  }
  return { distanceKm, durationMin, coords, cumKm, fastSeg, segSec };
}

/** 이 속도(m/s, 약 79km/h) 이상인 구간을 고속도로로 본다 */
export const HIGHWAY_SPEED_MS = 22;

/**
 * 경로 위 [km](누적 거리 기준) 가 고속도로 구간인지. 요금소처럼 잠깐 느린 곳에 흔들리지 않게
 * 앞뒤 1km 구간 길이의 절반 이상이 고속이면 고속도로로 본다.
 */
export function onHighway(r: Route, km: number): boolean {
  if (!r.fastSeg) return false;
  let fast = 0, all = 0;
  for (let i = 0; i < r.fastSeg.length; i++) {
    const a = r.cumKm[i], b = r.cumKm[i + 1];
    if (b < km - 1) continue;
    if (a > km + 1) break;
    const len = Math.min(b, km + 1) - Math.max(a, km - 1);
    if (len <= 0) continue;
    all += len;
    if (r.fastSeg[i]) fast += len;
  }
  return all > 0 && fast / all >= 0.5;
}

export const osrm: RouteProvider = {
  id: "osrm",
  async routeVia(points) {
    const url = `${OSRM}/${points.map((p) => `${p.lng},${p.lat}`).join(";")}?overview=false`;
    const res = await fetch(url, { signal: AbortSignal.timeout(6_000) });
    if (!res.ok) throw new Error(`OSRM HTTP ${res.status}`);
    const body = (await res.json()) as { code: string; routes?: { duration: number }[] };
    if (body.code !== "Ok" || !body.routes?.length) throw new Error(`OSRM ${body.code}`);
    return body.routes[0].duration / 60;
  },
  async route(from, to) {
    const url = `${OSRM}/${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&geometries=geojson&annotations=speed,duration`;
    const res = await fetch(url, { signal: AbortSignal.timeout(10_000) });
    if (!res.ok) throw new Error(`OSRM HTTP ${res.status}`);
    const body = (await res.json()) as {
      code: string; message?: string;
      routes?: {
        distance: number; duration: number; geometry: { coordinates: [number, number][] };
        legs?: { annotation?: { speed?: number[]; duration?: number[] } }[];
      }[];
    };
    if (body.code !== "Ok" || !body.routes?.length) throw new Error(`OSRM ${body.code}: ${body.message ?? "경로 없음"}`);
    const r = body.routes![0];
    const coords = (r.geometry.coordinates as [number, number][]).map(([lng, lat]) => [lat, lng] as [number, number]);
    const speeds = r.legs?.flatMap((l) => l.annotation?.speed ?? []) ?? [];
    const fastSeg = speeds.length === coords.length - 1 ? speeds.map((v) => v >= HIGHWAY_SPEED_MS) : undefined;
    const secs = r.legs?.flatMap((l) => l.annotation?.duration ?? []) ?? [];
    const segSec = secs.length === coords.length - 1 ? secs : undefined;
    return withCumulative(coords, r.distance / 1000, r.duration / 60, fastSeg, segSec);
  },
};

const cache = new Map<string, { at: number; route: Route }>();
const CACHE_MS = 60 * 60_000;

export async function routeCached(p: RouteProvider, from: LatLng, to: LatLng): Promise<Route> {
  const key = `${p.id}:${from.lat.toFixed(3)},${from.lng.toFixed(3)}:${to.lat.toFixed(3)},${to.lng.toFixed(3)}`;
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.route;
  const route = await p.route(from, to);
  cache.set(key, { at: Date.now(), route });
  if (cache.size > 200) cache.delete(cache.keys().next().value!);
  return route;
}

/** 경로 위 [km] 지점의 좌표 */
export function pointAtKm(r: Route, km: number): LatLng {
  const k = Math.min(Math.max(km, 0), r.cumKm[r.cumKm.length - 1]);
  let i = r.cumKm.findIndex((c) => c >= k);
  if (i <= 0) return { lat: r.coords[0][0], lng: r.coords[0][1] };
  const f = (k - r.cumKm[i - 1]) / Math.max(r.cumKm[i] - r.cumKm[i - 1], 1e-9);
  return { lat: r.coords[i - 1][0] + (r.coords[i][0] - r.coords[i - 1][0]) * f, lng: r.coords[i - 1][1] + (r.coords[i][1] - r.coords[i - 1][1]) * f };
}

/**
 * 지도 표시용 좌표 줄이기 (Douglas-Peucker). 도로 모양에서 [toleranceM] 이상 벗어나지 않는 한 점을 뺀다.
 * 직선 구간은 성기게, 굽은 구간은 촘촘하게 남아 지도 위 선이 도로를 그대로 따라간다.
 */
export function simplifyShape(coords: [number, number][], toleranceM = 10): [number, number][] {
  if (coords.length <= 2) return coords;
  const lat0 = coords[0][0] * Math.PI / 180;
  // 위경도 → 대략적인 미터 평면
  const xy = coords.map(([lat, lng]) => [lng * 111_320 * Math.cos(lat0), lat * 110_540]);
  const keep = new Uint8Array(coords.length);
  keep[0] = keep[coords.length - 1] = 1;
  const stack: [number, number][] = [[0, coords.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop()!;
    const [ax, ay] = xy[a], [bx, by] = xy[b];
    const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
    let maxD = 0, idx = -1;
    for (let i = a + 1; i < b; i++) {
      const [px, py] = xy[i];
      const t = len2 > 0 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2)) : 0;
      const d = Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
      if (d > maxD) { maxD = d; idx = i; }
    }
    if (maxD > toleranceM && idx > 0) {
      keep[idx] = 1;
      stack.push([a, idx], [idx, b]);
    }
  }
  return coords.filter((_, i) => keep[i]);
}

/** 약 [stepKm] 간격으로 줄인 좌표 (지도 표시에는 [simplifyShape] 를 쓸 것) */
export function simplify(r: Route, stepKm = 1): [number, number][] {
  const out: [number, number][] = [r.coords[0]];
  let next = stepKm;
  for (let i = 1; i < r.coords.length; i++) {
    if (r.cumKm[i] >= next || i === r.coords.length - 1) { out.push(r.coords[i]); next = r.cumKm[i] + stepKm; }
  }
  return out;
}

export interface RouteStation {
  stationId: string;
  /** 출발부터 경로를 따라 이 충전소 옆까지 거리 */
  atKm: number;
  /** 경로에서 벗어난 직선거리 (km) */
  offRouteKm: number;
  /** 고속도로 휴게소·졸음쉼터 (진행 방향 쪽만 남김) */
  restArea: boolean;
}

/** 휴게소는 차로에 붙어 있으므로 0.5km, 일반 충전소는 2km 안쪽만 */
export const CORRIDOR_REST_KM = 0.5;
export const CORRIDOR_KM = 2.0;

/** 좌측 통행 국가 (ISO 3166-1) */
export const LEFT_HAND_TRAFFIC = new Set(["GB", "IE", "JP", "AU", "NZ", "IN", "TH", "MY", "SG", "ID", "HK", "ZA"]);

/** 이름으로 휴게소 판정 (원본 데이터의 고속도로 표시는 부정확해서 쓰지 않음) */
export const isRestArea = (name: string) => /휴게소|졸음쉼터/.test(name);

/** 경로 진행 방향 기준 오른쪽(우측 통행 국가에서 내 방향 휴게소)인지. 경도·위도 평면의 외적 부호. */
function onRightSide(r: Route, km: number, lat: number, lng: number): boolean {
  const scale = r.cumKm[r.cumKm.length - 1] / r.distanceKm;
  const a = pointAtKm(r, (km - 0.3) * scale), b = pointAtKm(r, (km + 0.3) * scale);
  const p = pointAtKm(r, km * scale);
  const cosLat = Math.cos(p.lat * Math.PI / 180);
  const dx = (b.lng - a.lng) * cosLat, dy = b.lat - a.lat;
  const vx = (lng - p.lng) * cosLat, vy = lat - p.lat;
  return dx * vy - dy * vx < 0;
}

/**
 * 경로를 따라 있는 충전소. 충전소를 0.05° 격자에 넣어 두고, 경로를 약 1km 간격으로 훑으며 가까운 격자만 본다.
 * 출발·도착 근처(각 [edgeKm])는 제외. 휴게소는 진행 방향 쪽(우측 통행이면 오른쪽, 좌측 통행 국가면 왼쪽)만 남긴다.
 * 고속도로 구간에서는 휴게소·졸음쉼터만, 일반 충전소는 고속도로가 아닌 구간에서만 후보로 둔다.
 */
export function stationsAlongRoute(r: Route, stations: StationMap, edgeKm = 15): RouteStation[] {
  const CELL = 0.05;
  const grid = new Map<string, string[]>();
  for (const s of Object.values(stations)) {
    const k = `${Math.floor(s.lat / CELL)}:${Math.floor(s.lng / CELL)}`;
    (grid.get(k) ?? grid.set(k, []).get(k)!).push(s.id);
  }
  const best = new Map<string, RouteStation>();
  const total = r.distanceKm;
  const scale = r.cumKm[r.cumKm.length - 1] / total;
  for (let km = edgeKm; km <= total - edgeKm; km += 1) {
    const p = pointAtKm(r, km * scale);
    const gi = Math.floor(p.lat / CELL), gj = Math.floor(p.lng / CELL);
    for (let di = -1; di <= 1; di++) for (let dj = -1; dj <= 1; dj++) {
      for (const id of grid.get(`${gi + di}:${gj + dj}`) ?? []) {
        const s = stations[id];
        const rest = isRestArea(s.name);
        // 고속도로 구간에서는 IC로 나가야 하는 일반 충전소는 제외 (실제 우회 10~20분 + 통행료)
        if (!rest && onHighway(r, km * scale)) continue;
        const off = haversineKm(p.lat, p.lng, s.lat, s.lng);
        if (off > (rest ? CORRIDOR_REST_KM : CORRIDOR_KM)) continue;
        const prev = best.get(id);
        if (!prev || off < prev.offRouteKm) best.set(id, { stationId: id, atKm: km, offRouteKm: off, restArea: rest });
      }
    }
  }
  return [...best.values()]
    .filter((rs) => {
      if (!rs.restArea) return true;
      const s = stations[rs.stationId];
      return onRightSide(r, rs.atKm, s.lat, s.lng) === !LEFT_HAND_TRAFFIC.has(s.country);
    })
    .sort((a, b) => a.atKm - b.atKm);
}

/**
 * 계산량을 줄이려 [binKm] 구간마다 좋은 후보만 남긴다.
 * 점수: 휴게소 > 최대 출력 > 충전기 수 > 경로에서 가까움.
 */
export function pickCandidates(
  along: RouteStation[], stations: StationMap, binKm = 20, perBin = 2,
): RouteStation[] {
  const bins = new Map<number, RouteStation[]>();
  for (const rs of along) {
    const b = Math.floor(rs.atKm / binKm);
    (bins.get(b) ?? bins.set(b, []).get(b)!).push(rs);
  }
  const score = (rs: RouteStation) => {
    const s = stations[rs.stationId];
    const maxKw = Math.max(...Object.values(s.chargers).map((c) => c.kw));
    const n = Object.keys(s.chargers).length;
    return (rs.restArea ? 1000 : 0) + maxKw + n * 10 - rs.offRouteKm * 30;
  };
  return [...bins.values()].flatMap((list) => list.sort((a, b) => score(b) - score(a)).slice(0, perBin))
    .sort((a, b) => a.atKm - b.atKm);
}

/** 경로 위 [kmA]~[kmB] (누적 거리 기준) 구간의 원래 주행 시간 (분). 구간 시간이 없으면 평균 속도로 */
export function segmentMinutes(r: Route, kmA: number, kmB: number): number {
  if (!r.segSec) return (kmB - kmA) / r.distanceKm * r.durationMin;
  let sec = 0;
  for (let i = 0; i < r.segSec.length; i++) {
    const a = r.cumKm[i], b = r.cumKm[i + 1];
    if (b <= kmA) continue;
    if (a >= kmB) break;
    const len = b - a;
    if (len > 0) sec += r.segSec[i] * (Math.min(b, kmB) - Math.max(a, kmA)) / len;
  }
  return sec / 60;
}

/** 실제 우회를 볼 때 충전소 앞뒤로 잡는 경로 구간 (km) */
export const DETOUR_WINDOW_KM = 2;
/** 실제 우회가 이보다 크면 후보에서 뺀다 (분) */
export const MAX_DETOUR_MIN = 20;

const detourCache = new Map<string, number>();

/**
 * 실제 도로 기준 우회 시간 (분): "경로상 앞 지점 → 충전소 → 경로상 뒤 지점" 주행 시간 − 원래 그 구간 시간 + 진입·주차 1분.
 * 경로 API가 일방통행·회전 금지를 반영하므로 유턴·길 건너가 필요한 곳은 자연히 커진다. 실패하면 null.
 */
export async function realDetourMin(p: RouteProvider, r: Route, rs: RouteStation, station: { lat: number; lng: number }): Promise<number | null> {
  const scale = r.cumKm[r.cumKm.length - 1] / r.distanceKm;
  const kmA = Math.max(0, rs.atKm * scale - DETOUR_WINDOW_KM);
  const kmB = Math.min(r.cumKm[r.cumKm.length - 1], rs.atKm * scale + DETOUR_WINDOW_KM);
  const a = pointAtKm(r, kmA), b = pointAtKm(r, kmB);
  const key = `${rs.stationId}:${a.lat.toFixed(3)},${a.lng.toFixed(3)}:${b.lat.toFixed(3)},${b.lng.toFixed(3)}`;
  if (detourCache.has(key)) return detourCache.get(key)!;
  try {
    const via = await p.routeVia([a, station, b]);
    const d = Math.max(1, via - segmentMinutes(r, kmA, kmB) + 1);
    detourCache.set(key, d);
    if (detourCache.size > 5000) detourCache.delete(detourCache.keys().next().value!);
    return d;
  } catch {
    return null;
  }
}

/** 우회 시간 평균 (분). 휴게소는 진출입 1분, 그 외는 왕복 이탈 거리를 시내 평균 30km/h + 진출입 1분 */
export const detourMin = (offRouteKm: number, restArea = false) => restArea ? 1 : 1 + (2 * offRouteKm / 30) * 60;

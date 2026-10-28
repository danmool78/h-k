/**
 * 날씨: Open-Meteo (전 세계, 키 불필요, 비상업 무료 · 상용화 시 유료 키로 교체).
 * 데이터 출처 표시 필요: "Weather data by Open-Meteo.com" (CC BY 4.0)
 *
 * 지점·시각별 기온과 맑음/비/눈 판정을 돌려준다. 0.1° 격자 × 시각(1시간) 단위로 30분 캐시.
 */

const ENDPOINT = "https://api.open-meteo.com/v1/forecast";
const CACHE_MS = 30 * 60_000;
const GRID = 0.1;

export type WeatherKind = "CLEAR" | "RAIN" | "SNOW";

export interface PointWeather {
  lat: number;
  lng: number;
  /** 조회 시각 (epoch ms, 1시간 단위로 내림) */
  at: number;
  tempC: number;
  precipMm: number;
  snowCm: number;
  weatherCode: number;
  kind: WeatherKind;
}

/** WMO 날씨 코드 + 강수·강설량으로 판정. */
export function classify(code: number, precipMm: number, snowCm: number): WeatherKind {
  const snowCodes = (code >= 71 && code <= 77) || code === 85 || code === 86;
  const rainCodes = (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
  if (snowCm >= 0.1 || snowCodes) return "SNOW";
  if (precipMm >= 0.2 || rainCodes) return "RAIN";
  return "CLEAR";
}

interface Hourly {
  time: string[];
  temperature_2m: number[];
  precipitation: number[];
  snowfall: number[];
  weather_code: number[];
}

const cache = new Map<string, { at: number; hourly: Hourly }>();
const gridKey = (lat: number, lng: number) => `${Math.round(lat / GRID)}:${Math.round(lng / GRID)}`;

/** 격자별 시간별 예보 (캐시 없으면 Open-Meteo 한 번에 여러 지점 조회). */
async function hourlyFor(points: { lat: number; lng: number }[]): Promise<Hourly[]> {
  const now = Date.now();
  const keys = points.map((p) => gridKey(p.lat, p.lng));
  const missing = [...new Set(keys.filter((k) => !(cache.get(k) && now - cache.get(k)!.at < CACHE_MS)))];
  if (missing.length) {
    const coords = missing.map((k) => k.split(":").map((v) => Number(v) * GRID));
    const qs = new URLSearchParams({
      latitude: coords.map((c) => c[0].toFixed(2)).join(","),
      longitude: coords.map((c) => c[1].toFixed(2)).join(","),
      hourly: "temperature_2m,precipitation,snowfall,weather_code",
      forecast_days: "3",
      timezone: "GMT",
    });
    const res = await fetch(`${ENDPOINT}?${qs}`, { signal: AbortSignal.timeout(8_000) });
    if (!res.ok) throw new Error(`Open-Meteo HTTP ${res.status}`);
    const body = await res.json();
    const list = Array.isArray(body) ? body : [body];
    list.forEach((b: { hourly: Hourly }, i: number) => cache.set(missing[i], { at: now, hourly: b.hourly }));
  }
  return keys.map((k) => cache.get(k)!.hourly);
}

/** 각 지점의 [at] 시각 날씨. at 은 epoch ms. 예보 범위(3일) 밖이면 가장 가까운 시각을 쓴다. */
export async function weatherAt(points: { lat: number; lng: number; at: number }[]): Promise<PointWeather[]> {
  const hourly = await hourlyFor(points);
  return points.map((p, i) => {
    const h = hourly[i];
    const hourMs = Math.floor(p.at / 3_600_000) * 3_600_000;
    const times = h.time.map((t) => Date.parse(`${t}:00Z`));
    let idx = times.findIndex((t) => t === hourMs);
    if (idx < 0) idx = hourMs < times[0] ? 0 : times.length - 1;
    const tempC = h.temperature_2m[idx];
    const precipMm = h.precipitation[idx] ?? 0;
    const snowCm = h.snowfall[idx] ?? 0;
    const code = h.weather_code[idx] ?? 0;
    return { lat: p.lat, lng: p.lng, at: times[idx], tempC, precipMm, snowCm, weatherCode: code, kind: classify(code, precipMm, snowCm) };
  });
}

/**
 * 경로 대표값: 기온은 지점 평균, 날씨는 2곳 이상이 눈이면 눈, 아니면 2곳 이상 비면 비.
 * (모델 입력이 경로 전체에 하나라서. 구간별 반영은 추후)
 */
export function summarizeRoute(ws: PointWeather[]): { tempC: number; kind: WeatherKind } {
  const tempC = ws.reduce((s, w) => s + w.tempC, 0) / Math.max(ws.length, 1);
  const snow = ws.filter((w) => w.kind === "SNOW").length;
  const rain = ws.filter((w) => w.kind === "RAIN").length;
  const need = Math.min(2, ws.length);
  const kind: WeatherKind = snow >= need ? "SNOW" : snow + rain >= need ? "RAIN" : "CLEAR";
  return { tempC: Math.round(tempC * 10) / 10, kind };
}

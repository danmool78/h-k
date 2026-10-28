/**
 * 충전소 혼잡도 요약 통계.
 *
 * 충전소마다 요일 × 시간대(7 × 24 = 168칸)의 평균 사용률(충전중 / 사용가능 충전기)을 유지한다.
 * 원본 로그는 쌓지 않고 칸별 평균만 갱신하므로 저장 크기는 충전소 수에 비례해 고정된다.
 *
 * 저장 형식: statId → base64(336바이트) = 사용률 168바이트(0~200, 0.5% 단위) + 표본수 168바이트(최대 MAX_N)
 */
import { Stat, StationMap, StatusMap, statusKey } from "./chargers";

export const SLOTS = 168;
/** 칸당 표본 상한. 5분 간격이면 칸당 주 12회 → 약 4주 이동평균. */
export const MAX_N = 48;
/** 이 표본 수 미만이면 예측값을 내지 않음. */
export const MIN_N_FOR_FORECAST = 6;

export type OccupancyMap = Record<string, string>;

const KST_OFFSET_MS = 9 * 3600_000;

/** KST 기준 요일(일=0) × 24 + 시. */
export function slotOf(epochMs: number): number {
  const d = new Date(epochMs + KST_OFFSET_MS);
  return d.getUTCDay() * 24 + d.getUTCHours();
}

function decode(s: string | undefined): Uint8Array {
  if (!s) return new Uint8Array(SLOTS * 2);
  return new Uint8Array(Buffer.from(s, "base64"));
}

function encode(a: Uint8Array): string {
  return Buffer.from(a).toString("base64");
}

/** 현재 사용률(0~1). 운영 중인 충전기가 없으면 null. */
export function currentBusyRatio(statId: string, chargerIds: string[], statuses: StatusMap): number | null {
  let charging = 0;
  let inService = 0;
  for (const cid of chargerIds) {
    const stat = statuses[statusKey(statId, cid)]?.stat;
    if (stat === Stat.CHARGING) {
      charging++;
      inService++;
    } else if (stat === Stat.AVAILABLE) {
      inService++;
    }
  }
  return inService === 0 ? null : charging / inService;
}

/** 모든 충전소의 현재 사용률을 [now] 칸에 반영. 갱신한 충전소 수를 반환. */
export function recordOccupancy(occ: OccupancyMap, stations: StationMap, statuses: StatusMap, now: number): number {
  const slot = slotOf(now);
  let n = 0;
  for (const s of Object.values(stations)) {
    const ratio = currentBusyRatio(s.id, Object.keys(s.chargers), statuses);
    if (ratio === null) continue;
    const a = decode(occ[s.id]);
    const count = Math.min(a[SLOTS + slot] + 1, MAX_N);
    const prev = a[slot] / 200;
    const next = prev + (ratio - prev) / count;
    a[slot] = Math.round(next * 200);
    a[SLOTS + slot] = count;
    occ[s.id] = encode(a);
    n++;
  }
  // 목록에서 빠진 충전소 정리
  for (const id of Object.keys(occ)) if (!stations[id]) delete occ[id];
  return n;
}

export interface Forecast {
  /** 해당 시간대 평균 사용률 (0~1). */
  busyRatio: number;
  samples: number;
}

/** [atMs] 시각의 과거 평균 사용률. 표본이 부족하면 null. */
export function forecast(occ: OccupancyMap, statId: string, atMs: number): Forecast | null {
  const s = occ[statId];
  if (!s) return null;
  const a = decode(s);
  const slot = slotOf(atMs);
  const samples = a[SLOTS + slot];
  if (samples < MIN_N_FOR_FORECAST) return null;
  return { busyRatio: a[slot] / 200, samples };
}

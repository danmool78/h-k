/**
 * 충전소 혼잡 예측 검증.
 *
 * 5분마다 각 충전소의 실제 사용률을 두 예측과 비교해 날짜별로 오차를 누적한다.
 *  - 이력 예측: 같은 요일·시간대 과거 평균 (앱이 도착까지 20분 넘게 남은 충전소에 쓰는 값)
 *  - 기준(지속) 예측: 1시간 전 상태가 그대로라고 보는 단순 예측
 * 반드시 이번 관측을 통계에 넣기 전에 호출해야 한다 (미래 정보 누설 방지).
 *
 * 1시간 전 상태는 충전소별 최근 12틱(5분 간격) 사용률 고리 버퍼로 보관한다.
 */
import { StationMap, StatusMap } from "./chargers";
import { OccupancyMap, currentBusyRatio, forecast } from "./occupancy";

export const LAG_TICKS = 12;
const NONE = 255;
const KEEP_DAYS = 30;
/** 이 사용률 이상이면 "만차(대기 발생)"로 본다 */
export const FULL = 0.999;
/** 예측이 이 이상이면 "만차 예상" */
const PREDICT_FULL = 0.8;

/** statId → base64(12바이트), 각 바이트 = 사용률×200 (없음 255), [0] 이 가장 오래된 값 */
export type LagMap = Record<string, string>;

export interface DayStats {
  n: number;
  histAbsErr: number;
  persistAbsErr: number;
  /** 만차 예측 혼동행렬: 이력 예측 기준 */
  fullTP: number;
  fullFP: number;
  fullFN: number;
  fullTN: number;
}

export type ValidationMap = Record<string, DayStats>;

const empty = (): DayStats => ({ n: 0, histAbsErr: 0, persistAbsErr: 0, fullTP: 0, fullFP: 0, fullFN: 0, fullTN: 0 });

function decodeLag(s: string | undefined): Uint8Array {
  if (!s) return new Uint8Array(LAG_TICKS).fill(NONE);
  return new Uint8Array(Buffer.from(s, "base64"));
}

/**
 * 이번 틱의 실제 사용률을 예측과 비교해 [val] 에 누적하고, 고리 버퍼 [lag] 를 한 칸 민다.
 * 반환: 비교한 충전소 수.
 */
export function validateAndShift(
  val: ValidationMap, lag: LagMap, occ: OccupancyMap, stations: StationMap, statuses: StatusMap, now: number, day: string,
): number {
  const d = (val[day] ??= empty());
  let n = 0;
  for (const s of Object.values(stations)) {
    const actual = currentBusyRatio(s.id, Object.keys(s.chargers), statuses);
    const buf = decodeLag(lag[s.id]);
    const hourAgo = buf[0];

    if (actual !== null) {
      const hist = forecast(occ, s.id, now);
      if (hist && hourAgo !== NONE) {
        d.n++;
        d.histAbsErr += Math.abs(hist.busyRatio - actual);
        d.persistAbsErr += Math.abs(hourAgo / 200 - actual);
        const isFull = actual >= FULL;
        const predFull = hist.busyRatio >= PREDICT_FULL;
        if (isFull && predFull) d.fullTP++;
        else if (!isFull && predFull) d.fullFP++;
        else if (isFull && !predFull) d.fullFN++;
        else d.fullTN++;
        n++;
      }
    }
    // 한 칸 밀고 현재 값 추가
    const next = new Uint8Array(LAG_TICKS);
    next.set(buf.subarray(1));
    next[LAG_TICKS - 1] = actual === null ? NONE : Math.round(actual * 200);
    lag[s.id] = Buffer.from(next).toString("base64");
  }
  for (const id of Object.keys(lag)) if (!stations[id]) delete lag[id];
  for (const k of Object.keys(val).sort().slice(0, -KEEP_DAYS)) delete val[k];
  return n;
}

export interface ValidationSummary {
  days: number;
  samples: number;
  /** 평균 절대 오차 (사용률 0~1) */
  histMae: number | null;
  persistMae: number | null;
  /** 이력 예측이 기준보다 오차를 몇 % 줄였는지 */
  improvementPct: number | null;
  fullPrecision: number | null;
  fullRecall: number | null;
}

export function summarize(val: ValidationMap): ValidationSummary {
  const t = Object.values(val).reduce((a, d) => {
    (Object.keys(a) as (keyof DayStats)[]).forEach((k) => (a[k] += d[k]));
    return a;
  }, empty());
  const histMae = t.n ? t.histAbsErr / t.n : null;
  const persistMae = t.n ? t.persistAbsErr / t.n : null;
  return {
    days: Object.keys(val).length,
    samples: t.n,
    histMae,
    persistMae,
    improvementPct: histMae !== null && persistMae ? (1 - histMae / persistMae) * 100 : null,
    fullPrecision: t.fullTP + t.fullFP ? t.fullTP / (t.fullTP + t.fullFP) : null,
    fullRecall: t.fullTP + t.fullFN ? t.fullTP / (t.fullTP + t.fullFN) : null,
  };
}

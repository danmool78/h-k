/**
 * 고속도로 휴게소 충전기 상태 이력 (실데이터 재현·검증용).
 *
 * 5분마다 휴게소 충전기 전체의 상태를 충전기당 2바이트로 기록한다.
 *   바이트 0: 상태 코드 (0 모름, 1 사용 가능, 2 충전 중, 3 고장, 4 운영 중지)
 *   바이트 1: 충전 중이면 충전 경과 분 (0~254), 아니면 255
 * 충전기 순서(index)는 목록이 바뀔 때만 새로 저장하고, 틱 파일 이름에 그 버전을 붙인다.
 *   history/index/<버전>.json          ["KR:ME1:01", ...]
 *   history/<yyyyMMdd>/<HHmm>-<버전>.bin  (KST, 5분 단위)
 */
import { createHash } from "node:crypto";
import { StationMap, StatusMap } from "./chargers";
import { isRestArea } from "./routing";
import { EvseState, EvseStatus, statusKey } from "./providers/types";

export const KEEP_DAYS = 60;

const CODE: Record<EvseStatus, number> = { UNKNOWN: 0, AVAILABLE: 1, CHARGING: 2, OUTOFORDER: 3, INOPERATIVE: 4 };
const STATUS: EvseStatus[] = ["UNKNOWN", "AVAILABLE", "CHARGING", "OUTOFORDER", "INOPERATIVE"];

/** 기록 대상: 휴게소 충전기 키 목록 (정렬해 순서 고정) */
export function restAreaOrder(stations: StationMap): string[] {
  const keys: string[] = [];
  for (const s of Object.values(stations)) {
    if (!isRestArea(s.name)) continue;
    for (const cid of Object.keys(s.chargers)) keys.push(statusKey(s.id, cid));
  }
  return keys.sort();
}

export const orderVersion = (order: string[]) => createHash("sha1").update(order.join("|")).digest("hex").slice(0, 10);

export function encodeTick(order: string[], statuses: StatusMap, now: number): Buffer {
  const buf = Buffer.alloc(order.length * 2);
  order.forEach((k, i) => {
    const st = statuses[k];
    buf[i * 2] = CODE[st?.status ?? "UNKNOWN"];
    buf[i * 2 + 1] = st?.status === "CHARGING" && st.sessionStartMs != null
      ? Math.min(254, Math.max(0, Math.round((now - st.sessionStartMs) / 60_000))) : 255;
  });
  return buf;
}

/** 틱 → 충전기별 상태. [at] 은 그 틱 시각 (충전 시작 시각 복원용) */
export function decodeTick(order: string[], buf: Buffer, at: number): StatusMap {
  const out: StatusMap = {};
  order.forEach((k, i) => {
    const status = STATUS[buf[i * 2]] ?? "UNKNOWN";
    const min = buf[i * 2 + 1];
    const state: EvseState = { status, updatedAt: at, sessionStartMs: status === "CHARGING" && min !== 255 ? at - min * 60_000 : null };
    out[k] = state;
  });
  return out;
}

/** KST 기준 (yyyyMMdd, HHmm 5분 내림) */
export function tickName(epochMs: number): { day: string; time: string } {
  const d = new Date(epochMs + 9 * 3600_000);
  const day = d.toISOString().slice(0, 10).replace(/-/g, "");
  const hh = String(d.getUTCHours()).padStart(2, "0");
  const mm = String(Math.floor(d.getUTCMinutes() / 5) * 5).padStart(2, "0");
  return { day, time: `${hh}${mm}` };
}

/** yyyyMMdd + HHmm (KST) → epoch ms */
export function tickEpoch(day: string, time: string): number {
  return Date.parse(`${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6, 8)}T${time.slice(0, 2)}:${time.slice(2, 4)}:00+09:00`);
}

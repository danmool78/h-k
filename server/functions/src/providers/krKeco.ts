/**
 * 한국: 한국환경공단 전기자동차 충전소 정보 (data.go.kr 15076352).
 * 원본 형식을 [types.ts] 공통 형식으로 바꾼다.
 */
import { requireApiKey } from "../config";
import { ChargerInfoItem, ZCODES, fetchChargerInfo, fetchChargerStatus } from "../evApi";
import { ChargerProvider, EvseState, EvseStatus, StationMap, StationSnapshot, StatusMap, StatusUpdate, statusKey } from "./types";

const PROVIDER = "kr-keco";
const PREFIX = "KR:";

/** 완속 충전기 타입. 02: AC완속, 07: AC3상, 08: DC콤보(완속) */
const SLOW_TYPES = new Set(["02", "07", "08"]);

/** 환경공단 충전기 타입 → 지원 커넥터 (현대·기아 국내 전기차는 CCS1 = DC콤보) */
export const CONNECTORS: Record<string, string[]> = {
  "01": ["CHADEMO"], "03": ["CHADEMO", "AC3"], "04": ["CCS1"], "05": ["CHADEMO", "CCS1"],
  "06": ["CHADEMO", "AC3", "CCS1"], "09": ["NACS"], "10": ["CCS1", "NACS"],
};

/** 환경공단 충전기 타입 → OCPI 커넥터 이름 (대표 규격) */
const STANDARD: Record<string, string> = {
  "01": "CHADEMO", "03": "CHADEMO", "04": "IEC_62196_T1_COMBO", "05": "IEC_62196_T1_COMBO",
  "06": "IEC_62196_T1_COMBO", "09": "TESLA_S", "10": "IEC_62196_T1_COMBO",
};

/** 상태 코드: 1 통신이상, 2 충전대기, 3 충전중, 4 운영중지, 5 점검중, 9 상태미확인 */
export function mapStatus(stat: string): EvseStatus {
  switch (stat) {
    case "2": return "AVAILABLE";
    case "3": return "CHARGING";
    case "4": return "INOPERATIVE";
    case "5": return "OUTOFORDER";
    default: return "UNKNOWN";
  }
}

/** "yyyyMMddHHmmss" (KST) → epoch ms. 빈 값이면 null. */
export function parseKst(s: string | undefined): number | null {
  if (!s || s.length < 12) return null;
  const iso = `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}T${s.slice(8, 10)}:${s.slice(10, 12)}:${s.slice(12, 14) || "00"}+09:00`;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

function state(stat: string, statUpdDt: string, nowTsdt: string): EvseState {
  const status = mapStatus(stat);
  return {
    status,
    updatedAt: parseKst(statUpdDt) ?? 0,
    sessionStartMs: status === "CHARGING" ? parseKst(nowTsdt) : null,
  };
}

/** getChargerInfo 결과 → 공통 형식 (누구나 쓸 수 있는 급속만, 삭제·좌표 없는 것 제외). */
export function convert(items: ChargerInfoItem[]): { stations: StationMap; statuses: StatusMap } {
  const stations: StationMap = {};
  const statuses: StatusMap = {};
  for (const it of items) {
    // 삭제됨, 완속, 이용자 제한(버스 전용·입주민 전용 등)은 제외
    if (it.delYn === "Y" || it.limitYn === "Y" || SLOW_TYPES.has(it.chgerType)) continue;
    const lat = Number(it.lat);
    const lng = Number(it.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat === 0) continue;
    const id = PREFIX + it.statId;
    const s = (stations[id] ??= {
      id, provider: PROVIDER, country: "KR",
      name: it.statNm, addr: it.addr, lat, lng, operator: it.busiNm,
      highway: it.trafficYn === "Y", region: it.zcode, chargers: {},
    });
    s.chargers[it.chgerId] = {
      standard: STANDARD[it.chgerType] ?? it.chgerType, connectors: CONNECTORS[it.chgerType] ?? [], kw: Number(it.output) || 50,
    };
    statuses[statusKey(id, it.chgerId)] = state(it.stat, it.statUpdDt, it.nowTsdt);
  }
  return { stations, statuses };
}

export const krKeco: ChargerProvider = {
  id: PROVIDER,
  country: "KR",
  label: "한국환경공단 (대한민국)",

  async fetchStations(previous: StationMap): Promise<StationSnapshot> {
    const key = await requireApiKey();
    const stations: StationMap = {};
    const statuses: StatusMap = {};
    const failedRegions: string[] = [];
    for (const z of ZCODES) {
      try {
        const c = convert(await fetchChargerInfo(z, key));
        Object.assign(stations, c.stations);
        Object.assign(statuses, c.statuses);
      } catch (e) {
        console.error(`kr-keco zcode ${z} 실패, 이전 데이터 유지`, e);
        failedRegions.push(z);
        for (const s of Object.values(previous)) if (s.region === z) stations[s.id] = s;
      }
    }
    if (failedRegions.length === ZCODES.length) throw new Error("kr-keco: 모든 지역 조회 실패");
    return { stations, statuses, failedRegions };
  },

  async fetchStatusChanges(): Promise<StatusUpdate[]> {
    const key = await requireApiKey();
    const items = await fetchChargerStatus(10, key);
    return items.map((it) => ({
      stationId: PREFIX + it.statId,
      evseId: it.chgerId,
      state: state(it.stat, it.statUpdDt, it.nowTsdt),
    }));
  },
};

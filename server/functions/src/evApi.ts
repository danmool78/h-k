/**
 * 한국환경공단 전기자동차 충전소 정보 API 클라이언트.
 * https://www.data.go.kr/data/15076352/openapi.do
 */

const BASE = "https://apis.data.go.kr/B552584/EvCharger";
const PAGE_SIZE = 9999;

/** 시도 코드 (zcode). */
export const ZCODES = [
  "11", "26", "27", "28", "29", "30", "31", "36", "41",
  "43", "44", "45", "46", "47", "48", "50", "51", "52",
];

/** 완속 충전기 타입. 02: AC완속, 07: AC3상, 08: DC콤보(완속) */
const SLOW_TYPES = new Set(["02", "07", "08"]);

export interface ChargerInfoItem {
  statNm: string;
  statId: string;
  chgerId: string;
  chgerType: string;
  addr: string;
  lat: string;
  lng: string;
  useTime: string;
  busiNm: string;
  output: string;
  stat: string;
  statUpdDt: string;
  lastTsdt: string;
  lastTedt: string;
  nowTsdt: string;
  kind: string;
  zcode: string;
  trafficYn: string;
  limitYn: string;
  delYn: string;
}

export interface ChargerStatusItem {
  statId: string;
  chgerId: string;
  stat: string;
  statUpdDt: string;
  lastTsdt: string;
  lastTedt: string;
  nowTsdt: string;
}

interface Page<T> {
  totalCount: number;
  items: T[];
}

async function fetchPage<T>(op: string, params: Record<string, string>, key: string): Promise<Page<T>> {
  const qs = new URLSearchParams({ ...params, dataType: "JSON" });
  // 공공데이터포털 키는 이미 URL 인코딩 불필요한 hex 형태
  const url = `${BASE}/${op}?serviceKey=${key}&${qs}`;
  let lastErr: unknown;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(90_000) });
      const text = await res.text();
      if (!res.ok) throw new Error(`${op} HTTP ${res.status}: ${text.slice(0, 200)}`);
      const body = JSON.parse(text);
      if (body.resultCode && body.resultCode !== "00") {
        throw new Error(`${op} resultCode ${body.resultCode}: ${body.resultMsg}`);
      }
      const raw = body.items?.item ?? [];
      return { totalCount: Number(body.totalCount ?? 0), items: Array.isArray(raw) ? raw : [raw] };
    } catch (e) {
      lastErr = e;
      await new Promise((r) => setTimeout(r, 2_000 * (attempt + 1)));
    }
  }
  throw lastErr;
}

async function fetchAll<T>(op: string, params: Record<string, string>, key: string): Promise<T[]> {
  const out: T[] = [];
  for (let page = 1; ; page++) {
    const p = await fetchPage<T>(op, { ...params, pageNo: String(page), numOfRows: String(PAGE_SIZE) }, key);
    out.push(...p.items);
    if (p.items.length === 0 || out.length >= p.totalCount) return out;
  }
}

export function isFastCharger(item: Pick<ChargerInfoItem, "chgerType">): boolean {
  return !SLOW_TYPES.has(item.chgerType);
}

export function fetchChargerInfo(zcode: string, key: string): Promise<ChargerInfoItem[]> {
  return fetchAll<ChargerInfoItem>("getChargerInfo", { zcode }, key);
}

/** 최근 [periodMin]분 사이 상태가 바뀐 충전기 (전국). */
export function fetchChargerStatus(periodMin: number, key: string): Promise<ChargerStatusItem[]> {
  return fetchAll<ChargerStatusItem>("getChargerStatus", { period: String(periodMin) }, key);
}

import assert from "node:assert/strict";
import { test } from "node:test";
import { StationMap, StatusMap } from "./chargers";
import { EvseStatus } from "./providers/types";
import { MAX_N, MIN_N_FOR_FORECAST, forecast, recordOccupancy, slotOf } from "./occupancy";

const stations: StationMap = {
  S1: {
    id: "S1", provider: "test", country: "KR", name: "A휴게소", addr: "", lat: 0, lng: 0, operator: "", highway: true, region: "41",
    chargers: { "01": { standard: "IEC_62196_T1_COMBO", kw: 200 }, "02": { standard: "IEC_62196_T1_COMBO", kw: 200 } },
  },
};

const st = (c: string): EvseStatus => (c === "2" ? "AVAILABLE" : c === "3" ? "CHARGING" : "OUTOFORDER");
const status = (a: string, b: string): StatusMap => ({
  "S1:01": { status: st(a), updatedAt: 0, sessionStartMs: null },
  "S1:02": { status: st(b), updatedAt: 0, sessionStartMs: null },
});

// 2026-10-30 (금) 18:10 KST
const FRI_18 = Date.parse("2026-10-30T18:10:00+09:00");

test("slotOf 는 KST 요일×24+시", () => {
  assert.equal(slotOf(FRI_18), 5 * 24 + 18);
  assert.equal(slotOf(Date.parse("2026-11-01T00:30:00+09:00")), 0); // 일요일 0시
});

test("표본이 쌓이면 평균 사용률을 예측", () => {
  const occ = {};
  for (let i = 0; i < MIN_N_FOR_FORECAST - 1; i++) recordOccupancy(occ, stations, status("3", "3"), FRI_18);
  assert.equal(forecast(occ, "S1", FRI_18), null);           // 표본 부족

  recordOccupancy(occ, stations, status("3", "2"), FRI_18);  // 50%
  const f = forecast(occ, "S1", FRI_18)!;
  assert.equal(f.samples, MIN_N_FOR_FORECAST);
  assert.ok(Math.abs(f.busyRatio - (5 * 1 + 0.5) / 6) < 0.01);

  assert.equal(forecast(occ, "S1", FRI_18 + 3600_000), null); // 다른 시간대는 비어 있음
});

test("표본 수는 상한에서 멈추고 고장 충전기는 제외", () => {
  const occ = {};
  for (let i = 0; i < MAX_N + 10; i++) recordOccupancy(occ, stations, status("2", "4"), FRI_18);
  const f = forecast(occ, "S1", FRI_18)!;
  assert.equal(f.samples, MAX_N);
  assert.equal(f.busyRatio, 0);                              // 운영 중 1대가 비어 있음
});

test("목록에서 사라진 충전소는 정리", () => {
  const occ: Record<string, string> = { GONE: "AA==" };
  recordOccupancy(occ, stations, status("3", "3"), FRI_18);
  assert.deepEqual(Object.keys(occ), ["S1"]);
});

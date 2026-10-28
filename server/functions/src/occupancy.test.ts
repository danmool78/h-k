import assert from "node:assert/strict";
import { test } from "node:test";
import { StationMap, StatusMap } from "./chargers";
import { MAX_N, MIN_N_FOR_FORECAST, forecast, recordOccupancy, slotOf } from "./occupancy";

const stations: StationMap = {
  S1: {
    id: "S1", name: "A휴게소", addr: "", lat: 0, lng: 0, operator: "", highway: true, zcode: "41",
    chargers: { "01": { type: "04", kw: 200 }, "02": { type: "04", kw: 200 } },
  },
};

const status = (a: string, b: string): StatusMap => ({
  "S1:01": { stat: a, updDt: "", nowTsdt: "", lastTsdt: "", lastTedt: "" },
  "S1:02": { stat: b, updDt: "", nowTsdt: "", lastTsdt: "", lastTedt: "" },
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

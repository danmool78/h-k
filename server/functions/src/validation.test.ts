import assert from "node:assert/strict";
import { test } from "node:test";
import { StationMap, StatusMap } from "./chargers";
import { MIN_N_FOR_FORECAST, recordOccupancy } from "./occupancy";
import { LAG_TICKS, LagMap, ValidationMap, summarize, validateAndShift } from "./validation";

const stations: StationMap = {
  S1: {
    id: "S1", name: "A", addr: "", lat: 0, lng: 0, operator: "", highway: true, zcode: "41",
    chargers: { "01": { type: "04", kw: 200 }, "02": { type: "04", kw: 200 } },
  },
};
const status = (a: string, b: string): StatusMap => ({
  "S1:01": { stat: a, updDt: "", nowTsdt: "", lastTsdt: "", lastTedt: "" },
  "S1:02": { stat: b, updDt: "", nowTsdt: "", lastTsdt: "", lastTedt: "" },
});
const T = Date.parse("2026-10-30T18:10:00+09:00");

test("이력·1시간 전 값이 생기기 전에는 비교하지 않음", () => {
  const val: ValidationMap = {};
  const lag: LagMap = {};
  assert.equal(validateAndShift(val, lag, {}, stations, status("3", "3"), T, "20261030"), 0);
});

test("1시간 전 값과 이력이 있으면 오차를 누적하고, 이력이 맞으면 기준보다 낫다", () => {
  const occ = {};
  // 이 시간대는 늘 만차였다는 이력
  for (let i = 0; i < MIN_N_FOR_FORECAST + 2; i++) recordOccupancy(occ, stations, status("3", "3"), T);
  const val: ValidationMap = {};
  const lag: LagMap = {};
  // 1시간 전부터는 비어 있었다 (지속 예측은 0, 이력은 1)
  for (let i = 0; i < LAG_TICKS; i++) validateAndShift(val, lag, occ, stations, status("2", "2"), T, "20261030");
  // 지금 만차 → 이력 예측이 맞고 지속 예측이 틀림
  const n = validateAndShift(val, lag, occ, stations, status("3", "3"), T, "20261030");
  assert.equal(n, 1);
  const s = summarize({ x: { ...val["20261030"], n: 1, histAbsErr: 0, persistAbsErr: 1, fullTP: 1, fullFP: 0, fullFN: 0, fullTN: 0 } });
  assert.equal(s.improvementPct, 100);
  assert.equal(s.fullPrecision, 1);
});

test("30일보다 오래된 날은 지운다", () => {
  const val: ValidationMap = {};
  for (let d = 1; d <= 35; d++) val[`202610${String(d).padStart(2, "0")}`] = { n: 1, histAbsErr: 0, persistAbsErr: 0, fullTP: 0, fullFP: 0, fullFN: 0, fullTN: 1 };
  validateAndShift(val, {}, {}, stations, status("2", "2"), T, "20261035");
  assert.equal(Object.keys(val).length, 30);
});

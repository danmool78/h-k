import assert from "node:assert/strict";
import { test } from "node:test";
import { PointWeather, classify, summarizeRoute } from "./weather";

test("WMO 코드·강수량으로 맑음/비/눈 판정", () => {
  assert.equal(classify(0, 0, 0), "CLEAR");
  assert.equal(classify(3, 0.1, 0), "CLEAR");
  assert.equal(classify(61, 0, 0), "RAIN");
  assert.equal(classify(2, 1.2, 0), "RAIN");
  assert.equal(classify(73, 0, 0), "SNOW");
  assert.equal(classify(2, 0.5, 0.3), "SNOW");
  assert.equal(classify(95, 0, 0), "RAIN");
});

const w = (tempC: number, kind: PointWeather["kind"]): PointWeather =>
  ({ lat: 0, lng: 0, at: 0, tempC, precipMm: 0, snowCm: 0, weatherCode: 0, kind });

test("경로 대표값: 기온 평균, 2곳 이상이어야 비/눈", () => {
  assert.deepEqual(summarizeRoute([w(10, "CLEAR"), w(6, "RAIN"), w(2, "CLEAR")]), { tempC: 6, kind: "CLEAR" });
  assert.deepEqual(summarizeRoute([w(4, "RAIN"), w(2, "SNOW"), w(0, "RAIN")]), { tempC: 2, kind: "RAIN" });
  assert.deepEqual(summarizeRoute([w(0, "SNOW"), w(-2, "SNOW"), w(1, "RAIN")]), { tempC: -0.3, kind: "SNOW" });
});

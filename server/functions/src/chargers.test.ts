import assert from "node:assert/strict";
import { test } from "node:test";
import { applyStatus, buildStations, nearby, parseKst, statusKey } from "./chargers";
import { ChargerInfoItem } from "./evApi";

const base: ChargerInfoItem = {
  statNm: "중문 관광단지", statId: "ME174050", chgerId: "01", chgerType: "06",
  addr: "제주 서귀포시", lat: "33.2461812", lng: "126.4143365", useTime: "24시간",
  busiNm: "기후에너지환경부", output: "100", stat: "3", statUpdDt: "20261028110000",
  lastTsdt: "", lastTedt: "", nowTsdt: "20261028110000", kind: "D0", zcode: "50",
  trafficYn: "N", limitYn: "N", delYn: "N",
};

test("완속·삭제 충전기는 제외", () => {
  const { stations, statuses } = buildStations([
    base,
    { ...base, chgerId: "02", chgerType: "02" },
    { ...base, chgerId: "03", delYn: "Y" },
  ]);
  assert.deepEqual(Object.keys(stations["ME174050"].chargers), ["01"]);
  assert.equal(Object.keys(statuses).length, 1);
});

test("상태 변경분은 더 최신일 때만 반영", () => {
  const { stations, statuses } = buildStations([base]);
  const upd = { statId: "ME174050", chgerId: "01", lastTsdt: "", lastTedt: "", nowTsdt: "" };
  assert.equal(applyStatus(statuses, stations, [{ ...upd, stat: "2", statUpdDt: "20261028100000" }]), 0);
  assert.equal(applyStatus(statuses, stations, [{ ...upd, stat: "2", statUpdDt: "20261028113000" }]), 1);
  assert.equal(statuses[statusKey("ME174050", "01")].stat, "2");
  // 모르는 충전기는 무시
  assert.equal(applyStatus(statuses, stations, [{ ...upd, statId: "X", stat: "2", statUpdDt: "20991231000000" }]), 0);
});

test("빈 충전기가 없으면 충전 경과시간으로 대기 추정", () => {
  const { stations, statuses } = buildStations([base]);
  const now = parseKst("20261028112000")!;   // 충전 시작 20분 경과, 100kW 평균 32분
  const [v] = nearby(stations, statuses, 33.2461812, 126.4143365, 1, 10, now);
  assert.equal(v.available, 0);
  assert.equal(v.charging, 1);
  assert.equal(v.chargers[0].chargingMin, 20);
  assert.equal(v.waitMin, 12);
});

test("빈 충전기가 있으면 대기 0, 반경 밖은 제외", () => {
  const { stations, statuses } = buildStations([base, { ...base, chgerId: "02", stat: "2", nowTsdt: "" }]);
  const now = Date.now();
  assert.equal(nearby(stations, statuses, 33.2461812, 126.4143365, 1, 10, now)[0].waitMin, 0);
  assert.equal(nearby(stations, statuses, 37.5, 127.0, 5, 10, now).length, 0);
});

test("parseKst 는 KST 기준", () => {
  assert.equal(new Date(parseKst("20261028090000")!).toISOString(), "2026-10-28T00:00:00.000Z");
  assert.equal(parseKst(""), null);
});

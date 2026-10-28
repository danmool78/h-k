import assert from "node:assert/strict";
import { test } from "node:test";
import { applyStatus, migrateStationKeys, nearby, upgradeLegacy } from "./chargers";
import { ChargerInfoItem } from "./evApi";
import { convert, mapStatus, parseKst } from "./providers/krKeco";
import { statusKey } from "./providers/types";

const base: ChargerInfoItem = {
  statNm: "중문 관광단지", statId: "ME174050", chgerId: "01", chgerType: "06",
  addr: "제주 서귀포시", lat: "33.2461812", lng: "126.4143365", useTime: "24시간",
  busiNm: "기후에너지환경부", output: "100", stat: "3", statUpdDt: "20261028110000",
  lastTsdt: "", lastTedt: "", nowTsdt: "20261028110000", kind: "D0", zcode: "50",
  trafficYn: "N", limitYn: "N", delYn: "N",
};
const ID = "KR:ME174050";

test("한국 출처: 완속·삭제 충전기 제외, ID 에 국가 접두사, 표준 형식", () => {
  const { stations, statuses } = convert([
    base,
    { ...base, chgerId: "02", chgerType: "02" },
    { ...base, chgerId: "03", delYn: "Y" },
  ]);
  assert.deepEqual(Object.keys(stations), [ID]);
  assert.equal(stations[ID].country, "KR");
  assert.equal(stations[ID].provider, "kr-keco");
  assert.deepEqual(Object.keys(stations[ID].chargers), ["01"]);
  assert.equal(stations[ID].chargers["01"].standard, "IEC_62196_T1_COMBO");
  assert.equal(statuses[statusKey(ID, "01")].status, "CHARGING");
  assert.equal(statuses[statusKey(ID, "01")].sessionStartMs, parseKst("20261028110000"));
});

test("한국 상태 코드 → 표준 상태", () => {
  assert.equal(mapStatus("2"), "AVAILABLE");
  assert.equal(mapStatus("3"), "CHARGING");
  assert.equal(mapStatus("4"), "INOPERATIVE");
  assert.equal(mapStatus("5"), "OUTOFORDER");
  assert.equal(mapStatus("1"), "UNKNOWN");
});

test("상태 변경분은 더 최신일 때만 반영, 모르는 충전기는 무시", () => {
  const { stations, statuses } = convert([base]);
  const upd = (status: "AVAILABLE", at: string, stationId = ID) =>
    ({ stationId, evseId: "01", state: { status, updatedAt: parseKst(at)!, sessionStartMs: null } });
  assert.equal(applyStatus(statuses, stations, [upd("AVAILABLE", "20261028100000")]), 0);
  assert.equal(applyStatus(statuses, stations, [upd("AVAILABLE", "20261028113000")]), 1);
  assert.equal(statuses[statusKey(ID, "01")].status, "AVAILABLE");
  assert.equal(applyStatus(statuses, stations, [upd("AVAILABLE", "20991231000000", "KR:X")]), 0);
});

test("빈 충전기가 없으면 충전 경과시간으로 대기 추정", () => {
  const { stations, statuses } = convert([base]);
  const now = parseKst("20261028112000")!;   // 충전 시작 20분 경과, 100kW 평균 32분
  const [v] = nearby(stations, statuses, 33.2461812, 126.4143365, 1, 10, now);
  assert.equal(v.available, 0);
  assert.equal(v.charging, 1);
  assert.equal(v.chargers[0].chargingMin, 20);
  assert.equal(v.waitMin, 12);
  assert.equal(v.country, "KR");
});

test("빈 충전기가 있으면 대기 0, 반경 밖은 제외", () => {
  const { stations, statuses } = convert([base, { ...base, chgerId: "02", stat: "2", nowTsdt: "" }]);
  const now = Date.now();
  assert.equal(nearby(stations, statuses, 33.2461812, 126.4143365, 1, 10, now)[0].waitMin, 0);
  assert.equal(nearby(stations, statuses, 37.5, 127.0, 5, 10, now).length, 0);
});

test("parseKst 는 KST 기준", () => {
  assert.equal(new Date(parseKst("20261028090000")!).toISOString(), "2026-10-28T00:00:00.000Z");
  assert.equal(parseKst(""), null);
});

test("이전 형식 목록·상태를 네트워크 없이 현재 형식으로 변환", () => {
  const stations = {
    ME1: { id: "ME1", name: "A", addr: "", lat: 33, lng: 126, operator: "환경부", highway: true, zcode: "50", chargers: { "01": { type: "04", kw: 100 } } },
  } as unknown as Parameters<typeof upgradeLegacy>[0];
  const statuses = {
    "ME1:01": { stat: "3", updDt: "20261028110000", nowTsdt: "20261028105000", lastTsdt: "", lastTedt: "" },
  } as unknown as Parameters<typeof upgradeLegacy>[1];
  assert.equal(upgradeLegacy(stations, statuses), true);
  assert.deepEqual(Object.keys(stations), ["KR:ME1"]);
  assert.equal(stations["KR:ME1"].provider, "kr-keco");
  assert.equal(stations["KR:ME1"].region, "50");
  assert.equal(statuses["KR:ME1:01"].status, "CHARGING");
  assert.equal(statuses["KR:ME1:01"].sessionStartMs, parseKst("20261028105000"));
  const [v] = nearby(stations, statuses, 33, 126, 1, 1, parseKst("20261028111000")!);
  assert.equal(v.charging, 1);
  assert.equal(v.outOfService, 0);
  assert.equal(upgradeLegacy(stations, statuses), false);
});

test("이전 형식 키(접두사 없음) → KR: 접두사로 이전", () => {
  const m: Record<string, number> = { ME1: 1, "KR:ME2": 2 };
  assert.equal(migrateStationKeys(m), true);
  assert.deepEqual(Object.keys(m).sort(), ["KR:ME1", "KR:ME2"]);
  assert.equal(migrateStationKeys(m), false);
});

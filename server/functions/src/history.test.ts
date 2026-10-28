import assert from "node:assert/strict";
import { test } from "node:test";
import { StationMap, StatusMap } from "./chargers";
import { decodeTick, encodeTick, orderVersion, restAreaOrder, tickEpoch, tickName } from "./history";

const stations: StationMap = {
  "KR:A": { id: "KR:A", provider: "t", country: "KR", name: "칠곡휴게소(부산방향)", addr: "", lat: 0, lng: 0, operator: "", highway: true, region: "",
    chargers: { "01": { standard: "x", kw: 200 }, "02": { standard: "x", kw: 200 } } },
  "KR:B": { id: "KR:B", provider: "t", country: "KR", name: "동네 충전소", addr: "", lat: 0, lng: 0, operator: "", highway: false, region: "",
    chargers: { "01": { standard: "x", kw: 100 } } },
};

test("휴게소 충전기만 기록 대상, 순서 고정", () => {
  const order = restAreaOrder(stations);
  assert.deepEqual(order, ["KR:A:01", "KR:A:02"]);
  assert.equal(orderVersion(order), orderVersion([...order]));
});

test("상태·충전 경과 시간 2바이트 인코딩 왕복", () => {
  const now = Date.parse("2026-10-31T14:00:00+09:00");
  const statuses: StatusMap = {
    "KR:A:01": { status: "CHARGING", updatedAt: 0, sessionStartMs: now - 23 * 60_000 },
    "KR:A:02": { status: "AVAILABLE", updatedAt: 0, sessionStartMs: null },
  };
  const order = restAreaOrder(stations);
  const buf = encodeTick(order, statuses, now);
  assert.equal(buf.length, 4);
  const back = decodeTick(order, buf, now);
  assert.equal(back["KR:A:01"].status, "CHARGING");
  assert.equal(back["KR:A:01"].sessionStartMs, now - 23 * 60_000);
  assert.equal(back["KR:A:02"].status, "AVAILABLE");
});

test("틱 이름은 KST 5분 단위", () => {
  const t = Date.parse("2026-10-31T23:58:30+09:00");
  assert.deepEqual(tickName(t), { day: "20261031", time: "2355" });
  assert.equal(tickEpoch("20261031", "2355"), Date.parse("2026-10-31T23:55:00+09:00"));
});

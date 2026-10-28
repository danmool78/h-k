import assert from "node:assert/strict";
import { test } from "node:test";
import { StationMap } from "./chargers";
import { detourMin, pickCandidates, pointAtKm, simplify, stationsAlongRoute, withCumulative } from "./routing";

// 위도 0도에서 경도 방향 직선 100km 정도의 가짜 경로 (0.009° ≈ 1km)
const coords: [number, number][] = Array.from({ length: 101 }, (_, i) => [0, i * 0.009]);
const route = withCumulative(coords, 100, 60);

const st = (id: string, lat: number, lng: number, rest: boolean, kw = 100): StationMap[string] => ({
  id, provider: "t", country: "KR", name: rest ? `${id}휴게소` : id, addr: "", lat, lng, operator: "", highway: rest, region: "",
  chargers: { "1": { standard: "x", kw }, "2": { standard: "x", kw } },
});

test("누적 거리와 경로 위 지점", () => {
  assert.ok(Math.abs(route.cumKm[100] - 100) < 0.5);
  const p = pointAtKm(route, 50);
  assert.ok(Math.abs(p.lng - 0.45) < 0.01);
  assert.ok(simplify(route, 10).length <= 12);
});

test("경로 옆 충전소만 — 휴게소 0.5km, 일반 2km, 출발·도착 근처 제외", () => {
  const stations: StationMap = {
    A: st("A", -0.003, 0.45, true),       // 진행 방향(동쪽) 오른쪽(남쪽) 0.3km 휴게소 → 포함
    B: st("B", -0.010, 0.45, true),       // 1.1km 떨어진 휴게소 → 제외
    F: st("F", 0.003, 0.50, true),        // 왼쪽(반대 방향) 휴게소 → 제외
    C: st("C", 0.015, 0.54, false),       // 약 1.7km 옆 일반 → 포함
    D: st("D", 0.030, 0.63, false),       // 약 3.3km 옆 → 제외
    E: st("E", 0.0, 0.045, false),        // 5km 지점 (출발 근처) → 제외
  };
  const along = stationsAlongRoute(route, stations, 15);
  assert.deepEqual(along.map((a) => a.stationId), ["A", "C"]);
  assert.ok(Math.abs(along[0].atKm - 50) <= 1);
});

test("구간마다 휴게소·고출력 우선으로 추림", () => {
  const stations: StationMap = {
    A: st("A", 0.001, 0.36, false, 50),
    B: st("B", -0.001, 0.37, true, 100),
    C: st("C", 0.001, 0.38, false, 200),
  };
  const along = stationsAlongRoute(route, stations, 15);
  const picked = pickCandidates(along, stations, 20, 2);
  assert.deepEqual(picked.map((p) => p.stationId).sort(), ["B", "C"]);
});

test("우회 시간: 휴게소 1분, 일반은 2km 벗어나면 약 9분", () => {
  assert.equal(detourMin(0.3, true), 1);
  assert.equal(detourMin(0), 1);
  assert.ok(Math.abs(detourMin(2) - 9) < 0.01);
});

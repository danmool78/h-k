// 실데이터 재현: 기록된 휴게소 충전기 상태로 "내비 방식" vs "안기다려" 를 같은 경로에서 비교한다.
// 계획은 웹 데모와 같은 몬테카를로(../demo/model.js 의 planPerFirstStop: 최대 3곳·충전량·식사)를 쓰고,
// 대기는 기록된 실제 상태로 판정한다. 주행 기록은 없으므로 주행 시간은 두 방식 모두 내비 예상 시간으로 같게 둔다
// → 두 방식의 차이와 예측 오차는 충전소 대기 판단에서만 나온다.
import { MEAL_MIN, MonteCarlo, mealWindow, score, tripContext } from "../demo/model.js";

export const API = "https://asia-northeast3-h-y-server.cloudfunctions.net/api";
const post = async (p, b) => {
  const r = await fetch(API + p, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(b) });
  if (!r.ok) throw new Error(`${p} ${r.status}`);
  return r.json();
};

/** 진입·결제 등 정차마다 드는 고정 시간(분) */
const OVERHEAD_MIN = 3;

/** 기록 틱 목록에서 [t] 이전 가장 최근 틱의 상태 (없으면 첫 틱) */
function tickAt(series, t) {
  let best = series[0];
  for (const tk of series) if (tk.at <= t) best = tk; else break;
  return best;
}

/** 실제 대기(분): 도착 시각 이후 처음 빈 충전기가 생기는 기록 시각까지. 기록이 끝나면 마지막 상태의 추정 대기 */
function actualWait(series, id, arriveAt) {
  for (const tk of series) {
    if (tk.at + 5 * 60_000 <= arriveAt) continue;
    const s = tk.stations[id];
    if (!s || s.inService === 0) continue;
    if (s.available > 0) return Math.max(0, (tk.at - arriveAt) / 60_000);
  }
  const last = series.at(-1)?.stations[id];
  return last?.waitMin ?? 15;
}

/**
 * [snap] 상태로 본 도착 시 예상 대기. 곧 도착하면 지금 상태 그대로,
 * 멀면 빈 충전기 비율이 낮을수록 그 사이 찰 수 있다고 본다.
 */
function predictedWait(snap, id, etaMin) {
  const s = snap?.stations[id];
  if (!s || s.inService === 0) return 60;
  if (s.available > 0) return etaMin <= 20 ? 0 : 3 * (1 - s.available / s.inService);
  return Math.max(s.waitMin ?? 15, 2);
}

/**
 * 한 출발 시각에 대해 두 방식을 재현.
 * @returns { route, cands, series, departAt, navi, ours, lastTick }
 *   각 방식: { name, stops[], stop, legs: {driveMin, detourMin, waitMin, chargeMin, mealMin}, totalMin, predictedMin, segs[], events[] }
 */
export async function replay({ origin, dest, day, departTime, soc, arrivalReserve, learnedTrips = 0, samples = 1000 }) {
  const rc = await post("/route/chargers", { origin, dest });
  const route = rc.route;
  const cands = rc.chargers.filter((c) => c.restArea && c.total - c.outOfService > 0);
  if (!cands.length) throw new Error("경로에 휴게소 충전소가 없습니다");
  const byId = new Map(cands.map((c) => [c.id, c]));
  const departAt = Date.parse(`${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6, 8)}T${departTime.slice(0, 2)}:${departTime.slice(2)}:00+09:00`);
  const endAt = departAt + (route.durationMin + 120) * 60_000;
  const kst = (ms) => new Date(ms + 9 * 3600_000).toISOString().slice(11, 16).replace(":", "");
  const hist = await post("/history/series", { day, from: departTime, to: kst(Math.min(endAt, departAt + 23.9 * 3600_000)), stationIds: cands.slice(0, 80).map((c) => c.id) });
  const series = hist.ticks;
  if (!series.length) throw new Error("이 시각의 기록이 없습니다");

  const ctx = tripContext(route.distanceKm, departAt, origin.lat, origin.lng, "CLEAR", "NORMAL");
  const env = { context: ctx, tempC: 12, occupants: 1, hvacOn: true, cabinSetC: 22, tireKpa: 250, ascentPerKm: 3 };
  const totalKm = route.distanceKm, totalMin = route.durationMin, soc0 = soc / 100;
  const minAt = (km) => totalMin * km / totalKm;

  /**
   * [fromKm] 지점부터 남은 구간의 계획들 (빠른 순). 웹 데모 recommend() 와 같은 규칙:
   * 충전 없이도 충분하면 식사 시간이 걸릴 때만 밥 먹을 휴게소 한 곳, 아니면 직행.
   */
  function planFrom(fromKm, socNow, waitOf, meal, seed) {
    const mc = new MonteCarlo(env, samples, seed, learnedTrips);
    const remKm = totalKm - fromKm, remMin = minAt(remKm);
    const waits = new Map();
    const chargers = cands.filter((c) => c.atKm > fromKm + 2).map((c) => {
      const atKm = c.atKm - fromKm;
      waits.set(c.id, waitOf(c, minAt(atKm)));
      return {
        id: c.id, atKm, powerKw: Math.min(Math.max(c.maxKw, 30), 350), waitMeanMin: Math.max(waits.get(c.id), 0.5),
        detourMin: c.detourMin, detourSigma: 0.2, mealOk: c.name.includes("휴게소"),
      };
    });
    const toPlan = (p) => ({
      p, mealSeparateMin: p.mealSeparateMin ?? 0, destSoc: p.destSocP50,
      stops: p.stops.map((s) => ({
        c: byId.get(s.charger), atKm: s.atKm + fromKm, socAt: s.socAtP50, toSoc: s.chargeToSoc,
        chargeMin: s.chargeMinutesP50, meal: s.meal, predWait: waits.get(s.charger),
      })),
    });
    const direct = mc.arrival(socNow, remKm, remMin);
    if (direct.socP10 >= arrivalReserve && mc.arrivalShare(socNow, remKm, remMin, arrivalReserve) >= 0.95) {
      const ms = meal ? mc.planPerFirstStop(socNow, remKm, remMin, chargers.filter((c) => c.mealOk), arrivalReserve, 0.95, meal, 1).filter((p) => p.stops[0]?.meal) : [];
      if (ms.length) return ms.sort((a, b) => score(a) - score(b)).map(toPlan);
      return [{ p: { minutesP50: direct.minutesP50 }, mealSeparateMin: 0, destSoc: direct.socP50, stops: [] }];
    }
    return mc.planPerFirstStop(socNow, remKm, remMin, chargers, arrivalReserve, 0.95, meal).sort((a, b) => score(a) - score(b)).map(toPlan);
  }

  /** 정차 한 곳의 체류: 우회·결제 + 대기 + 충전 (식사 정차면 대기+충전이 밥 먹는 시간보다 짧아도 밥 먹는 시간만큼) */
  const dwellOf = (s, wait) => {
    const active = wait + s.chargeMin;
    return { detour: s.c.detourMin + OVERHEAD_MIN, wait, charge: s.chargeMin, meal: s.meal ? Math.max(MEAL_MIN - active, 0) : 0 };
  };

  /** 출발 때 이 방식이 예상한 총 소요 (주행 + 정차마다 자기가 예상한 대기 + 별도 식사) */
  const predictedOf = (plan) => totalMin + plan.mealSeparateMin +
    plan.stops.reduce((a, s) => { const d = dwellOf(s, s.predWait); return a + d.detour + d.wait + d.charge + d.meal; }, 0);

  /**
   * 계획을 기록된 실제 상태로 재생. [onCheck] 가 있으면(안기다려) 5분마다 다음 정차지를 확인하고 바꿀 수 있다.
   * segs: 애니메이션용 구간 { t0, t1, pos0, pos1, soc0, soc1, label }
   */
  function play(name, first, onCheck) {
    if (!first) return { name, infeasible: true, events: [{ t: 0, text: "도착 최소 배터리를 지키는 계획이 없습니다" }], segs: [], stops: [] };
    const events = [{ t: 0, kind: "plan", text: first.stops.length ? `계획: ${first.stops.map((s) => `${s.c.name} ${Math.round(s.toSoc * 100)}%${s.meal ? "(식사)" : ""}`).join(" → ")}` : "충전 없이 직행 계획" }];
    const segs = [], done = [], legs = { driveMin: 0, detourMin: 0, waitMin: 0, chargeMin: 0, mealMin: 0 };
    let plan = first, stops = first.stops.slice(), t = 0, pos = 0, socNow = soc0, mealDone = false;
    const seg = (dt, dPos, socEnd, label) => {
      if (dt <= 0) return;
      segs.push({ t0: t, t1: t + dt, pos0: pos / totalKm, pos1: (pos + dPos) / totalKm, soc0: socNow, soc1: socEnd, label });
      t += dt; pos += dPos; socNow = socEnd;
    };
    // [toKm] 까지 달리며 5분마다 확인. 계획이 바뀌면 true
    const driveTo = (toKm, socEnd) => {
      while (pos < toKm - 1e-9) {
        const left = minAt(toKm - pos), next = onCheck ? (Math.floor(t / 5 + 1e-9) + 1) * 5 : Infinity;
        if (t + left <= next) { legs.driveMin += left; seg(left, toKm - pos, socEnd, "주행"); return false; }
        const dt = next - t, dPos = totalKm * dt / totalMin;
        legs.driveMin += dt;
        seg(dt, dPos, socNow + (socEnd - socNow) * dPos / (toKm - pos), "주행");
        const alt = onCheck(t, pos, socNow, stops, mealDone);
        if (alt) {
          events.push({ t, kind: "replan", text: alt.reason });
          plan = alt; stops = alt.stops.slice();
          return true;
        }
      }
      return false;
    };
    for (;;) {
      const s = stops[0];
      if (!s) { if (driveTo(totalKm, plan.destSoc)) continue; break; }
      if (driveTo(s.atKm, s.socAt)) continue;
      stops.shift();
      const arriveT = t, arriveAt = departAt + t * 60_000;
      const wait = actualWait(series, s.c.id, arriveAt);
      const snap = tickAt(series, arriveAt).stations[s.c.id];
      const d = dwellOf(s, wait);
      events.push({
        t, kind: wait > 3 ? "wait" : "ok",
        text: wait > 3 ? `${s.c.name} 도착 — 빈 충전기 ${snap?.available ?? 0}/${snap?.inService ?? "?"}, 실제 대기 ${Math.round(wait)}분`
          : `${s.c.name} 도착 — 바로 충전 (빈 충전기 ${snap?.available ?? "?"}/${snap?.inService ?? "?"})`,
      });
      seg(d.detour, 0, socNow, "진입");
      seg(d.wait, 0, socNow, wait > 3 ? "대기" : "진입");
      events.push({ t, kind: "charge", text: `${Math.round(d.charge)}분 충전 → ${Math.round(s.toSoc * 100)}%${s.meal ? " · 식사" : ""}` });
      seg(d.charge, 0, s.toSoc, "충전");
      seg(d.meal, 0, socNow, "식사");
      Object.assign(legs, { detourMin: legs.detourMin + d.detour, waitMin: legs.waitMin + d.wait, chargeMin: legs.chargeMin + d.charge, mealMin: legs.mealMin + d.meal });
      if (s.meal) mealDone = true;
      done.push({ ...s, arriveT, waitMin: wait });
    }
    // 정차지에서 먹지 않으면 따로 식사 (도착 직전 근처 식당 가정)
    if (plan.mealSeparateMin && !mealDone) {
      legs.mealMin += plan.mealSeparateMin;
      seg(plan.mealSeparateMin, 0, socNow, "식사");
      events.push({ t, kind: "charge", text: `충전소와 따로 식사 ${plan.mealSeparateMin}분` });
    }
    events.push({ t, kind: "arrive", text: `도착 · 배터리 약 ${Math.round(socNow * 100)}%` });
    return { name, stops: done, stop: done[0]?.c ?? null, legs, events, segs, totalMin: t, predictedMin: predictedOf(first), destSoc: socNow };
  }

  const meal0 = mealWindow(departAt, totalMin);
  // 두 방식은 같은 계산(같은 난수)으로 계획하고, 대기를 아느냐만 다르다
  const SEED = 7;
  // 내비 방식: 빈자리를 모르고(대기 0으로 가정) 출력·거리로만 고름
  const navi = play("내비 방식", planFrom(0, soc0, () => 0, meal0, SEED)[0], null);

  // 안기다려: 출발 시점 실시간 상태로 고르고, 달리는 동안 5분마다 다음 정차지 확인
  const snap0 = tickAt(series, departAt);
  const ours = play("안기다려", planFrom(0, soc0, (c, eta) => predictedWait(snap0, c.id, eta), meal0, SEED)[0], (t, posKm, socNow, stops, mealDone) => {
    const next = stops[0];
    if (!next) return null;
    const snap = tickAt(series, departAt + t * 60_000);
    const w = predictedWait(snap, next.c.id, Math.min(minAt(next.atKm - posKm), 20));
    if (w < 10) return null;
    // 가던 곳이 막혔다 → 남은 구간으로 다시 계획
    const meal = mealDone ? null : mealWindow(departAt + t * 60_000, minAt(totalKm - posKm));
    const alt = planFrom(posKm, socNow, (c, eta) => predictedWait(snap, c.id, Math.min(eta, 20)), meal, SEED + Math.round(t));
    const best = alt[0];
    if (!best || best.stops[0]?.c.id === next.c.id) return null;
    const keep = alt.find((a) => a.stops[0]?.c.id === next.c.id);
    const saving = keep ? keep.p.minutesP50 - best.p.minutesP50 : 99;
    if (saving < 5) return null;
    // 같은 휴게소에 운영사가 다른 충전소가 따로 있는 경우가 많다
    const c = best.stops[0]?.c;
    const to = !c ? "다음 충전 없이 직행" : c.name === next.c.name ? `같은 휴게소의 다른 충전소(${c.operator ?? ""} ${c.total}대)` : c.name;
    return { ...best, reason: `${next.c.name} 만차 (대기 약 ${Math.round(w)}분 예상) → ${to}(으)로 바꾸면 약 ${Math.round(Math.min(saving, 60))}분 빠릅니다` };
  });

  return { route, cands, series, departAt, navi, ours, lastTick: series.at(-1).at };
}

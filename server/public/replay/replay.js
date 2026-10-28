// 실데이터 재현: 기록된 휴게소 충전기 상태로 "내비 방식" vs "안기다려" 를 같은 경로에서 비교한다.
// 계획은 웹 데모 모델(../demo/model.js)의 몬테카를로를 그대로 쓰고, 대기는 기록된 실제 상태로 판정한다.
import { MonteCarlo, score, tripContext } from "../demo/model.js";

export const API = "https://asia-northeast3-h-y-server.cloudfunctions.net/api";
const post = async (p, b) => {
  const r = await fetch(API + p, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(b) });
  if (!r.ok) throw new Error(`${p} ${r.status}`);
  return r.json();
};

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

/** 출발 시각의 상태로 본 도착 시 예상 대기 (앱의 expectedWaitMin 단순판: 이력 예측 없이 현재 상태) */
function predictedWait(snap, id, etaMin) {
  const s = snap?.stations[id];
  if (!s || s.inService === 0) return 60;
  if (s.available > 0) return etaMin <= 20 ? 0 : 3;
  return Math.max(s.waitMin ?? 15, 2);
}

/**
 * 한 출발 시각에 대해 두 방식을 재현.
 * @returns { route, cands, navi, ours } 각 방식: { stopId, events[], legs: {driveMin1, waitMin, chargeMin, driveMin2}, totalMin, socs }
 */
export async function replay({ origin, dest, day, departTime, soc, arrivalReserve, learnedTrips = 0, samples = 1000 }) {
  const rc = await post("/route/chargers", { origin, dest });
  const route = rc.route;
  const cands = rc.chargers.filter((c) => c.restArea && c.total - c.outOfService > 0);
  if (!cands.length) throw new Error("경로에 휴게소 충전소가 없습니다");
  const departAt = Date.parse(`${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6, 8)}T${departTime.slice(0, 2)}:${departTime.slice(2)}:00+09:00`);
  const endAt = departAt + (route.durationMin + 120) * 60_000;
  const kst = (ms) => new Date(ms + 9 * 3600_000).toISOString().slice(11, 16).replace(":", "");
  const hist = await post("/history/series", { day, from: departTime, to: kst(Math.min(endAt, departAt + 23.9 * 3600_000)), stationIds: cands.slice(0, 80).map((c) => c.id) });
  const series = hist.ticks;
  if (!series.length) throw new Error("이 시각의 기록이 없습니다");

  const ctx = tripContext(route.distanceKm, departAt, origin.lat, origin.lng, "CLEAR", "NORMAL");
  const env = { context: ctx, tempC: 12, occupants: 1, hvacOn: true, cabinSetC: 22, tireKpa: 250, ascentPerKm: 3 };
  const totalKm = route.distanceKm, totalMin = route.durationMin, soc0 = soc / 100;

  /** 남은 구간 기준으로 각 후보의 최선 계획 */
  function plans(fromKm, socNow, waitOf, seed) {
    const mc = new MonteCarlo(env, samples, seed, learnedTrips);
    const remKm = totalKm - fromKm, remMin = totalMin * remKm / totalKm;
    const out = [];
    for (const c of cands) {
      if (c.atKm <= fromKm + 2) continue;
      const atKm = c.atKm - fromKm;
      const etaMin = remMin * atKm / remKm;
      const w = waitOf(c, etaMin);
      const p = mc.bestForCharger(socNow, remKm, remMin, { id: c.id, atKm, powerKw: Math.min(Math.max(c.maxKw, 30), 350), waitMeanMin: w, detourMin: c.detourMin }, arrivalReserve);
      if (p) out.push({ c, p, wait: w });
    }
    return out.sort((a, b) => score(a.p) - score(b.p));
  }

  /** 정해진 정차 계획을 기록된 실제 상태로 재생 */
  function play(name, firstChoice, onCheck) {
    const events = [];
    let choice = firstChoice;
    if (!choice) return { name, infeasible: true, events: [{ t: 0, text: "도착 최소 배터리를 지키는 계획이 없습니다" }] };
    events.push({ t: 0, text: `${choice.c.name} 에서 ${Math.round(choice.p.chargeToSoc * 100)}%까지 충전 계획`, kind: "plan" });
    // 5분마다 확인 (안기다려만)
    if (onCheck) {
      for (let t = 5; t < totalMin * choice.c.atKm / totalKm; t += 5) {
        const next = onCheck(t, choice);
        if (next && next.c.id !== choice.c.id) {
          events.push({ t, text: next.reason, kind: "replan", from: choice.c.name, to: next.c.name });
          choice = next;
        }
      }
    }
    // 출발 시점에 이 방식이 예측한 총 소요 (내비 방식은 대기를 모르니 대기 없이 계산한 값)
    const predictedMin = firstChoice.p.minutesP50;
    const c = choice.c, p = choice.p;
    const driveMin1 = totalMin * c.atKm / totalKm;
    const arriveAt = departAt + driveMin1 * 60_000;
    const waitMin = actualWait(series, c.id, arriveAt);
    const snap = tickAt(series, arriveAt).stations[c.id];
    events.push({
      t: driveMin1, kind: waitMin > 3 ? "wait" : "ok",
      text: waitMin > 3 ? `${c.name} 도착 — 빈 충전기 ${snap?.available ?? 0}/${snap?.inService ?? "?"}, 실제 대기 ${Math.round(waitMin)}분`
        : `${c.name} 도착 — 바로 충전 (빈 충전기 ${snap?.available ?? "?"}/${snap?.inService ?? "?"})`,
    });
    const chargeMin = p.chargeMinutesP50;
    const driveMin2 = totalMin - driveMin1;
    const total = driveMin1 + c.detourMin + 3 + waitMin + chargeMin + driveMin2;
    events.push({ t: driveMin1 + waitMin, text: `${Math.round(chargeMin)}분 충전 → ${Math.round(p.chargeToSoc * 100)}%`, kind: "charge" });
    events.push({ t: total, text: `도착 · 배터리 약 ${Math.round(p.destSocP50 * 100)}%`, kind: "arrive" });
    return {
      name, stop: c, plan: p, events, totalMin: total, predictedMin,
      legs: { driveMin1, waitMin, chargeMin, driveMin2, detourMin: c.detourMin + 3 },
      socs: { start: soc0, atCharger: p.socAtChargerP50, after: p.chargeToSoc, dest: p.destSocP50 },
    };
  }

  // 내비 방식: 빈자리를 모르고(대기 0으로 가정) 출력·거리로만 고름
  const naviChoice = plans(0, soc0, () => 1, 7)[0];
  const navi = play("내비 방식", naviChoice, null);

  // 안기다려: 출발 시점 실시간 상태로 고르고, 달리는 동안 5분마다 가던 곳 확인
  const snap0 = tickAt(series, departAt);
  const oursChoice = plans(0, soc0, (c, eta) => predictedWait(snap0, c.id, eta), 11)[0];
  const ours = play("안기다려", oursChoice, (t, choice) => {
    const now = departAt + t * 60_000;
    const snap = tickAt(series, now);
    const posKm = totalKm * t / totalMin;
    const etaToTarget = totalMin * (choice.c.atKm - posKm) / totalKm;
    const w = predictedWait(snap, choice.c.id, Math.min(etaToTarget, 20));
    if (w < 10) return null;
    // 가던 곳이 막혔다 → 남은 구간으로 다시 계획
    const usedSoc = (soc0 - choice.p.socAtChargerP50) * posKm / choice.c.atKm;
    const alt = plans(posKm, soc0 - usedSoc, (c, eta) => predictedWait(snap, c.id, Math.min(eta, 20)), 13 + t);
    const keep = alt.find((a) => a.c.id === choice.c.id);
    const best = alt[0];
    if (!best || best.c.id === choice.c.id) return null;
    const saving = keep ? keep.p.minutesP50 - best.p.minutesP50 : 99;
    if (saving < 5) return null;
    // 계획 수치는 전체 경로 기준으로 다시 맞춘다
    const full = plans(0, soc0, (c, eta) => (c.id === best.c.id ? best.wait : 60), 17).find((a) => a.c.id === best.c.id) ?? best;
    return {
      ...full,
      reason: `${choice.c.name} 만차 (대기 약 ${Math.round(w)}분 예상) → ${best.c.name}(으)로 바꾸면 약 ${Math.round(Math.min(saving, 60))}분 빠릅니다`,
    };
  });

  return { route, cands, series, departAt, navi, ours, lastTick: series.at(-1).at };
}

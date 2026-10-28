// 안기다려 예측 모델 — 앱(app/src/main/java/com/sssoft/hk/model, trip) 의 자바스크립트 포팅 (웹 데모용).
// 학습 전 기본 프로필(사전값)로 계산한다. 모델을 바꾸면 이 파일도 같이 맞춰야 한다.

// ---- 프로필 사전값 (VehicleProfile / EnergyModel) ----
export const ENERGY_NAMES = ["공기저항", "굴러가는 저항", "공조", "공조 기본", "저온 손실", "가속 손실", "젖은 노면", "눈길", "밤 조명", "타이어 공기압"];
const PRIOR_MEAN = [95, 30, 100, 300, 1.5, 0.35, 4, 12, 60, 15];
const PRIOR_STD = [12, 5, 25, 100, 0.5, 0.15, 3, 8, 40, 10];
const PACE_STD = [0.10, 0.06, 0.06, 0.05, 0.06, 0.10, 0.05, 0.04, 0.04];
const PACE_NOISE = 0.06;
const ACCEL_MEAN = [0, 0.3, -1.0, 0, 0, 0, 0, -0.1, 0.2];
const ACCEL_STD = [0.30, 0.20, 0.20, 0.15, 0.15, 0.20, 0.15, 0.15, 0.20];
const ACCEL_NOISE = 0.25;
const DEFAULT_ACCEL = 25;
const PROFILE = { capacityKwh: 74, capacityStd: 3, curbMassKg: 1950, vehicleMaxKw: 200, chargeLogK: Math.log(0.9), chargeLogKStd: 0.15, reserveSoc: 0.10, tireNominalKpa: 250 };
const PASSENGER_KG = 70, LUGGAGE_KG = 30, REGEN = 0.6, SEGMENT_KM = 10;

// ---- 난수 (재현 가능한 시드) ----
function rng(seed) {
  let a = seed >>> 0;
  const uni = () => { a |= 0; a = (a + 0x6D2B79F5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  let spare = null;
  const gauss = () => {
    if (spare !== null) { const s = spare; spare = null; return s; }
    let u, v, s;
    do { u = uni() * 2 - 1; v = uni() * 2 - 1; s = u * u + v * v; } while (s >= 1 || s === 0);
    const m = Math.sqrt(-2 * Math.log(s) / s); spare = v * m; return u * m;
  };
  return { uni, gauss };
}

const dot = (a, b) => a.reduce((s, x, i) => s + x * b[i], 0);

// ---- 주행 조건 (TripContext) ----
export function solarElevationDeg(ms, lat, lng) {
  const days = ms / 86400000, doy = days % 365.2422;
  const decl = -23.44 * Math.cos(2 * Math.PI / 365 * (doy + 10)) * Math.PI / 180;
  const utcH = (ms % 86400000) / 3600000;
  const ha = ((utcH + lng / 15) - 12) * 15 * Math.PI / 180;
  const lr = lat * Math.PI / 180;
  return Math.asin(Math.sin(lr) * Math.sin(decl) + Math.cos(lr) * Math.cos(decl) * Math.cos(ha)) * 180 / Math.PI;
}

export function tripContext(plannedKm, departMs, lat, lng, weather, mode) {
  const kst = new Date(departMs + 9 * 3600000);
  const dow = kst.getUTCDay(), hour = kst.getUTCHours();
  const weekend = dow === 0 || dow === 6;
  const type = plannedKm >= 80 ? "LONG" : (!weekend && ((hour >= 6 && hour <= 9) || (hour >= 17 && hour <= 19))) ? "COMMUTE" : "SHORT";
  return { type, night: solarElevationDeg(departMs, lat, lng) < -6, weather, weekend, mode };
}
export const TYPE_LABEL = { LONG: "장거리", COMMUTE: "출퇴근", SHORT: "단거리" };

function habitFeatures(c) {
  return [1, +(c.type === "COMMUTE"), +(c.type === "LONG"), +c.night, +(c.weather === "RAIN"), +(c.weather === "SNOW"), +c.weekend, +(c.mode === "ECO"), +(c.mode === "SPORT")];
}
function paceLog(c) {
  const x = habitFeatures(c);
  return [0, Math.sqrt(x.reduce((s, xi, i) => s + xi * xi * PACE_STD[i] ** 2, 0) + PACE_NOISE ** 2)];
}
function accelWhPerKmTon(c) { return DEFAULT_ACCEL * Math.exp(dot(habitFeatures(c), ACCEL_MEAN)); }

// ---- 소모 모델 (EnergyModel) ----
function features(e) {
  const speed = e.hours > 0 ? e.distKm / e.hours : 0;
  const hvacH = e.hvacOn ? e.hours : 0;
  const dt = e.distKm * e.massKg / 1000;
  return [e.distKm * (speed / 100) ** 2, dt, hvacH * Math.abs(e.cabinSetC - e.tempC), hvacH, e.distKm * Math.max(0, 15 - e.tempC),
    e.accelWh, e.weather === "RAIN" ? dt : 0, e.weather === "SNOW" ? dt : 0, e.night ? e.hours : 0, dt * Math.min(Math.max(e.tireUnder, 0), 0.5)];
}
const climbWh = (e) => e.massKg * 9.81 * (e.ascentM - REGEN * e.descentM) / 3600;
const noiseStdWh = (p) => 0.05 * Math.max(p, 0) + 80;

// ---- 충전 곡선 (ChargeCurve) ----
const SHAPE = [[0, 1], [0.5, 1], [0.8, 0.55], [0.9, 0.3], [1, 0.12]];
function shape(s) {
  s = Math.min(Math.max(s, 0), 1);
  for (let i = 1; i < SHAPE.length; i++) if (s <= SHAPE[i][0]) {
    const [x0, y0] = SHAPE[i - 1], [x1, y1] = SHAPE[i];
    return y0 + (y1 - y0) * (s - x0) / (x1 - x0);
  }
  return SHAPE.at(-1)[1];
}
function chargeTable(chargerKw) {
  // 경유지로 정한 충전소는 차가 도착 전에 배터리를 데우므로 저온 감속 없음 (앱의 preconditioned = true)
  const peak = Math.min(chargerKw, PROFILE.vehicleMaxKw);
  const t = new Float64Array(201);
  for (let i = 1; i <= 200; i++) t[i] = t[i - 1] + 0.005 / Math.max(peak * shape((i - 0.5) / 200), 1) * 60;
  return t;
}
function lookup(t, from, to) {
  const at = (s) => { const x = Math.min(Math.max(s, 0), 1) * 200; const i = Math.min(Math.floor(x), 199); return t[i] + (t[i + 1] - t[i]) * (x - i); };
  return to <= from ? 0 : at(to) - at(from);
}

// ---- 대기 예측 (TripPlanner.expectedWaitMin) ----
export function expectedWaitMin(v, etaMin) {
  const now = v.waitMin;
  const hist = v.forecast ? 1 + 25 * v.forecast.busyRatio ** 2 : null;
  let w;
  if (etaMin <= 20) w = now ?? 15; else if (hist !== null) w = hist; else w = Math.max(now ?? 8, 2);
  return Math.max(w, 0.5);
}

const pct = (sorted, q) => { const pos = (sorted.length - 1) * q / 100, lo = Math.floor(pos), hi = Math.min(lo + 1, sorted.length - 1); return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo); };
export const score = (p) => 0.6 * p.minutesP50 + 0.4 * p.minutesP90;

// ---- 몬테카를로 (EvMonteCarlo) ----
export class MonteCarlo {
  constructor(env, samples = 1500, seed = 42) {
    this.env = env; this.n = samples;
    const r = rng(seed);
    const [pm, ps] = paceLog(env.context);
    this.theta = Array.from({ length: samples }, () => PRIOR_MEAN.map((m, j) => Math.max(0, m + PRIOR_STD[j] * r.gauss())));
    this.pace = Float64Array.from({ length: samples }, () => Math.exp(pm + r.gauss() * ps));
    this.accelMul = Float64Array.from({ length: samples }, () => Math.exp(r.gauss() * ACCEL_NOISE));
    this.noise1 = Float64Array.from({ length: samples }, () => r.gauss());
    this.noise2 = Float64Array.from({ length: samples }, () => r.gauss());
    this.cap = Float64Array.from({ length: samples }, () => PROFILE.capacityKwh + r.gauss() * PROFILE.capacityStd);
    this.k = Float64Array.from({ length: samples }, () => Math.exp(PROFILE.chargeLogK + r.gauss() * PROFILE.chargeLogKStd));
    this.waitZ = Float64Array.from({ length: samples }, () => r.gauss());
    this.overhead = Float64Array.from({ length: samples }, () => 3 * Math.exp(r.gauss() * 0.3));
    this.detourZ = Float64Array.from({ length: samples }, () => r.gauss());
    this.mass = PROFILE.curbMassKg + env.occupants * PASSENGER_KG + LUGGAGE_KG;
    this.tireUnder = env.tireKpa ? Math.min(Math.max((PROFILE.tireNominalKpa - env.tireKpa) / PROFILE.tireNominalKpa, 0), 0.5) : 0;
    this.accel = accelWhPerKmTon(env.context);
  }

  /** 시나리오 i: [kWh, 실제 주행 분] */
  leg(i, distKm, navMin, noise) {
    if (distKm <= 0) return [0, 0];
    const e = this.env, minutes = navMin * this.pace[i];
    const input = {
      distKm, hours: minutes / 60, tempC: e.tempC, massKg: this.mass, hvacOn: e.hvacOn, cabinSetC: e.cabinSetC,
      weather: e.context.weather, night: e.context.night, tireUnder: this.tireUnder,
      accelWh: this.accel * distKm * this.mass / 1000 * this.accelMul[i], ascentM: e.ascentPerKm * distKm, descentM: e.ascentPerKm * distKm,
    };
    const pred = dot(this.theta[i], features(input)) + climbWh(input);
    const nSeg = Math.max(distKm / SEGMENT_KM, 1);
    const wh = pred + noise[i] * noiseStdWh(pred / nSeg) * Math.sqrt(nSeg);
    return [Math.max(wh, 0) / 1000, minutes];
  }

  arrival(soc0, distKm, navMin) {
    const remain = new Float64Array(this.n), mins = new Float64Array(this.n);
    for (let i = 0; i < this.n; i++) {
      const [kwh, m] = this.leg(i, distKm, navMin, this.noise1);
      remain[i] = soc0 - kwh / this.cap[i]; mins[i] = m;
    }
    const p = remain.filter((x) => x >= PROFILE.reserveSoc).length / this.n;
    remain.sort(); mins.sort();
    return { probability: p, socP10: pct(remain, 10), socP50: pct(remain, 50), socP90: pct(remain, 90), minutesP50: pct(mins, 50), minutesP90: pct(mins, 90) };
  }

  /** 충전소 하나의 최선 충전 목표. 목표 확률 미달이면 null */
  bestForCharger(soc0, totalKm, totalMin, c, targetP = 0.95) {
    const d1 = Math.min(Math.max(c.atKm, 0), totalKm), nav1 = totalMin * d1 / totalKm;
    const table = chargeTable(c.powerKw);
    const n = this.n, socAt = new Float64Array(n), l1 = new Float64Array(n), l2k = new Float64Array(n), l2m = new Float64Array(n), fixed = new Float64Array(n);
    const wl = Math.log(Math.max(c.waitMeanMin, 0.5));
    for (let i = 0; i < n; i++) {
      const [k1, m1] = this.leg(i, d1, nav1, this.noise1);
      const [k2, m2] = this.leg(i, totalKm - d1, totalMin - nav1, this.noise2);
      socAt[i] = soc0 - k1 / this.cap[i]; l1[i] = m1; l2k[i] = k2; l2m[i] = m2;
      fixed[i] = Math.exp(wl + this.waitZ[i] * 0.6) + this.overhead[i] + c.detourMin * Math.exp(this.detourZ[i] * 0.4);
    }
    let best = null;
    const total = new Float64Array(n), chargeMin = new Float64Array(n);
    for (let step = 0; step <= 13; step++) {
      const tgt = 0.30 + step * 0.05;
      let ok = 0;
      for (let i = 0; i < n; i++) {
        const from = Math.max(socAt[i], 0), to = Math.max(tgt, from);
        chargeMin[i] = lookup(table, from, to) * this.cap[i] / this.k[i];
        total[i] = l1[i] + l2m[i] + fixed[i] + chargeMin[i];
        if (socAt[i] >= PROFILE.reserveSoc && to - l2k[i] / this.cap[i] >= PROFILE.reserveSoc) ok++;
      }
      const p = ok / n;
      if (p < targetP) continue;
      const ts = Float64Array.from(total).sort(), cs = Float64Array.from(chargeMin).sort();
      const plan = { charger: c.id, chargeToSoc: tgt, probability: p, minutesP50: pct(ts, 50), minutesP90: pct(ts, 90), chargeMinutesP50: pct(cs, 50) };
      if (!best || score(plan) < score(best)) best = plan;
    }
    return best;
  }
}

/** 경로 좌표에서 거리 비율 [r] (0~1) 지점. 좌표 간격이 고르지 않으므로 누적 거리로 찾는다. */
function pointAtRatio(path, r) {
  const d = (a, b) => Math.hypot((b[0] - a[0]) * 111, (b[1] - a[1]) * 111 * Math.cos(a[0] * Math.PI / 180));
  const cum = [0];
  for (let i = 1; i < path.length; i++) cum.push(cum[i - 1] + d(path[i - 1], path[i]));
  const target = cum.at(-1) * Math.min(Math.max(r, 0), 1);
  const i = Math.max(1, cum.findIndex((c) => c >= target));
  return path[i];
}

/**
 * 추천 (TripPlanner.recommend 의 웹 판). 도로 경로(서버 /route/chargers)를 따라 있는 충전소로 계산한다.
 * 반환: { route, direct, plan, options, baseline, savedMin, checked, env, weather, candidates }
 */
export async function recommend(api, inp) {
  const { origin, dest, departMs } = inp;
  const offsetMin = Math.max(0, (departMs - Date.now()) / 60000);

  const rc = await api.routeChargers(origin, dest, offsetMin);
  const route = rc.route;
  const totalKm = route.distanceKm, totalMin = route.durationMin;

  let tempC = inp.tempC, weather = inp.weather, forecast = false;
  if (inp.useForecast) {
    // 실제 경로 위 5개 지점의 "그 지점 도착 시각" 예보
    const pts = [0, 1, 2, 3, 4].map((i) => {
      const [lat, lng] = pointAtRatio(route.path, i / 4);
      return { lat, lng, etaMin: offsetMin + (i / 4) * totalMin };
    });
    try { const w = await api.routeWeather(pts); tempC = w.tempC; weather = w.kind; forecast = true; } catch (e) { console.warn(e); }
  }

  const context = tripContext(totalKm, departMs, origin.lat, origin.lng, weather, inp.mode);
  const env = { context, tempC, occupants: inp.occupants, hvacOn: inp.hvacOn, cabinSetC: inp.cabinSetC, tireKpa: inp.tireKpa, ascentPerKm: 3 };
  const mc = new MonteCarlo(env);
  const soc0 = inp.soc / 100;
  const direct = mc.arrival(soc0, totalKm, totalMin);

  const cands = rc.chargers.filter((v) => v.total - v.outOfService > 0);
  const base = { route, direct, env, weather: { tempC, kind: weather, forecast }, checked: cands.length, candidates: cands };
  if (direct.probability >= 0.95) return { ...base, plan: { kind: "none", probability: direct.probability }, options: [], baseline: null, savedMin: null };

  const options = [];
  for (const v of cands) {
    const waitMin = expectedWaitMin(v, v.etaMin);
    const c = { id: v.id, atKm: v.atKm, powerKw: Math.min(Math.max(v.maxKw, 30), 350), waitMeanMin: waitMin, detourMin: v.detourMin };
    const p = mc.bestForCharger(soc0, totalKm, totalMin, c);
    if (p) options.push({ station: v, atKm: v.atKm, waitMin, plan: p });
  }
  options.sort((a, b) => score(a.plan) - score(b.plan));
  const baseline = options.reduce((m, o) => (!m || o.station.maxKw > m.station.maxKw ? o : m), null);
  const best = options[0];
  const savedMin = best && baseline && baseline.station.id !== best.station.id ? baseline.plan.minutesP50 - best.plan.minutesP50 : null;
  return { ...base, plan: best ? { kind: "plan", ...best.plan } : { kind: "infeasible", probability: direct.probability }, options, baseline, savedMin };
}

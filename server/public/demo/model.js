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

// ---- 웹 데모용 가상 학습 (앱은 실제 주행 기록으로 학습) ----
// LearningSimulationTest 결과를 근사: 주행 n회 후 차량·습관 값의 불확실성 배율 (최소 0.3)
export function learnedScale(trips) { return Math.max(0.3, 1 / Math.sqrt(1 + trips / 2)); }

/** 학습도 (0~1). 불확실성이 처음보다 줄어든 정도. 앱의 VehicleProfile.learningProgress 와 같은 기준 */
export function learningProgress(trips) {
  const scale = learnedScale(trips);
  const vehicle = (1 - scale) / 0.7;
  const habit = trips / (trips + 8);
  const sessions = Math.floor(trips / 4);
  const charging = Math.min(1, sessions / (sessions + 3) / 0.85);
  const places = Math.min(1, Math.min(trips, 30) / 15);
  return { vehicle, habit, charging, places, overall: 0.45 * vehicle + 0.25 * habit + 0.2 * charging + 0.1 * places };
}

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

// ---- 여러 곳 충전 + 식사 (EvMonteCarlo.bestPlanPerFirstStop) ----
export const MAX_STOPS = 3;
const MIN_TARGET = 0.20, MAX_TARGET = 0.95, SCREEN_Z = 1.75, KEEP_PER_FIRST = 3, MAX_BUMPS = 8;
export const MEAL_MIN = 35, MEAL_SEPARATE_EXTRA = 5;
const ceil05 = (x) => Math.ceil(x / 0.05 - 1e-6) * 0.05;
const r2 = (x) => Math.round(x * 100) / 100;

/** 출발부터 [totalMin] 분 동안 걸리는 점심(11:30~13:30)·저녁(17:30~19:30). 2시간 미만이거나 출발 직후·도착 직전만 걸리면 없음 */
export function mealWindow(departMs, totalMin) {
  if (totalMin < 120) return null;
  const d = new Date(departMs), now = d.getHours() * 60 + d.getMinutes();
  for (const day of [0, 1]) for (const [label, from, to] of [["점심", 690, 810], ["저녁", 1050, 1170]]) {
    const a = Math.max(from + day * 1440 - now, 45), b = Math.min(to + day * 1440 - now, totalMin - 30);
    if (a < b) return { fromMin: a, toMin: b, label, minutes: MEAL_MIN };
  }
  return null;
}

// ---- 몬테카를로 (EvMonteCarlo) ----
export class MonteCarlo {
  constructor(env, samples = 1500, seed = 42, learnedTrips = 0) {
    this.env = env; this.n = samples;
    const r = rng(seed);
    const k = learnedScale(learnedTrips);
    const [pm, ps0] = paceLog(env.context);
    const ps = Math.sqrt(Math.max(ps0 ** 2 - PACE_NOISE ** 2, 0) * k * k + PACE_NOISE ** 2);
    this.theta = Array.from({ length: samples }, () => PRIOR_MEAN.map((m, j) => Math.max(0, m + PRIOR_STD[j] * k * r.gauss())));
    this.pace = Float64Array.from({ length: samples }, () => Math.exp(pm + r.gauss() * ps));
    this.accelMul = Float64Array.from({ length: samples }, () => Math.exp(r.gauss() * ACCEL_NOISE));
    this.noise1 = Float64Array.from({ length: samples }, () => r.gauss());
    this.noise2 = Float64Array.from({ length: samples }, () => r.gauss());
    this.cap = Float64Array.from({ length: samples }, () => PROFILE.capacityKwh + r.gauss() * PROFILE.capacityStd);
    const kStd = PROFILE.chargeLogKStd * Math.max(0.25, 1 / Math.sqrt(1 + learnedTrips / 8));
    this.k = Float64Array.from({ length: samples }, () => Math.exp(PROFILE.chargeLogK + r.gauss() * kStd));
    this.waitZ = Float64Array.from({ length: samples }, () => r.gauss());
    this.overhead = Float64Array.from({ length: samples }, () => 3 * Math.exp(r.gauss() * 0.3));
    this.detourZ = Float64Array.from({ length: samples }, () => r.gauss());
    this.mass = PROFILE.curbMassKg + env.occupants * PASSENGER_KG + LUGGAGE_KG;
    this.tireUnder = env.tireKpa ? Math.min(Math.max((PROFILE.tireNominalKpa - env.tireKpa) / PROFILE.tireNominalKpa, 0), 0.5) : 0;
    this.accel = accelWhPerKmTon(env.context);
  }

  /** 시나리오 i: [kWh, 실제 주행 분] */
  leg(i, distKm, navMin, noise, minSeg = 1) {
    if (distKm <= 0) return [0, 0];
    const e = this.env, minutes = navMin * this.pace[i];
    const input = {
      distKm, hours: minutes / 60, tempC: e.tempC, massKg: this.mass, hvacOn: e.hvacOn, cabinSetC: e.cabinSetC,
      weather: e.context.weather, night: e.context.night, tireUnder: this.tireUnder,
      accelWh: this.accel * distKm * this.mass / 1000 * this.accelMul[i], ascentM: e.ascentPerKm * distKm, descentM: e.ascentPerKm * distKm,
    };
    const pred = dot(this.theta[i], features(input)) + climbWh(input);
    // 경로를 잘게 나눠 더할 때는 minSeg 를 작게: 구간별 노이즈가 무작위 걸음처럼 쌓임
    const nSeg = Math.max(distKm / SEGMENT_KM, minSeg);
    const wh = pred + noise[i] * noiseStdWh(pred / nSeg) * Math.sqrt(nSeg);
    return [Math.max(wh, 0) / 1000, minutes];
  }

  /** 충전 없이 갈 때 도착 배터리가 [reserve] 이상일 확률 */
  arrivalShare(soc0, distKm, navMin, reserve) {
    let ok = 0;
    for (let i = 0; i < this.n; i++) if (soc0 - this.leg(i, distKm, navMin, this.noise1)[0] / this.cap[i] >= reserve) ok++;
    return ok / this.n;
  }

  arrival(soc0, distKm, navMin) {
    const remain = new Float64Array(this.n), mins = new Float64Array(this.n);
    for (let i = 0; i < this.n; i++) {
      const [kwh, m] = this.leg(i, distKm, navMin, this.noise1);
      remain[i] = soc0 - kwh / this.cap[i]; mins[i] = m;
    }
    const p = remain.filter((x) => x >= PROFILE.reserveSoc).length / this.n;
    // 시각화용 시뮬레이션 결과 (정렬 전 원본: 같은 i 가 같은 시나리오)
    const samples = { dest: Float64Array.from(remain), total: Float64Array.from(mins) };
    remain.sort(); mins.sort();
    return {
      probability: p, socP10: pct(remain, 10), socP50: pct(remain, 50), socP90: pct(remain, 90),
      minutesP10: pct(mins, 10), minutesP50: pct(mins, 50), minutesP90: pct(mins, 90), samples, n: this.n,
    };
  }

  /**
   * 첫 충전소마다, 목표 확률을 지키는 가장 빠른 계획 (그 뒤 충전소 [maxStops]−1 곳·충전량·식사 포함).
   * 경로 위 모든 지점까지 누적 소모를 시나리오마다 한 번 계산 → 평균·운 나쁜 쪽 소모로 조합을 거르고 → 남은 것만 정밀 계산.
   */
  planPerFirstStop(soc0, totalKm, totalMin, chargers, arrivalReserve = 0.30, targetP = 0.95, meal = null, maxStops = MAX_STOPS) {
    const n = this.n, r = rng(7);
    const cs = chargers.filter((c) => c.atKm > 0 && c.atKm < totalKm).sort((a, b) => a.atKm - b.atKm);
    const N = cs.length, dest = N + 1;
    if (!N) return [];
    const km = [0, ...cs.map((c) => c.atKm), totalKm], nav = km.map((k) => totalMin * k / totalKm);
    const kwh = [new Float64Array(n)], mins = [new Float64Array(n)];
    for (let p = 1; p <= dest; p++) {
      const noise = Float64Array.from({ length: n }, () => r.gauss());
      const K = new Float64Array(n), M = new Float64Array(n);
      for (let i = 0; i < n; i++) {
        const [k, m] = this.leg(i, km[p] - km[p - 1], nav[p] - nav[p - 1], noise, 0.01);
        K[i] = kwh[p - 1][i] + k; M[i] = mins[p - 1][i] + m;
      }
      kwh.push(K); mins.push(M);
    }
    const fixed = cs.map((c) => { const wl = Math.log(Math.max(c.waitMeanMin, 0.5)); return Float64Array.from({ length: n }, () => Math.exp(wl + r.gauss() * 0.6) + 3 * Math.exp(r.gauss() * 0.3)); });
    const detour = cs.map((c) => Float64Array.from({ length: n }, () => c.detourMin * Math.exp(r.gauss() * (c.detourSigma ?? 0.4))));
    const tables = cs.map((c) => chargeTable(c.powerKw));
    const avg = (a) => a.reduce((s, x) => s + x, 0) / a.length;
    const paceMean = avg(this.pace), capMean = avg(this.cap), kMean = avg(this.k);
    const stats = [];
    const use = (p) => {
      if (stats[p]) return stats[p];
      const m = new Float64Array(dest + 1), s = new Float64Array(dest + 1);
      for (let q = p + 1; q <= dest; q++) {
        let sum = 0, sq = 0;
        for (let i = 0; i < n; i++) { const u = (kwh[q][i] - kwh[p][i]) / this.cap[i]; sum += u; sq += u * u; }
        m[q] = sum / n; s[q] = Math.sqrt(Math.max(sq / n - m[q] ** 2, 0));
      }
      return (stats[p] = { m, s });
    };
    const meanUse = (p, q) => use(p).m[q], needUse = (p, q) => use(p).m[q] + SCREEN_Z * use(p).s[q];
    const meanDwell = (c, from, to, eat) => {
      const active = cs[c].waitMeanMin * Math.exp(0.18) + 3 + lookup(tables[c], Math.max(from, 0), to) * capMean / kMean;
      return cs[c].detourMin + (eat ? Math.max(active, meal.minutes) : active);
    };
    const separateMin = meal ? meal.minutes + MEAL_SEPARATE_EXTRA : 0;

    // 1) 거르기: 첫 충전소별로 유망한 계획 몇 개
    const keep = new Map(), drive = nav[dest] * paceMean, pts = [], tgts = [];
    const worst = (f) => { const l = keep.get(f); return l && l.length >= KEEP_PER_FIRST ? Math.max(...l.map((d) => d.est)) : Infinity; };
    const record = (depth, mealAt, dwell) => {
      const est = drive + dwell + (meal && mealAt < 0 ? separateMin : 0);
      const l = keep.get(pts[0]) ?? []; keep.set(pts[0], l);
      if (l.length >= KEEP_PER_FIRST) {
        const w = l.reduce((a, b) => (b.est > a.est ? b : a));
        if (w.est <= est) return;
        l.splice(l.indexOf(w), 1);
      }
      l.push({ points: pts.slice(0, depth), targets: tgts.slice(0, depth), mealAt, est });
    };
    const extend = (prev, soc, depth, dwell, mealAt) => {
      if (depth > 0 && drive + dwell >= worst(pts[0])) return;
      for (let q = prev + 1; q <= N; q++) {
        if (soc - needUse(prev, q) < PROFILE.reserveSoc) break;
        const arr = soc - meanUse(prev, q), c = q - 1, eta = nav[q] * paceMean + dwell;
        const eat = !!meal && mealAt < 0 && cs[c].mealOk && eta >= meal.fromMin && eta <= meal.toMin;
        const toDest = Math.max(ceil05(needUse(q, dest) + arrivalReserve), MIN_TARGET);
        const m = eat ? depth : mealAt;
        pts[depth] = q;
        for (let t = ceil05(Math.max(arr + 0.05, MIN_TARGET)); t <= MAX_TARGET + 1e-9; t += 0.05) {
          const last = t >= toDest - 1e-9;
          const tgt = last ? Math.max(toDest, ceil05(arr + 0.05)) : t;
          if (tgt > MAX_TARGET + 1e-9) break;
          tgts[depth] = tgt;
          const dw = dwell + meanDwell(c, arr, tgt, eat);
          if (last && eat) {
            // 밥 먹는 시간 안에 끝나는 만큼은 더 채워도 시간이 안 든다 → 도착 배터리를 넉넉하게
            let more = tgt;
            while (more + 0.05 <= MAX_TARGET + 1e-9 && meanDwell(c, arr, more + 0.05, true) - cs[c].detourMin <= meal.minutes - 3) more += 0.05;
            tgts[depth] = more;
          }
          if (last) { record(depth + 1, m, dw); break; }
          if (depth + 1 < maxStops) extend(q, tgt, depth + 1, dw, m);
        }
      }
    };
    extend(0, soc0, 0, 0, -1);

    // 2) 정밀 계산: 확률 모자라면 가장 자주 실패하는 구간 앞 정차에서 더 채움
    const evaluate = (dr) => {
      const k = dr.points.length, targets = dr.targets.slice();
      const socAt = dr.points.map(() => new Float64Array(n)), charge = dr.points.map(() => new Float64Array(n)), dwell = dr.points.map(() => new Float64Array(n));
      const total = new Float64Array(n), destSoc = new Float64Array(n);
      const sep = meal && dr.mealAt < 0 ? separateMin : 0;
      for (let bump = 0; bump <= MAX_BUMPS; bump++) {
        const fails = new Array(k + 1).fill(0);
        let ok = 0;
        for (let i = 0; i < n; i++) {
          const cap = this.cap[i];
          let soc = soc0, prev = 0, t = mins[dest][i] + sep, failed = -1;
          for (let s = 0; s < k; s++) {
            const q = dr.points[s], c = q - 1;
            const arr = soc - (kwh[q][i] - kwh[prev][i]) / cap;
            socAt[s][i] = arr;
            if (arr < PROFILE.reserveSoc && failed < 0) failed = s;
            const from = Math.max(arr, 0), to = Math.max(targets[s], from);
            charge[s][i] = lookup(tables[c], from, to) * cap / this.k[i];
            const active = fixed[c][i] + charge[s][i];
            dwell[s][i] = detour[c][i] + (s === dr.mealAt ? Math.max(active, meal.minutes) : active);
            t += dwell[s][i]; soc = to; prev = q;
          }
          destSoc[i] = soc - (kwh[dest][i] - kwh[prev][i]) / cap;
          if (destSoc[i] < arrivalReserve && failed < 0) failed = k;
          total[i] = t;
          if (failed < 0) ok++; else fails[failed]++;
        }
        const p = ok / n;
        if (p >= targetP) {
          const sorted = (a) => Float64Array.from(a).sort();
          const stops = dr.points.map((q, s) => {
            const sa = sorted(socAt[s]);
            return {
              charger: cs[q - 1].id, atKm: km[q], chargeToSoc: r2(targets[s]),
              socAtP10: pct(sa, 10), socAtP50: pct(sa, 50), socAtP90: pct(sa, 90),
              chargeMinutesP50: pct(sorted(charge[s]), 50), dwellMinutesP50: pct(sorted(dwell[s]), 50), meal: s === dr.mealAt,
            };
          });
          const ts = sorted(total), ds = sorted(destSoc), f = stops[0];
          return {
            n, charger: f.charger, chargeToSoc: f.chargeToSoc, probability: p,
            minutesP10: pct(ts, 10), minutesP50: pct(ts, 50), minutesP90: pct(ts, 90), chargeMinutesP50: f.chargeMinutesP50,
            socAtChargerP10: f.socAtP10, socAtChargerP50: f.socAtP50, socAtChargerP90: f.socAtP90,
            destSocP10: pct(ds, 10), destSocP50: pct(ds, 50), destSocP90: pct(ds, 90),
            stops, mealSeparateMin: sep, mealLabel: meal?.label ?? null,
            // 시각화용 시뮬레이션 결과 (정렬 전 원본: 같은 i 가 같은 시나리오)
            samples: { socAt: socAt.map((a) => Float64Array.from(a)), dest: Float64Array.from(destSoc), total: Float64Array.from(total), arrivalReserve },
          };
        }
        const w = fails.indexOf(Math.max(...fails));
        if (w === 0) return null;
        targets[w - 1] += 0.05;
        if (targets[w - 1] > MAX_TARGET + 1e-9) return null;
      }
      return null;
    };
    const out = [];
    for (const drafts of keep.values()) {
      const plans = drafts.map(evaluate).filter(Boolean);
      if (plans.length) out.push(plans.reduce((a, b) => (score(b) < score(a) ? b : a)));
    }
    return out;
  }

  /** (한 곳만 들르는 이전 방식) 충전소 하나의 최선 충전 목표. 목표 확률 미달이면 null */
  /** [arrivalReserve]: 목적지 도착 시 최소 배터리 (95% 확률로 이 이상 남게) */
  bestForCharger(soc0, totalKm, totalMin, c, arrivalReserve = 0.30, targetP = 0.95) {
    const d1 = Math.min(Math.max(c.atKm, 0), totalKm), nav1 = totalMin * d1 / totalKm;
    const table = chargeTable(c.powerKw);
    const n = this.n, socAt = new Float64Array(n), l1 = new Float64Array(n), l2k = new Float64Array(n), l2m = new Float64Array(n), fixed = new Float64Array(n);
    const wl = Math.log(Math.max(c.waitMeanMin, 0.5));
    for (let i = 0; i < n; i++) {
      const [k1, m1] = this.leg(i, d1, nav1, this.noise1);
      const [k2, m2] = this.leg(i, totalKm - d1, totalMin - nav1, this.noise2);
      socAt[i] = soc0 - k1 / this.cap[i]; l1[i] = m1; l2k[i] = k2; l2m[i] = m2;
      fixed[i] = Math.exp(wl + this.waitZ[i] * 0.6) + this.overhead[i] + c.detourMin * Math.exp(this.detourZ[i] * (c.detourSigma ?? 0.4));
    }
    let best = null;
    const total = new Float64Array(n), chargeMin = new Float64Array(n), destSoc = new Float64Array(n);
    const socAtSorted = Float64Array.from(socAt).sort();
    for (let step = 0; step <= 13; step++) {
      const tgt = 0.30 + step * 0.05;
      let ok = 0;
      for (let i = 0; i < n; i++) {
        const from = Math.max(socAt[i], 0), to = Math.max(tgt, from);
        chargeMin[i] = lookup(table, from, to) * this.cap[i] / this.k[i];
        total[i] = l1[i] + l2m[i] + fixed[i] + chargeMin[i];
        destSoc[i] = to - l2k[i] / this.cap[i];
        if (socAt[i] >= PROFILE.reserveSoc && destSoc[i] >= arrivalReserve) ok++;
      }
      const p = ok / n;
      if (p < targetP) continue;
      const ts = Float64Array.from(total).sort(), cs = Float64Array.from(chargeMin).sort();
      const ds = Float64Array.from(destSoc).sort();
      const plan = {
        minutesP10: pct(ts, 10), n,
        // 시각화용 시뮬레이션 결과 (정렬 전 원본)
        samples: { socAt: Float64Array.from(socAt), dest: Float64Array.from(destSoc), total: Float64Array.from(total), arrivalReserve },
        charger: c.id, chargeToSoc: tgt, probability: p, minutesP50: pct(ts, 50), minutesP90: pct(ts, 90), chargeMinutesP50: pct(cs, 50),
        socAtChargerP10: pct(socAtSorted, 10), socAtChargerP50: pct(socAtSorted, 50), socAtChargerP90: pct(socAtSorted, 90),
        destSocP10: pct(ds, 10), destSocP50: pct(ds, 50), destSocP90: pct(ds, 90),
      };
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
  const mc = new MonteCarlo(env, 1500, 42, inp.learnedTrips ?? 0);
  const soc0 = inp.soc / 100;
  const direct = mc.arrival(soc0, totalKm, totalMin);

  const cands = rc.chargers.filter((v) => v.total - v.outOfService > 0);
  const base = { route, direct, env, weather: { tempC, kind: weather, forecast }, checked: cands.length, candidates: cands };
  const arrivalReserve = inp.arrivalReserve ?? 0.30;
  // 가는 길에 점심·저녁 시간이 걸리면 식당 있는 휴게소에서 밥 먹으며 충전하는 계획도 비교
  const meal = inp.meal === false ? null : mealWindow(departMs, totalMin);
  const byId = new Map(cands.map((v) => [v.id, v]));
  const waits = new Map(cands.map((v) => [v.id, expectedWaitMin(v, v.etaMin)]));
  // 우회 흔들림: 휴게소 진출입은 일정, 시내는 신호·정체로 들쭉날쭉 → 시내는 확실히 빠를 때만 뽑힘
  const chargers = cands.map((v) => ({
    id: v.id, atKm: v.atKm, powerKw: Math.min(Math.max(v.maxKw, 30), 350), waitMeanMin: waits.get(v.id), detourMin: v.detourMin,
    detourSigma: v.restArea ? 0.2 : 0.6, mealOk: v.restArea && v.name.includes("휴게소"),
  }));
  const option = (p) => { const v = byId.get(p.charger); return { station: v, atKm: v.atKm, waitMin: waits.get(v.id), plan: p }; };
  // 충전 없이도 95% 확률로 [arrivalReserve] 이상 남으면 충전 불필요
  const directOk = direct.socP10 >= arrivalReserve && mc.arrivalShare(soc0, totalKm, totalMin, arrivalReserve) >= 0.95;
  if (directOk) {
    // 그래도 식사 시간이 걸리면 밥 먹을 휴게소를 고르고, 먹는 동안 채운다 (시간 손해 없음)
    const ms = meal ? mc.planPerFirstStop(soc0, totalKm, totalMin, chargers.filter((c) => c.mealOk), arrivalReserve, 0.95, meal, 1).filter((p) => p.stops[0]?.meal) : [];
    const mealStop = ms.length ? option(ms.reduce((a, b) => (score(b) < score(a) ? b : a))) : null;
    return { ...base, meal, stations: byId, mealStop, plan: { kind: "none", probability: direct.probability }, options: [], baseline: null, savedMin: null };
  }
  // 첫 충전소마다 그 뒤 충전소(최대 3곳)·충전량·식사까지 포함한 가장 빠른 계획
  const options = mc.planPerFirstStop(soc0, totalKm, totalMin, chargers, arrivalReserve, 0.95, meal).map(option);
  options.sort((a, b) => score(a.plan) - score(b.plan));
  const baseline = options.reduce((m, o) => (!m || o.station.maxKw > m.station.maxKw ? o : m), null);
  const best = options[0];
  const savedMin = best && baseline && baseline.station.id !== best.station.id ? baseline.plan.minutesP50 - best.plan.minutesP50 : null;
  return { ...base, meal, stations: byId, plan: best ? { kind: "plan", ...best.plan } : { kind: "infeasible", probability: direct.probability }, options, baseline, savedMin };
}

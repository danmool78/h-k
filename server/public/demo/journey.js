// 배터리 여정 시각화: 경로 거리에 따른 배터리 % (중앙값 선 + 예측 범위 띠 + 충전 막대 + 도착 최소 기준선)
// 앱의 BatteryJourney (Compose) 와 같은 디자인.

const C = {
  line: "#4f8cff", band: "rgba(79,140,255,.18)", charge: "#4caf50", meal: "#ffb300", reserve: "#e53935",
  grid: "#2a2f3a", text: "#9aa3b5", strong: "#e8eaf0",
};
export const levelColor = (soc) => (soc >= 0.5 ? "#4caf50" : soc >= 0.2 ? "#ffb300" : "#e53935");
const P = (x) => `${Math.round(Math.max(x, 0) * 100)}%`;

/**
 * @param j { totalKm, soc0, reserve, stops: [{ atKm, name, arriveP10, arriveP50, arriveP90, chargeTo, chargeMin, meal? }],
 *            dest: { p10, p50, p90 }, destName, samples?: { socAt: [Float64Array per stop], dest } }
 */
export function journeySvg(j) {
  const W = 640, H = 220, L = 44, R = 16, T = 18, B = 46;
  const x = (km) => L + (W - L - R) * (km / j.totalKm);
  const y = (soc) => T + (H - T - B) * (1 - Math.min(Math.max(soc, 0), 1));
  const parts = [];

  // 격자
  for (const g of [0, 0.25, 0.5, 0.75, 1]) {
    parts.push(`<line x1="${L}" x2="${W - R}" y1="${y(g)}" y2="${y(g)}" stroke="${C.grid}" stroke-width="1"/>`);
    parts.push(`<text x="${L - 8}" y="${y(g) + 4}" fill="${C.text}" font-size="11" text-anchor="end">${g * 100}%</text>`);
  }

  // 도착 최소 기준선
  parts.push(`<line x1="${L}" x2="${W - R}" y1="${y(j.reserve)}" y2="${y(j.reserve)}" stroke="${C.reserve}" stroke-width="1.5" stroke-dasharray="6 5"/>`);
  parts.push(`<text x="${W - R}" y="${y(j.reserve) - 6}" fill="${C.reserve}" font-size="11" text-anchor="end">도착 최소 ${P(j.reserve)}</text>`);

  const stops = j.stops ?? [];
  // 시뮬레이션 중 일부를 옅은 선으로: "여러 번 달려본 결과"가 띠가 된다는 걸 보여줌
  if (j.samples) {
    const n = j.samples.dest.length, step = Math.max(1, Math.floor(n / 40));
    for (let i = 0; i < n; i += step) {
      const d = j.samples.dest[i];
      let pts = `${x(0)},${y(j.soc0)}`;
      stops.forEach((s, k) => {
        const a = j.samples.socAt[k][i];
        pts += ` ${x(s.atKm)},${y(a)} ${x(s.atKm)},${y(Math.max(s.chargeTo, a))}`;
      });
      pts += ` ${x(j.totalKm)},${y(d)}`;
      parts.push(`<polyline fill="none" stroke="${d < j.reserve ? "#e53935" : "#9aa3b5"}" stroke-opacity=".22" stroke-width="1" points="${pts}"/>`);
    }
  }
  // 구간마다 예측 범위 띠 + 중앙값 선: 출발(또는 충전 후) → 다음 충전소 도착(또는 목적지)
  let km0 = 0, s0 = j.soc0;
  for (const s of stops) {
    parts.push(`<polygon fill="${C.band}" points="${x(km0)},${y(s0)} ${x(s.atKm)},${y(s.arriveP90)} ${x(s.atKm)},${y(s.arriveP10)}"/>`);
    parts.push(`<polyline fill="none" stroke="${C.line}" stroke-width="3" stroke-linejoin="round" points="${x(km0)},${y(s0)} ${x(s.atKm)},${y(s.arriveP50)}"/>`);
    km0 = s.atKm; s0 = s.chargeTo;
  }
  parts.push(`<polygon fill="${C.band}" points="${x(km0)},${y(s0)} ${x(j.totalKm)},${y(j.dest.p90)} ${x(j.totalKm)},${y(j.dest.p10)}"/>`);
  parts.push(`<polyline fill="none" stroke="${C.line}" stroke-width="3" stroke-linejoin="round" points="${x(km0)},${y(s0)} ${x(j.totalKm)},${y(j.dest.p50)}"/>`);
  // 충전 막대 (밥 먹으며 충전하는 곳은 주황)
  const bw = 14;
  for (const s of stops) {
    const col = s.meal ? C.meal : C.charge;
    parts.push(`<rect x="${x(s.atKm) - bw / 2}" y="${y(s.chargeTo)}" width="${bw}" height="${y(s.arriveP50) - y(s.chargeTo)}" rx="4" fill="${col}" opacity=".9"/>`);
    parts.push(`<text x="${x(s.atKm) + bw / 2 + 6}" y="${(y(s.chargeTo) + y(s.arriveP50)) / 2 + 4}" fill="${col}" font-size="12" font-weight="700">+${Math.round((s.chargeTo - s.arriveP50) * 100)}% · ${Math.round(s.chargeMin)}분${s.meal ? ` · 🍚${s.meal}` : ""}</text>`);
  }

  // 지점 표시
  const dot = (km, soc, color) => `<circle cx="${x(km)}" cy="${y(soc)}" r="5" fill="${color}" stroke="#0f1115" stroke-width="2"/>`;
  parts.push(dot(0, j.soc0, levelColor(j.soc0)));
  for (const s of stops) { parts.push(dot(s.atKm, s.arriveP50, levelColor(s.arriveP50))); parts.push(dot(s.atKm, s.chargeTo, levelColor(s.chargeTo))); }
  parts.push(dot(j.totalKm, j.dest.p50, levelColor(j.dest.p50)));

  // 가로축 라벨
  const label = (km, text, anchor) => `<text x="${x(km)}" y="${H - B + 18}" fill="${C.text}" font-size="12" text-anchor="${anchor}">${text}</text>`;
  parts.push(label(0, "출발", "start"));
  const max = stops.length > 1 ? 7 : 12;
  stops.forEach((s, k) => parts.push(label(s.atKm, `${stops.length > 1 ? "①②③④"[k] + " " : ""}${s.name.length > max ? s.name.slice(0, max) + "…" : s.name}${stops.length > 1 ? "" : ` · ${Math.round(s.atKm)}km`}`, "middle")));
  parts.push(label(j.totalKm, `${j.destName} · ${Math.round(j.totalKm)}km`, "end"));
  parts.push(`<text x="${L}" y="${H - 6}" fill="${C.text}" font-size="11">선: 예상 · 띠: 예측 범위 (적게 남는 경우 ~ 많이 남는 경우)</text>`);

  return `<svg viewBox="0 0 ${W} ${H}" width="100%" role="img" aria-label="배터리 여정">${parts.join("")}</svg>`;
}

/** 배터리 모양 게이지 하나 */
function battery(label, soc, sub) {
  const pctW = Math.round(Math.min(Math.max(soc, 0), 1) * 100);
  return `<div class="batt">
    <div class="batt-label">${label}</div>
    <div class="batt-body"><div class="batt-fill" style="width:${pctW}%;background:${levelColor(soc)}"></div><span>${P(soc)}</span></div>
    <div class="batt-sub">${sub ?? "&nbsp;"}</div>
  </div>`;
}

/** 출발 / 충전소 도착 / 충전 후 / 목적지 도착 게이지 줄 */
export function batteryRow(j) {
  const stops = j.stops ?? [];
  const items = [battery("출발", j.soc0)];
  if (stops.length === 1) {
    const s = stops[0];
    items.push(battery("충전소 도착", s.arriveP50, `적으면 ${P(s.arriveP10)}`));
    items.push(battery("충전 후", s.chargeTo, `${Math.round(s.chargeMin)}분 충전${s.meal ? ` · 🍚${s.meal}` : ""}`));
  } else {
    // 여러 곳이면 충전소마다 "충전 후" 하나씩
    stops.forEach((s, k) => items.push(battery(`${"①②③④"[k]} 충전 후`, s.chargeTo, `도착 ${P(s.arriveP50)} · ${Math.round(s.chargeMin)}분${s.meal ? ` · 🍚${s.meal}` : ""}`)));
  }
  items.push(battery("목적지 도착", j.dest.p50, `적으면 ${P(j.dest.p10)}`));
  return `<div class="batt-row">${items.join('<div class="batt-arrow">→</div>')}</div>`;
}

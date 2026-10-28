// 배터리 여정 시각화: 경로 거리에 따른 배터리 % (중앙값 선 + 예측 범위 띠 + 충전 막대 + 도착 최소 기준선)
// 앱의 BatteryJourney (Compose) 와 같은 디자인.

const C = {
  line: "#4f8cff", band: "rgba(79,140,255,.18)", charge: "#4caf50", reserve: "#e53935",
  grid: "#2a2f3a", text: "#9aa3b5", strong: "#e8eaf0",
};
export const levelColor = (soc) => (soc >= 0.5 ? "#4caf50" : soc >= 0.2 ? "#ffb300" : "#e53935");
const P = (x) => `${Math.round(Math.max(x, 0) * 100)}%`;

/**
 * @param j { totalKm, soc0, reserve, stop?: { atKm, name, arriveP10, arriveP50, arriveP90, chargeTo, chargeMin },
 *            dest: { p10, p50, p90 }, destName }
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

  const s = j.stop;
  if (s) {
    // 예측 범위 띠 (충전 전 / 충전 후)
    parts.push(`<polygon fill="${C.band}" points="${x(0)},${y(j.soc0)} ${x(s.atKm)},${y(s.arriveP90)} ${x(s.atKm)},${y(s.arriveP10)}"/>`);
    parts.push(`<polygon fill="${C.band}" points="${x(s.atKm)},${y(s.chargeTo)} ${x(j.totalKm)},${y(j.dest.p90)} ${x(j.totalKm)},${y(j.dest.p10)}"/>`);
    // 충전 막대
    const bw = 14;
    parts.push(`<rect x="${x(s.atKm) - bw / 2}" y="${y(s.chargeTo)}" width="${bw}" height="${y(s.arriveP50) - y(s.chargeTo)}" rx="4" fill="${C.charge}" opacity=".9"/>`);
    parts.push(`<text x="${x(s.atKm) + bw / 2 + 6}" y="${(y(s.chargeTo) + y(s.arriveP50)) / 2 + 4}" fill="${C.charge}" font-size="12" font-weight="700">+${Math.round((s.chargeTo - s.arriveP50) * 100)}% · ${Math.round(s.chargeMin)}분</text>`);
    // 중앙값 선
    parts.push(`<polyline fill="none" stroke="${C.line}" stroke-width="3" stroke-linejoin="round" points="${x(0)},${y(j.soc0)} ${x(s.atKm)},${y(s.arriveP50)}"/>`);
    parts.push(`<polyline fill="none" stroke="${C.line}" stroke-width="3" stroke-linejoin="round" points="${x(s.atKm)},${y(s.chargeTo)} ${x(j.totalKm)},${y(j.dest.p50)}"/>`);
  } else {
    parts.push(`<polygon fill="${C.band}" points="${x(0)},${y(j.soc0)} ${x(j.totalKm)},${y(j.dest.p90)} ${x(j.totalKm)},${y(j.dest.p10)}"/>`);
    parts.push(`<polyline fill="none" stroke="${C.line}" stroke-width="3" points="${x(0)},${y(j.soc0)} ${x(j.totalKm)},${y(j.dest.p50)}"/>`);
  }

  // 지점 표시
  const dot = (km, soc, color) => `<circle cx="${x(km)}" cy="${y(soc)}" r="5" fill="${color}" stroke="#0f1115" stroke-width="2"/>`;
  parts.push(dot(0, j.soc0, levelColor(j.soc0)));
  if (s) { parts.push(dot(s.atKm, s.arriveP50, levelColor(s.arriveP50))); parts.push(dot(s.atKm, s.chargeTo, levelColor(s.chargeTo))); }
  parts.push(dot(j.totalKm, j.dest.p50, levelColor(j.dest.p50)));

  // 가로축 라벨
  const label = (km, text, anchor) => `<text x="${x(km)}" y="${H - B + 18}" fill="${C.text}" font-size="12" text-anchor="${anchor}">${text}</text>`;
  parts.push(label(0, "출발", "start"));
  if (s) parts.push(label(s.atKm, `${s.name.length > 12 ? s.name.slice(0, 12) + "…" : s.name} · ${Math.round(s.atKm)}km`, "middle"));
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
  const s = j.stop;
  const items = [battery("출발", j.soc0)];
  if (s) {
    items.push(battery("충전소 도착", s.arriveP50, `적으면 ${P(s.arriveP10)}`));
    items.push(battery("충전 후", s.chargeTo, `${Math.round(s.chargeMin)}분 충전`));
  }
  items.push(battery("목적지 도착", j.dest.p50, `적으면 ${P(j.dest.p10)}`));
  return `<div class="batt-row">${items.join('<div class="batt-arrow">→</div>')}</div>`;
}

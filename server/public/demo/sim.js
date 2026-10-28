// "2,000번 미리 달려봤어요": 몬테카를로 결과를 사람이 보기 쉽게 (분포 막대 + 운 좋으면/보통/운 나쁘면)

const P = (x) => `${Math.round(Math.max(x, 0) * 100)}%`;
const clock = (ms) => new Date(ms).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Seoul" });
const quant = (arr, q) => { const s = Float64Array.from(arr).sort(); const pos = (s.length - 1) * q; const lo = Math.floor(pos); return s[lo] + (s[Math.min(lo + 1, s.length - 1)] - s[lo]) * (pos - lo); };

/** 운 좋으면 / 보통 / 운 나쁘면 (도착 시각과 남는 배터리) */
export function luckRow(departMs, totals, dests) {
  // 시간은 짧을수록, 배터리는 많을수록 운이 좋은 것
  const cell = (label, color, t, d, note) => `<div class="luck" style="border-color:${color}">
      <div class="luck-label" style="color:${color}">${label}</div>
      <div class="luck-time">${clock(departMs + t * 60_000)}</div>
      <div class="luck-soc">배터리 ${P(d)} 남음</div>
      <div class="luck-note">${note}</div></div>`;
  return `<div class="luck-row">
    ${cell("운 좋으면", "#4caf50", quant(totals, 0.1), quant(dests, 0.9), "10번 중 1번은 이보다 좋음")}
    ${cell("보통", "#4f8cff", quant(totals, 0.5), quant(dests, 0.5), "가장 흔한 결과")}
    ${cell("운 나쁘면", "#e53935", quant(totals, 0.9), quant(dests, 0.1), "10번 중 1번은 이보다 나쁨")}
  </div>`;
}

function histogram(values, { bins, min, max, color, markers = [], refLine, xLabel }) {
  const W = 600, H = 150, L = 8, R = 8, T = 14, B = 34;
  const counts = new Array(bins).fill(0);
  for (const v of values) {
    const i = Math.min(bins - 1, Math.max(0, Math.floor((v - min) / (max - min) * bins)));
    counts[i]++;
  }
  const top = Math.max(...counts, 1);
  const bw = (W - L - R) / bins;
  const x = (v) => L + (W - L - R) * (v - min) / (max - min);
  const parts = counts.map((c, i) => {
    const h = (H - T - B) * c / top;
    const mid = min + (i + 0.5) * (max - min) / bins;
    const fill = refLine && refLine.below && mid < refLine.value ? "#e53935" : color;
    return `<rect class="bar" x="${L + i * bw + 1}" y="${H - B - h}" width="${bw - 2}" height="${h}" rx="2" fill="${fill}" style="animation-delay:${i * 18}ms"/>`;
  });
  if (refLine) {
    parts.push(`<line x1="${x(refLine.value)}" x2="${x(refLine.value)}" y1="${T - 6}" y2="${H - B}" stroke="#e53935" stroke-width="2" stroke-dasharray="5 4"/>`);
    parts.push(`<text x="${x(refLine.value) + 4}" y="${T + 2}" fill="#e53935" font-size="11">${refLine.label}</text>`);
  }
  for (const m of markers) {
    parts.push(`<line x1="${x(m.value)}" x2="${x(m.value)}" y1="${T}" y2="${H - B + 4}" stroke="${m.color}" stroke-width="2"/>`);
    parts.push(`<text x="${x(m.value)}" y="${H - B + 18}" fill="${m.color}" font-size="12" text-anchor="middle" font-weight="700">${m.label}</text>`);
  }
  parts.push(`<text x="${L}" y="${H - 2}" fill="#9aa3b5" font-size="11">${xLabel}</text>`);
  return `<svg viewBox="0 0 ${W} ${H}" width="100%">${parts.join("")}</svg>`;
}

/** 시뮬레이션 카드 HTML. [s] = { total, dest, n, arrivalReserve } */
export function simCard(s, departMs, reserve) {
  const t10 = quant(s.total, 0.1), t50 = quant(s.total, 0.5), t90 = quant(s.total, 0.9);
  const tMin = Math.floor(quant(s.total, 0.01) / 5) * 5, tMax = Math.ceil(quant(s.total, 0.99) / 5) * 5 + 5;
  const ok = Array.from(s.dest).filter((d) => d >= reserve).length / s.dest.length;
  const dMin = Math.max(0, Math.floor(quant(s.dest, 0.005) * 20) / 20 - 0.05), dMax = Math.min(1, Math.ceil(quant(s.dest, 0.995) * 20) / 20 + 0.05);
  return `
    <div class="sim-head"><b class="sim-count" data-n="${s.n}">0</b><span>번 미리 달려봤어요</span></div>
    <div class="muted small">기온·정체·운전 습관·배터리 상태·휴게소 대기를 그럴 법한 범위에서 바꿔 가며 같은 여행을 계산한 결과입니다.</div>
    <h4>언제 도착할까?</h4>
    ${histogram(s.total, {
      bins: Math.min(40, Math.max(12, Math.round((tMax - tMin) / 3))), min: tMin, max: tMax, color: "#4f8cff",
      markers: [{ value: t50, label: `보통 ${clock(departMs + t50 * 60_000)}`, color: "#4f8cff" }, { value: t90, label: `운 나쁘면 ${clock(departMs + t90 * 60_000)}`, color: "#e53935" }],
      xLabel: `빠름 ← 도착 시각 → 늦음 · 운 좋으면 ${clock(departMs + t10 * 60_000)}`,
    })}
    <h4>얼마나 남을까?</h4>
    ${histogram(s.dest, {
      bins: 30, min: dMin, max: dMax, color: "#4caf50",
      refLine: { value: reserve, label: `도착 최소 ${P(reserve)}`, below: true },
      markers: [{ value: quant(s.dest, 0.5), label: `보통 ${P(quant(s.dest, 0.5))}`, color: "#4caf50" }],
      xLabel: "적게 남음 ← 도착 배터리 → 많이 남음",
    })}
    <div class="sim-prob"><b style="color:${ok >= 0.95 ? "#4caf50" : "#ffb300"}">${Math.round(ok * 1000) / 10}%</b> 확률로 ${P(reserve)} 이상 남기고 도착
      <span class="muted small">(${s.n.toLocaleString()}번 중 ${Math.round(ok * s.n).toLocaleString()}번)</span></div>`;
}

/** 숫자가 0 → n 으로 올라가는 효과 */
export function animateCount(root) {
  const el = root.querySelector(".sim-count");
  if (!el) return;
  const n = +el.dataset.n, t0 = performance.now();
  const step = (now) => {
    const f = Math.min(1, (now - t0) / 900);
    el.textContent = Math.round(n * (1 - Math.pow(1 - f, 3))).toLocaleString();
    if (f < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

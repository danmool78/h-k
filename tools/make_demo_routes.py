#!/usr/bin/env python3
"""
시연용 실제 지역 경로 만들기 (한 번 돌려 결과 JSON 을 앱 리소스로 넣는다).

- 도로 선·구간 속도·회전 안내: OSRM (OpenStreetMap 길찾기, router.project-osrm.org)
- 높이(경로·주변 지형 격자): Open-Meteo 고도 API (Copernicus DEM 90m)

결과: shared/src/commonMain/composeResources/files/routes/<id>.json
  x, y   : 출발점 기준 m (동, 북)        z : 그 점의 높이 (m)
  v      : 그 점에서 다음 점까지 OSRM 구간 속도 (m/s)
  turns  : [거리(m), 방향(-1 왼쪽, 1 오른쪽, 0), 종류]  (교차로 회전·진출입로·회전 교차로)
  grid   : 주변 지형 {x0, y0, step, nx, ny, h}
사용: python3 tools/make_demo_routes.py
"""
import json
import math
import os
import time
import urllib.request

ROUTES = [
    ("daegwallyeong", "대관령 넘어가는 길", "mountain", [(37.6869, 128.7553), (37.7051, 128.7782), (37.7253, 128.8165)]),
    ("hangyeryeong", "한계령 (설악로)", "mountain", [(38.0909, 128.4258), (38.0785, 128.5277)]),
    ("seoul", "서울 도심 (광화문 → 동대문)", "city", [(37.5759, 126.9769), (37.5700, 126.9920), (37.5665, 127.0090)]),
    ("highway", "고속도로 (판교 → 경부고속도로 → 수원IC)", "highway", [(37.4000, 127.1080), (37.2700, 127.1150)]),
]
OUT = os.path.join(os.path.dirname(__file__), "..", "shared", "src", "commonMain", "composeResources", "files", "routes")
MARGIN = 3000.0
GRID_MAX = 64


def get(url):
    for i in range(5):
        try:
            with urllib.request.urlopen(url, timeout=40) as r:
                return json.load(r)
        except Exception as e:  # 잠깐 막히면 다시
            print("retry", e)
            time.sleep(2 * (i + 1))
    raise RuntimeError(url)


def heights(lats, lngs):
    out = []
    for i in range(0, len(lats), 100):
        la = ",".join(f"{v:.5f}" for v in lats[i:i + 100])
        lo = ",".join(f"{v:.5f}" for v in lngs[i:i + 100])
        out += get(f"https://api.open-meteo.com/v1/elevation?latitude={la}&longitude={lo}")["elevation"]
        time.sleep(1.0)
    return out


def turn_kind(st):
    t, m = st["maneuver"]["type"], st["maneuver"].get("modifier", "")
    if t in ("depart", "arrive", "new name", "continue") or m in ("straight", ""):
        return None
    if t in ("roundabout", "rotary", "roundabout turn"):
        return "ROUNDABOUT"
    if t in ("on ramp",):
        return "IC_ENTER"
    if t in ("off ramp",):
        return "IC_EXIT"
    if t == "fork" or t == "merge":
        return None
    if m == "uturn":
        return "UTURN"
    if m.startswith("sharp"):
        return "SHARP_TURN"
    if m.startswith("slight"):
        return None
    return "TURN"


def build(rid, label, kind, pts):
    q = ";".join(f"{lng},{lat}" for lat, lng in pts)
    d = get(f"https://router.project-osrm.org/route/v1/driving/{q}?overview=full&geometries=geojson&steps=true&annotations=speed")
    r = d["routes"][0]
    coords = r["geometry"]["coordinates"]  # [lng, lat]
    lat0, lng0 = coords[0][1], coords[0][0]
    kx, ky = 111320.0 * math.cos(math.radians(lat0)), 110540.0
    xs = [(c[0] - lng0) * kx for c in coords]
    ys = [(c[1] - lat0) * ky for c in coords]
    cum = [0.0]
    for i in range(1, len(xs)):
        cum.append(cum[-1] + math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]))
    total = cum[-1]
    speeds = [s for leg in r["legs"] for s in leg["annotation"]["speed"]]
    speeds += [speeds[-1]] * (len(xs) - len(speeds))
    # 회전 안내: 위치에서 가장 가까운 점의 거리
    turns = []
    for leg in r["legs"]:
        for st in leg["steps"]:
            k = turn_kind(st)
            if not k:
                continue
            lng, lat = st["maneuver"]["location"]
            x, y = (lng - lng0) * kx, (lat - lat0) * ky
            i = min(range(len(xs)), key=lambda j: (xs[j] - x) ** 2 + (ys[j] - y) ** 2)
            m = st["maneuver"].get("modifier", "")
            dr = -1 if "left" in m else (1 if "right" in m else (-1 if m == "uturn" else 0))
            turns.append([round(cum[i], 1), dr, k])
    # 경로 높이: 40m 마다 (최대 300곳) 재고 점마다 보간
    n = max(2, min(300, int(total / 40)))
    sl, sg = [], []
    j = 0
    for k in range(n):
        dd = total * k / (n - 1)
        while j < len(cum) - 2 and cum[j + 1] < dd:
            j += 1
        span = cum[j + 1] - cum[j]
        t = (dd - cum[j]) / span if span > 0 else 0
        sl.append(coords[j][1] + (coords[j + 1][1] - coords[j][1]) * t)
        sg.append(coords[j][0] + (coords[j + 1][0] - coords[j][0]) * t)
    sh = heights(sl, sg)
    zs = []
    for c in cum:
        f = c / total * (n - 1)
        a = min(int(f), n - 1)
        b = min(a + 1, n - 1)
        zs.append(sh[a] + (sh[b] - sh[a]) * (f - a))
    # 주변 지형 격자
    minx, maxx = min(xs) - MARGIN, max(xs) + MARGIN
    miny, maxy = min(ys) - MARGIN, max(ys) + MARGIN
    step = max(200.0, max(maxx - minx, maxy - miny) / GRID_MAX)
    nx, ny = int((maxx - minx) / step) + 1, int((maxy - miny) / step) + 1
    glat = [lat0 + (miny + (i // nx) * step) / ky for i in range(nx * ny)]
    glng = [lng0 + (minx + (i % nx) * step) / kx for i in range(nx * ny)]
    gh = heights(glat, glng)
    out = {
        "id": rid, "label": label, "kind": kind,
        "durationSec": round(r["duration"]), "distanceM": round(total),
        "x": [round(v, 1) for v in xs], "y": [round(v, 1) for v in ys], "z": [round(v, 1) for v in zs],
        "v": [round(v, 1) for v in speeds],
        "turns": turns,
        "grid": {"x0": round(minx, 1), "y0": round(miny, 1), "step": round(step, 1), "nx": nx, "ny": ny, "h": [round(v) for v in gh]},
        "source": "OSRM (OpenStreetMap contributors, ODbL), Open-Meteo Elevation (Copernicus DEM GLO-90)",
    }
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, rid + ".json"), "w") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(rid, label, round(total), "m", len(xs), "pts", len(turns), "turns", f"grid {nx}x{ny}", f"z {min(zs):.0f}~{max(zs):.0f}")


if __name__ == "__main__":
    for r in ROUTES:
        build(*r)

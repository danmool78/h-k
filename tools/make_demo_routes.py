#!/usr/bin/env python3
"""
시연용 실제 지역 경로 만들기 (한 번 돌려 결과 JSON 을 앱 리소스로 넣는다).

- 도로 선·구간 속도·회전 안내: OSRM (OpenStreetMap 길찾기, router.project-osrm.org)
- 높이(경로·주변 지형 격자): 공개 고도 타일 (AWS Terrain Tiles, Terrarium PNG: 높이 = R*256 + G + B/256 - 32768)

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
GRID_MAX = 72


def get(url):
    for i in range(5):
        try:
            with urllib.request.urlopen(url, timeout=40) as r:
                return json.load(r)
        except Exception as e:  # 잠깐 막히면 다시
            print("retry", e)
            time.sleep(2 * (i + 1))
    raise RuntimeError(url)


TILE = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
_tiles = {}


def tile(z, x, y):
    key = (z, x, y)
    if key not in _tiles:
        import io
        from PIL import Image
        for i in range(5):
            try:
                with urllib.request.urlopen(TILE.format(z=z, x=x, y=y), timeout=40) as r:
                    im = Image.open(io.BytesIO(r.read())).convert("RGB")
                break
            except Exception as e:
                print("retry", e)
                time.sleep(2 * (i + 1))
        px = im.load()
        _tiles[key] = [[px[i, j][0] * 256 + px[i, j][1] + px[i, j][2] / 256 - 32768 for i in range(256)] for j in range(256)]
    return _tiles[key]


def heights(lats, lngs, z=13):
    """점마다 높이: 그 줌의 타일에서 쌍선형 (먼저 필요한 타일을 동시에 받는다)"""
    from concurrent.futures import ThreadPoolExecutor
    n = 2 ** z
    def pix(lat, lng):
        x = (lng + 180) / 360 * n * 256
        y = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n * 256
        return x, y
    need = set()
    for la, lo in zip(lats, lngs):
        x, y = pix(la, lo)
        need.add((int(x // 256), int(y // 256)))
        need.add((int((x + 1) // 256), int((y + 1) // 256)))
    with ThreadPoolExecutor(8) as ex:
        list(ex.map(lambda k: tile(z, k[0], k[1]), need))
    def at(px, py):
        t = tile(z, int(px // 256), int(py // 256))
        return t[int(py % 256)][int(px % 256)]
    out = []
    for la, lo in zip(lats, lngs):
        x, y = pix(la, lo)
        x0, y0 = math.floor(x - 0.5), math.floor(y - 0.5)
        fx, fy = x - 0.5 - x0, y - 0.5 - y0
        h = (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
        out.append(h)
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
    # 경로 높이: 20m 마다 재고 점마다 보간
    n = max(2, int(total / 20))
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
    gh = heights(glat, glng, z=12)
    out = {
        "id": rid, "label": label, "kind": kind, "lat0": lat0, "lng0": lng0,
        "durationSec": round(r["duration"]), "distanceM": round(total),
        "x": [round(v, 1) for v in xs], "y": [round(v, 1) for v in ys], "z": [round(v, 1) for v in zs],
        "v": [round(v, 1) for v in speeds],
        "turns": turns,
        "grid": {"x0": round(minx, 1), "y0": round(miny, 1), "step": round(step, 1), "nx": nx, "ny": ny, "h": [round(v) for v in gh]},
        "source": "OSRM (OpenStreetMap contributors, ODbL), AWS Terrain Tiles (Mapzen; SRTM, GMTED, ETOPO1 etc.)",
    }
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, rid + ".json"), "w") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(rid, label, round(total), "m", len(xs), "pts", len(turns), "turns", f"grid {nx}x{ny}", f"z {min(zs):.0f}~{max(zs):.0f}")


if __name__ == "__main__":
    for r in ROUTES:
        build(*r)

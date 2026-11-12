"""'목적지 → 도로·고도 자료 내려받기 → 3D 구성 → 위험 구간 분석' 장면. python3 pipeline.py 작업폴더 타일폴더 초"""
import glob, json, math, os, sys
from PIL import Image, ImageDraw, ImageFont

S, T, SEC = sys.argv[1], sys.argv[2], float(sys.argv[3])
FPS = 30
F = '/home/user/h-k/shared/src/commonMain/composeResources/font'
B = lambda n: ImageFont.truetype(f'{F}/nanum_gothic_bold.ttf', n)
R = lambda n: ImageFont.truetype(f'{F}/nanum_gothic.ttf', n)
CYAN, WHITE, DIM, RED = (41, 196, 246), (236, 243, 255), (160, 180, 210), (255, 77, 90)
Z = 13

# 고도 타일 이어 붙이기 + 실제 대관령 시연 길
tiles = {}
for f in glob.glob(f'{T}/t_*.png'):
    x, y = map(int, os.path.basename(f)[2:-4].split('_'))
    tiles[(x, y)] = Image.open(f).convert('RGB')
xs = sorted({k[0] for k in tiles}); ys = sorted({k[1] for k in tiles})
mosaic = Image.new('RGB', (256 * len(xs), 256 * len(ys)))
for (x, y), im in tiles.items():
    mosaic.paste(im, ((x - xs[0]) * 256, (y - ys[0]) * 256))
route = json.load(open('/home/user/h-k/shared/src/commonMain/composeResources/files/routes/daegwallyeong.json'))
lat0, lng0 = route['lat0'], route['lng0']
kx = 111320 * math.cos(math.radians(lat0))


def to_px(x, y):
    lat = lat0 + y / 110540; lng = lng0 + x / kx
    n = 2 ** Z
    px = (lng + 180) / 360 * n * 256 - xs[0] * 256
    py = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n * 256 - ys[0] * 256
    return px, py


pts = [to_px(x, y) for x, y in zip(route['x'], route['y'])]


def fit(im, w=1280, h=720):
    r = max(w / im.width, h / im.height)
    im = im.resize((int(im.width * r), int(im.height * r)), Image.LANCZOS)
    x, y = (im.width - w) // 2, (im.height - h) // 2
    return im.crop((x, y, x + w, y + h))


def header(im, num, title, sub):
    im = im.convert('RGBA')
    ov = Image.new('RGBA', im.size, (0, 0, 0, 0)); d = ImageDraw.Draw(ov)
    d.rounded_rectangle([30, 26, 30 + 64 + d.textlength(title, font=B(34)) + 40, 26 + 66], radius=16, fill=(4, 12, 28, 225))
    d.ellipse([44, 37, 88, 81], outline=CYAN, width=3)
    w = d.textlength(num, font=B(26)); d.text((66 - w / 2, 44), num, font=B(26), fill=CYAN)
    d.text((104, 40), title, font=B(34), fill=WHITE)
    if sub:
        w = d.textlength(sub, font=R(22))
        d.rounded_rectangle([30, 100, 30 + w + 32, 100 + 40], radius=12, fill=(4, 12, 28, 200))
        d.text((46, 107), sub, font=R(22), fill=DIM)
    return Image.alpha_composite(im, ov).convert('RGB')


def zoom(im, k, total, amount=0.05):
    z = 1 + amount * k / total
    w, h = int(1280 / z), int(720 / z)
    x, y = (1280 - w) // 2, (720 - h) // 2
    return im.crop((x, y, x + w, y + h)).resize((1280, 720), Image.LANCZOS)


app = lambda i: Image.open(f'{S}/f/{i:05d}.png').convert('RGB')
N = int(SEC * FPS)
per = N // 4
os.makedirs(f'{S}/pipe', exist_ok=True)
n = 0


def emit(im):
    global n
    im.save(f'{S}/pipe/{n:05d}.png'); n += 1


# 1. 목적지 입력: 정차 화면의 "가는 길" 카드 (내비 목적지가 정해지면 뜬다)
s1 = app(540)
for k in range(per):
    im = zoom(s1, k, per, 0.04)
    d = ImageDraw.Draw(im)
    a = min(1, k / 20)
    d.rounded_rectangle([848, 226, 1276, 404], radius=14, outline=tuple(int(c * a) for c in CYAN), width=4)
    emit(header(im, '1', '내비게이션에 목적지 입력', '현재 위치·목적지 (Pleos NaviHelper)'))

# 2. 내려받기: 고도 타일이 하나씩 받아지고, 그 위에 실제 도로가 그려진다
order = sorted(tiles, key=lambda t: (abs(t[0] - 7026.5) + abs(t[1] - 3168.4)))
for k in range(per):
    u = k / per
    im = Image.new('RGB', mosaic.size, (6, 14, 30))
    shown = order[:max(1, int(len(order) * min(1, u / 0.45)))]
    for (x, y) in shown:
        im.paste(tiles[(x, y)], ((x - xs[0]) * 256, (y - ys[0]) * 256))
    d = ImageDraw.Draw(im)
    for (x, y) in tiles:
        d.rectangle([(x - xs[0]) * 256, (y - ys[0]) * 256, (x - xs[0]) * 256 + 255, (y - ys[0]) * 256 + 255], outline=(10, 20, 40), width=2)
    if u > 0.45:
        m = int(len(pts) * min(1, (u - 0.45) / 0.4))
        if m > 1:
            d.line(pts[:m], fill=(255, 255, 255), width=12, joint='curve'); d.line(pts[:m], fill=RED, width=7, joint='curve')
    im = fit(im)
    emit(header(im, '2', '도로와 주변 지형 고도 자료 내려받기', 'OpenStreetMap 도로 선 · 공개 고도 타일 (색이 곧 높이) — 대관령 일대'))

# 3. 3D 지형으로 구성
for k in range(per):
    emit(header(zoom(app(300 + k), k, per, 0.03), '3', '3D 지형으로 구성', '경로 둘레 산·골짜기를 높이별 색과 그림자로'))

# 4. 분석: 편하게 달렸을 때의 흔들림 → 멀미 위험 구간
for k in range(N - 3 * per):
    emit(header(zoom(app(200), k, N - 3 * per, 0.04), '4', '흔들림 계산 → 멀미 위험 구간', '그 길을 편하게 달렸을 때의 앞뒤·좌우·위아래 흔들림을 ISO 2631-1 로'))
print('pipeline frames', n)

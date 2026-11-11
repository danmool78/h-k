"""'우리 아이 멀미를 배워서 미리 막는다' 설명 화면 (단계가 차례로 나타남). python3 learn.py 작업폴더 초"""
import os, sys
from PIL import Image, ImageDraw, ImageFont

S = sys.argv[1]
SEC = float(sys.argv[2])
FPS = 30
F = '/home/user/h-k/shared/src/commonMain/composeResources/font'
B = lambda n: ImageFont.truetype(f'{F}/nanum_gothic_bold.ttf', n)
R = lambda n: ImageFont.truetype(f'{F}/nanum_gothic.ttf', n)
CYAN, WHITE, DIM, RED, AMBER = (41, 196, 246), (236, 243, 255), (150, 172, 205), (255, 77, 90), (255, 179, 26)

STEPS = [
    ('"지금 멀미" 한 번', '바로 환기하고, 쉬어 갈 곳을 알려 줍니다'),
    ('그 순간을 아이 기준으로', '주행 기록을 다시 계산해 민지의 멀미 기준(물 높이)을 맞춥니다'),
    ('멀미 직전 흔들림 주기를 기억', '세 번부터 "민지는 5초 흔들림에 약해요" — 그 주기를 더 크게 봅니다'),
    ('다음 주행부터 미리 막기', '기준에 닿기 전에 소리·안내·환기 / 멀미 없이 끝난 주행으로는 너무 이른 경고를 줄입니다'),
]


def base():
    im = Image.new('RGB', (1280, 720))
    d = ImageDraw.Draw(im)
    for y in range(720):
        c = int(6 + (y / 720) * 10)
        d.line([(0, y), (1280, y)], fill=(c, c + 10, c + 30))
    return im


def draw(t):
    """t = 0..1 진행"""
    im = base()
    d = ImageDraw.Draw(im)
    title = '우리 아이 멀미를 배워서, 다음엔 미리 막습니다'
    d.text((60, 48), title, font=B(40), fill=WHITE)
    d.text((62, 104), '찰랑의 아이별 멀미 학습', font=R(22), fill=CYAN)
    # 단계 (왼쪽)
    for k, (head, sub) in enumerate(STEPS):
        appear = 0.08 + k * 0.2
        a = max(0.0, min(1.0, (t - appear) / 0.06))
        if a <= 0: continue
        y = 170 + k * 120
        col = lambda c: tuple(int(c[i] * a + 12 * (1 - a)) for i in range(3))
        d.ellipse([60, y, 112, y + 52], outline=col(CYAN), width=3)
        num = str(k + 1)
        w = d.textlength(num, font=B(28))
        d.text((86 - w / 2, y + 9), num, font=B(28), fill=col(CYAN))
        d.text((132, y + 2), head, font=B(30), fill=col(WHITE))
        # 긴 설명은 두 줄로
        words, line, lines = sub.split(' '), '', []
        for wd in words:
            if d.textlength(line + ' ' + wd, font=R(20)) > 520 and line:
                lines.append(line); line = wd
            else:
                line = (line + ' ' + wd).strip()
        lines.append(line)
        for j, ln in enumerate(lines[:2]):
            d.text((134, y + 44 + j * 26), ln, font=R(20), fill=col(DIM))
    # 그래프 (오른쪽): 멀미 누적, 처음엔 기준 없이 넘침 → 배운 뒤엔 기준 앞에서 미리 알림
    gx, gy, gw, gh = 760, 190, 460, 380
    d.rounded_rectangle([gx - 20, gy - 40, gx + gw + 20, gy + gh + 50], radius=16, outline=(40, 80, 130), width=2, fill=(8, 20, 40))
    d.text((gx, gy - 28), '멀미 누적', font=B(20), fill=WHITE)
    d.line([(gx, gy + gh), (gx + gw, gy + gh)], fill=(70, 100, 140), width=2)
    d.line([(gx, gy), (gx, gy + gh)], fill=(70, 100, 140), width=2)
    th = gy + gh * 0.35  # 민지 기준
    if t > 0.28:
        for x in range(gx, gx + gw, 14): d.line([(x, th), (x + 7, th)], fill=RED, width=2)
        d.text((gx + gw - 110, th - 28), '민지 기준', font=B(18), fill=RED)
    # 배우기 전(회색): 기준을 넘어 멀미 / 배운 뒤(파랑): 기준 앞에서 미리 알리고 환기 → 기준 아래에 머문다
    import math
    tt = min(1.0, max(0.0, (t - 0.05) / 0.85))
    rise = lambda u: 1.05 * (1 - math.exp(-2.2 * u)) + 0.03 * math.sin(16 * u)
    yv = lambda v: gy + gh - v * gh * 0.9
    lim = (gy + gh - th) / (gh * 0.9)        # 기준을 값으로
    warn_u = None
    before, after = [], []
    for i in range(int(200 * tt) + 1):
        u = i / 200
        before.append((gx + u * gw, yv(rise(u))))
        v = rise(u)
        if warn_u is None and v >= lim * 0.82: warn_u = u
        if warn_u is not None:
            v0 = rise(warn_u)
            v = v0 + 0.06 * (1 - math.exp(-(u - warn_u) * 6)) - 0.04 * (u - warn_u)
        after.append((gx + u * gw, yv(v)))
    if len(before) > 1: d.line(before, fill=(90, 105, 130), width=3)
    if len(after) > 1: d.line(after, fill=CYAN, width=4)
    # 배우기 전: 기준을 넘는 곳에 "멀미"
    for (x, y) in before:
        if y <= th:
            d.line([(x - 9, y - 9), (x + 9, y + 9)], fill=RED, width=4); d.line([(x - 9, y + 9), (x + 9, y - 9)], fill=RED, width=4)
            d.text((x - 20, y - 40), '멀미', font=B(18), fill=RED)
            break
    if warn_u is not None and t > 0.6:
        x, y = gx + warn_u * gw, yv(rise(warn_u))
        d.ellipse([x - 9, y - 9, x + 9, y + 9], fill=AMBER)
        d.text((x - 10, y + 16), '미리 알림·환기', font=B(18), fill=AMBER)
    # 범례
    d.line([(gx + 10, gy + gh + 30), (gx + 40, gy + gh + 30)], fill=(90, 105, 130), width=3)
    d.text((gx + 48, gy + gh + 19), '배우기 전', font=R(17), fill=DIM)
    d.line([(gx + 160, gy + gh + 30), (gx + 190, gy + gh + 30)], fill=CYAN, width=4)
    d.text((gx + 198, gy + gh + 19), '배운 뒤', font=R(17), fill=CYAN)
    return im


os.makedirs(f'{S}/learn', exist_ok=True)
N = int(SEC * FPS)
for i in range(N):
    draw(min(1.0, i / (N * 0.85))).save(f'{S}/learn/{i:05d}.png')
print('learn frames', N)

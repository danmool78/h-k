"""찍은 화면에 자막을 입히고 제목·분석·마무리 화면과 이어 붙여 mp4 로. python3 build.py 작업폴더 결과.mp4 [구간 나눔 json]"""
import json, os, subprocess, sys
from PIL import Image, ImageDraw, ImageFont

S = sys.argv[1]
OUT = sys.argv[2]
F = '/home/user/h-k/shared/src/commonMain/composeResources/font'
B = lambda n: ImageFont.truetype(f'{F}/nanum_gothic_bold.ttf', n)
FPS = 30
marks = dict(json.load(open(f'{S}/marks.json')))
split = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else {}

# (시작 프레임, 끝 프레임, 자막)
d0 = marks['drive']
s1 = d0 + split.get('drive2', 300)
s2 = d0 + split.get('drive3', 600)
CAPS = [
    (marks['main'], marks['seat'], '차 화면에 물컵 하나 — 차가 흔들리는 만큼 물이 출렁입니다'),
    (marks['seat'], marks['panel'], '아이가 뒷좌석에 타면 좌석 신호로 알아보고, 그 아이 기준으로 맞춥니다'),
    (marks['panel'], marks['cards'], '시연: 실제 도로 "대관령 넘어가는 길" 을 거칠게 달려 봅니다'),
    (marks['cards'], marks['brief'], '출발 전, 실제 도로와 지형으로 멀미 위험 구간을 찾아 알려 줍니다'),
    (marks['brief'], marks['drive'], '위험 구간을 3D 지형 위에서 미리 달려 봅니다 (정차 중에만)'),
    (d0, s1, '위험 구간 1km 앞부터 미리 알립니다'),
    (s1, s2, '굽이마다 물이 출렁이고 넘칩니다 — 넘친 만큼 아이 멀미가 쌓입니다'),
    (s2, marks['report'], '멀미가 쌓이면 차가 먼저 환기하고, 아이 쪽 창문을 열지 묻습니다'),
    (marks['report'], marks['end'], '도착하면 운전을 평가하고 "다음엔 이렇게" 를 알려 줍니다'),
]


def caption(im, text):
    im = im.convert('RGB')
    ov = Image.new('RGBA', im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    f = B(30)
    w = d.textlength(text, font=f)
    x0 = (im.width - w) / 2 - 24
    d.rounded_rectangle([x0, im.height - 86, x0 + w + 48, im.height - 30], radius=14, fill=(4, 12, 28, 210))
    d.text(((im.width - w) / 2, im.height - 78), text, font=f, fill=(240, 246, 255, 255))
    return Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')


os.makedirs(f'{S}/out', exist_ok=True)
for name in os.listdir(f'{S}/out'):
    os.remove(f'{S}/out/{name}')
n = 0


LAST = None


def emit(im):
    global n, LAST
    LAST = im
    im.save(f'{S}/out/{n:05d}.jpg', quality=92)
    n += 1


def fade(a, b, frames):
    for k in range(frames):
        emit(Image.blend(a, b, (k + 1) / frames))


title = Image.open(f'{S}/title.png').convert('RGB')
end = Image.open(f'{S}/end.png').convert('RGB')
black = Image.new('RGB', (1280, 720))
fade(black, title, 15)
for _ in range(FPS * 3): emit(title)

frames = sorted(os.listdir(f'{S}/f'))
SKIP = range(*split.get('skip', [0, 0]))  # 너무 긴 장면 덜어 내기
first = None
for i, name in enumerate(frames):
    if i in SKIP: continue
    text = next((t for a, b, t in CAPS if a <= i < b), None)
    im = Image.open(f'{S}/f/{name}')
    im = caption(im, text) if text else im.convert('RGB')
    if first is None:
        fade(title, im, 15)
        first = im
    emit(im)
last = im

# 상세 분석 (정차 중에 연 화면) 천천히 다가가기
trace = Image.open('/home/user/h-k/tools/contest_form/img/trace.png').convert('RGB').resize((1280, 732)).crop((0, 6, 1280, 726))
fade(last, caption(trace, '언제·왜 출렁였는지 상세 분석으로 다시 봅니다 (정차 중에만)'), 15)
for k in range(FPS * 4):
    z = 1 + 0.06 * k / (FPS * 4)
    w, h = int(1280 / z), int(720 / z)
    x, y = (1280 - w) // 2, (720 - h) // 2
    emit(caption(trace.crop((x, y, x + w, y + h)).resize((1280, 720), Image.LANCZOS), '언제·왜 출렁였는지 상세 분석으로 다시 봅니다 (정차 중에만)'))
fade(LAST, end, 20)
for _ in range(FPS * 5): emit(end)
fade(end, black, 20)

subprocess.run(['ffmpeg', '-y', '-v', 'error', '-framerate', str(FPS), '-i', f'{S}/out/%05d.jpg',
                '-c:v', 'libx264', '-preset', 'slow', '-crf', '20', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', OUT], check=True)
print('frames', n, 'sec', n / FPS, OUT)

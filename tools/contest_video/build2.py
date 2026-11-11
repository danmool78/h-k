"""찍은 장면 + 자막 + 내레이션 + 배경음악 → mp4. python3 build2.py 작업폴더 결과.mp4 split.json

장면마다 내레이션이 끝날 때까지 장면을 늘린다 (멈춘 장면은 앞뒤로 오가며 늘려 물이 계속 움직이게).
배경음악은 직접 합성한 잔잔한 곡(저작권 없음), 내레이션이 나올 때 자동으로 낮춘다.
"""
import json, os, subprocess, sys, wave
import numpy as np
from PIL import Image, ImageDraw, ImageFont

S, OUT = sys.argv[1], sys.argv[2]
split = json.load(open(sys.argv[3]))
F = '/home/user/h-k/shared/src/commonMain/composeResources/font'
FONT = ImageFont.truetype(f'{F}/nanum_gothic_bold.ttf', 30)
FPS = 30
marks = dict(json.load(open(f'{S}/marks.json')))
lines = dict(json.load(open(f'{S}/narration.json')))


def dur(name):
    out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f'{S}/voice/{name}.mp3'], capture_output=True, text=True).stdout
    return float(out)


def caption(im, text):
    im = im.convert('RGB')
    if not text: return im
    ov = Image.new('RGBA', im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    w = d.textlength(text, font=FONT)
    x0 = (im.width - w) / 2 - 24
    d.rounded_rectangle([x0, im.height - 86, x0 + w + 48, im.height - 30], radius=14, fill=(4, 12, 28, 210))
    d.text(((im.width - w) / 2, im.height - 78), text, font=FONT, fill=(240, 246, 255, 255))
    return Image.alpha_composite(im.convert('RGBA'), ov).convert('RGB')


os.makedirs(f'{S}/out', exist_ok=True)
for name in os.listdir(f'{S}/out'): os.remove(f'{S}/out/{name}')
n = 0
LAST = None
voice = []  # (시작 초, 이름)


def emit(im):
    global n, LAST
    LAST = im
    im.save(f'{S}/out/{n:05d}.jpg', quality=92)
    n += 1


def fade_to(b, frames=12):
    a = LAST
    for k in range(frames):
        emit(Image.blend(a, b, (k + 1) / frames))


def frame(i, src='f'): return Image.open(f'{S}/{src}/{i:05d}.png')


def stretch(idx, total):
    """장면 번호 목록을 total 장으로: 모자라면 앞뒤로 오가며 늘린다"""
    if len(idx) >= total: return idx
    out = list(idx)
    back = idx[::-1][1:-1] or idx
    seq = back + idx[1:]
    k = 0
    while len(out) < total:
        out.append(seq[k % len(seq)]); k += 1
    return out


def scene(idx, text, voice_name=None, pad=0.6, src='f'):
    """장면 하나: 내레이션이 있으면 그 길이 + pad 까지 늘린다. text 는 문자열 또는 (장면 번호 → 자막) 함수"""
    total = len(idx)
    if voice_name:
        voice.append((n / FPS, voice_name))
        total = max(total, int((dur(voice_name) + pad) * FPS))
    for i in stretch(idx, total):
        emit(caption(frame(i, src), text(i) if callable(text) else text))


def still(im, seconds, text=None, zoom=0.0, voice_name=None, pad=0.8):
    if voice_name:
        voice.append((n / FPS, voice_name))
        seconds = max(seconds, dur(voice_name) + pad)
    frames = int(seconds * FPS)
    for k in range(frames):
        z = 1 + zoom * k / frames
        w, h = int(1280 / z), int(720 / z)
        x, y = (1280 - w) // 2, (720 - h) // 2
        emit(caption(im.crop((x, y, x + w, y + h)).resize((1280, 720), Image.LANCZOS), text))


R = lambda a, b: list(range(a, b))
d0 = marks['drive']
s1, s2 = d0 + split['drive2'], d0 + split['drive3']
skip = set(range(*split.get('skip', [0, 0])))

title = Image.open(f'{S}/title.png').convert('RGB')
end = Image.open(f'{S}/end.png').convert('RGB')
LAST = Image.new('RGB', (1280, 720))
fade_to(title, 15)
still(title, 4, zoom=0.04, voice_name='title')
fade_to(caption(frame(marks['main']), '차 화면에 물컵 하나 — 차가 흔들리는 만큼 물이 출렁입니다'))
scene(R(marks['main'], marks['seat']), '차 화면에 물컵 하나 — 차가 흔들리는 만큼 물이 출렁입니다', 'main')
scene(R(marks['seat'] + 5, marks['panel']), '아이가 뒷좌석에 타면 좌석 신호로 알아보고, 그 아이 기준으로 맞춥니다', 'seat')
scene(R(marks['panel'], marks['cards']), '시연: 실제 도로 "대관령 넘어가는 길" 을 거칠게 달려 봅니다')
scene(R(marks['cards'], marks['brief']), '출발 전, 실제 도로와 지형으로 멀미 위험 구간을 찾아 알려 줍니다', 'cards')
scene(R(marks['brief'], d0), '위험 구간을 3D 지형 위에서 미리 달려 봅니다 (정차 중에만)', 'brief')
scene([i for i in R(d0, s1) if i not in skip], '위험 구간 1km 앞부터 미리 알립니다', 'drive1')
scene([i for i in R(s1, s2) if i not in skip], '굽이마다 물이 출렁이고 넘칩니다 — 넘친 만큼 아이 멀미가 쌓입니다', 'drive2')
scene([i for i in R(s2, marks['report']) if i not in skip], '멀미가 쌓이면 차가 먼저 환기하고, 아이 쪽 창문을 열지 묻습니다', 'drive3')
# 아이별 멀미 학습: 주행 중 "지금 멀미" (따로 찍은 장면) → 무엇을 배워 어떻게 미리 막는지
sick = sorted(int(x[:5]) for x in os.listdir(f'{S}/g'))
press = split.get('press', 75)
fade_to(caption(frame(sick[0], 'g'), '아이가 멀미하면 — "지금 멀미" 한 번'), 12)
scene(sick, lambda i: '아이가 멀미하면 — "지금 멀미" 한 번' if i < press + 10 else '그 순간이 민지의 멀미 기준으로 — 이제 이만큼 흔들리면 "위험"(주황)', 'sick', src='g')
learn = sorted(int(x[:5]) for x in os.listdir(f'{S}/learn'))
fade_to(frame(learn[0], 'learn').convert('RGB'), 15)
scene(learn, None, 'learn', pad=0.8, src='learn')
fade_to(caption(frame(marks['report']), '도착하면 운전을 평가하고 "다음엔 이렇게" 를 알려 줍니다'), 12)
scene(R(marks['report'], marks['end']), '도착하면 운전을 평가하고 "다음엔 이렇게" 를 알려 줍니다', 'report')
trace = Image.open('/home/user/h-k/tools/contest_form/img/trace.png').convert('RGB').resize((1280, 732)).crop((0, 6, 1280, 726))
cap = '언제·왜 출렁였는지 상세 분석으로 다시 봅니다 (정차 중에만)'
fade_to(caption(trace, cap), 15)
still(trace, 4, cap, zoom=0.06, voice_name='analysis')
fade_to(end, 20)
still(end, 5, voice_name='end', pad=1.5)
fade_to(Image.new('RGB', (1280, 720)), 20)
total = n / FPS
print('video', total, 's')

# ---- 배경음악: 잔잔한 패드 + 물방울 같은 아르페지오 (직접 합성) ----
SR = 44100
t = np.arange(int(total * SR)) / SR
mus = np.zeros_like(t)
hz = lambda m: 440 * 2 ** ((m - 69) / 12)
chords = [[48, 55, 59, 62, 64], [45, 52, 55, 59, 60], [41, 48, 52, 55, 57], [43, 50, 55, 59, 62]]  # Cmaj9 Am9 Fmaj7 G
BAR = 4.0
for b in range(int(total / BAR) + 1):
    ch = chords[b % 4]
    a, z = int(b * BAR * SR), min(len(t), int((b + 1) * BAR * SR + SR))
    if a >= len(t): break
    tt = t[a:z] - b * BAR
    env = np.minimum(1, tt / 1.2) * np.exp(-np.maximum(0, tt - BAR + 0.2) / 0.6)
    pad = sum(np.sin(2 * np.pi * hz(m) * tt) + 0.5 * np.sin(2 * np.pi * hz(m) * 1.003 * tt) for m in ch)
    mus[a:z] += 0.05 * env * pad
    # 아르페지오 (반 박자마다, 한 옥타브 위)
    for k in range(8):
        m = ch[[0, 2, 4, 3, 1, 3, 4, 2][k]] + 24
        s0 = a + int(k * 0.5 * SR)
        s1_ = min(len(t), s0 + int(1.6 * SR))
        if s0 >= len(t): break
        u = (np.arange(s1_ - s0)) / SR
        mus[s0:s1_] += 0.035 * np.exp(-u / 0.45) * (np.sin(2 * np.pi * hz(m) * u) + 0.3 * np.sin(2 * np.pi * hz(m) * 2 * u))
# 부드럽게 (한 극 저역 통과), 처음·끝 페이드
y = np.zeros_like(mus); k = 0.25
for i in range(1, len(mus)): y[i] = y[i - 1] + k * (mus[i] - y[i - 1])
fade = np.minimum(1, np.minimum(t / 2.0, (total - t) / 3.0))
y *= fade
y = y / (np.abs(y).max() + 1e-9) * 0.5
with wave.open(f'{S}/music.wav', 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((y * 32767).astype(np.int16).tobytes())

# ---- 섞기: 내레이션은 시작 시각만큼 늦춰 깔고, 음악은 내레이션이 나올 때 낮춤 ----
inputs = ['-i', f'{S}/music.wav']
filt = []
for k, (st, name) in enumerate(voice):
    inputs += ['-i', f'{S}/voice/{name}.mp3']
    ms = int(st * 1000) + 300
    filt.append(f'[{k + 1}:a]aresample=44100,adelay={ms}|{ms}[v{k}]')
vs = ''.join(f'[v{k}]' for k in range(len(voice)))
filt.append(f'{vs}amix=inputs={len(voice)}:normalize=0[vo]')
filt.append('[vo]asplit=2[vo1][vo2]')
filt.append('[0:a]volume=0.35[m]')
filt.append('[m][vo1]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=400[md]')
filt.append('[md][vo2]amix=inputs=2:normalize=0,volume=1.25[a]')
subprocess.run(['ffmpeg', '-y', '-v', 'error'] + inputs + ['-filter_complex', ';'.join(filt), '-map', '[a]', '-ac', '2', f'{S}/audio.wav'], check=True)
subprocess.run(['ffmpeg', '-y', '-v', 'error', '-framerate', str(FPS), '-i', f'{S}/out/%05d.jpg', '-i', f'{S}/audio.wav',
                '-c:v', 'libx264', '-preset', 'slow', '-crf', '20', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '160k',
                '-shortest', '-movflags', '+faststart', OUT], check=True)
print('done', OUT, [(round(s, 1), v) for s, v in voice])

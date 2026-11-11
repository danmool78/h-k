"""제출용: 01 참가신청서·04 성명 채우기. 개인정보는 저장소 밖 JSON 에서 읽고, 결과도 저장소 밖에 저장한다 (저장소는 공개).

python3 tools/contest_form/personal.py 입력.docx 개인정보.json 결과.docx

JSON 예: {"name": "...", "unit": "개인", "track": "일반", "role": "...", "org": "...", "email": "...", "phone": "...", "people": 1, "date": [2026, 11, 11]}
동의 체크·서명·서명일자는 본인이 직접 (채우지 않음).
"""
import sys, json
import docx

src, info, out = sys.argv[1], sys.argv[2], sys.argv[3]
P = json.load(open(info, encoding='utf-8'))
d = docx.Document(src)


def put(p, text):
    runs = p.runs
    runs[0].text = text
    for r in runs[1:]: r.text = ''


def check(label, options, chosen):
    return f"{label}: " + "    ".join(("☑ " if o == chosen else "□ ") + o for o in options)


for p in d.paragraphs:
    t = p.text.strip()
    if t.startswith('참가 단위:'):
        put(p, check('참가 단위', ['개인', '팀'], P['unit']))
    elif t.startswith('참가 부문:'):
        put(p, check('참가 부문', ['대학(원)생', '일반(스타트업 포함)'], '일반(스타트업 포함)' if P['track'] == '일반' else '대학(원)생'))
    elif t.startswith('팀명(개인 참가 시 성명)'):
        put(p, f"팀명(개인 참가 시 성명): {P['name']}")
    elif t.startswith('대표자 성명:'):
        put(p, f"대표자 성명: {P['name']}      역할: {P['role']}")
    elif t.startswith('대표자 소속'):
        put(p, f"대표자 소속(학교/학과/회사명/무소속 등): {P['org']}")
    elif t.startswith('대표자 이메일:'):
        put(p, f"대표자 이메일: {P['email']}")
    elif t.startswith('대표자 휴대폰번호:'):
        put(p, f"대표자 휴대폰번호: {P['phone']}")
    elif t.startswith('참가 인원:'):
        put(p, f"참가 인원: {P['people']}명")
    elif t.startswith('작성일:'):
        y, m, dd = P['date']
        put(p, f"작성일: {y}년 {m}월 {dd}일")

# 01-B 참가 구성원 첫 줄
row = d.tables[0].rows[1].cells
from docx.enum.table import WD_ALIGN_VERTICAL
from docx.enum.text import WD_ALIGN_PARAGRAPH
for c, v in zip(row, [P['name'], P['org'], P['phone'], P['email'], P['role']]):
    c.paragraphs[0].text = v
    c.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.CENTER
    c.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
# 04 대표자 성명
c = d.tables[9].rows[1].cells[1]
c.paragraphs[0].text = P['name']
c.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.CENTER
c.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
d.save(out)
print('saved', out)

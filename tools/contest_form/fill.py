"""접수양식 02 장 채우기: 소제목·큰 줄·강조·표·그림·스토리보드. python3 fill.py content.py 원본.docx 결과.docx 그림폴더

양식의 칸마다 운영사 태그가 붙은 입력 필드(content control)가 있다. 내용은 그 필드 안에 넣는다
(글만 받는 필드를 표·그림도 담는 필드로 바꿔 감싼다. 태그·이름은 그대로). 칸은 쪽에 걸쳐 나뉘게 한다 (빈 쪽 방지).
"""
import sys, os, copy, importlib.util
import docx
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

spec = importlib.util.spec_from_file_location("content", sys.argv[1]); C = importlib.util.module_from_spec(spec); spec.loader.exec_module(C)
src, out, imgdir = sys.argv[2], sys.argv[3], sys.argv[4]
d = docx.Document(src)

BLUE = RGBColor(0x12, 0x50, 0xB0)
NAVY = RGBColor(0x0B, 0x24, 0x4A)
GREY = RGBColor(0x55, 0x60, 0x70)
BODY = Pt(10)


def shade(el, fill):
    pr = el.get_or_add_tcPr() if el.tag.endswith('}tc') else el.get_or_add_pPr()
    shd = OxmlElement('w:shd'); shd.set(qn('w:val'), 'clear'); shd.set(qn('w:color'), 'auto'); shd.set(qn('w:fill'), fill)
    pr.append(shd)


def left_bar(p, color='2A86F0'):
    pPr = p._p.get_or_add_pPr()
    bdr = OxmlElement('w:pBdr')
    l = OxmlElement('w:left'); l.set(qn('w:val'), 'single'); l.set(qn('w:sz'), '24'); l.set(qn('w:space'), '6'); l.set(qn('w:color'), color)
    bdr.append(l); pPr.append(bdr)


def set_line(p, text):
    full = p.text
    label = full.split(':')[0]
    tail = '\n' if full.endswith('\n') else ''
    runs = p.runs
    runs[0].text = f"{label}: {text}" + tail
    for r in runs[1:]: r.text = ''


for p in d.paragraphs:
    t = p.text.strip()
    if t.startswith('앱명') and '____' in t:
        set_line(p, C.APP)


TABLE_CM = 16.8  # 칸 안쪽 너비(약 17.1cm)에 맞춘 표 너비


def set_widths(t, widths):
    t.autofit = False
    tblPr = t._tbl.tblPr
    w = tblPr.find(qn('w:tblW'))
    if w is None:
        w = OxmlElement('w:tblW'); tblPr.append(w)
    w.set(qn('w:w'), str(int(sum(widths) * 567))); w.set(qn('w:type'), 'dxa')
    lay = OxmlElement('w:tblLayout'); lay.set(qn('w:type'), 'fixed'); tblPr.append(lay)
    for j, gc in enumerate(t._tbl.tblGrid.findall(qn('w:gridCol'))):
        gc.set(qn('w:w'), str(int(widths[j] * 567)))
    for row in t.rows:
        for j, c in enumerate(row.cells):
            c.width = Cm(widths[j])


def auto_widths(rows):
    """열마다 글 길이(평균, 제곱근으로 완만하게)에 비례, 한 열 최소 2.2cm"""
    n = len(rows[0])
    weight = [max(1.0, (sum(len(r[j]) for r in rows) / len(rows)) ** 0.5) for j in range(n)]
    tot = sum(weight)
    w = [max(2.2, TABLE_CM * x / tot) for x in weight]
    k = TABLE_CM / sum(w)
    return [x * k for x in w]


def add_table(cell, rows):
    t = cell.add_table(rows=len(rows), cols=len(rows[0]))
    t.style = 'Table Grid'
    for i, row in enumerate(rows):
        for j, v in enumerate(row):
            c = t.cell(i, j)
            c.paragraphs[0].text = ''
            r = c.paragraphs[0].add_run(v)
            r.font.size = Pt(9)
            if i == 0:
                r.bold = True; r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF); shade(c._tc, '1250B0')
            elif j == 0:
                r.bold = True; shade(c._tc, 'EAF2FD')
    # 비교표: 마지막 "찰랑" 열을 넓히고 강조
    if rows[0][-1] == '찰랑':
        set_widths(t, [2.2] + [3.0] * (len(rows[0]) - 2) + [5.6])
        for i in range(1, len(rows)):
            c = t.cell(i, len(rows[0]) - 1)
            shade(c._tc, 'D6E8FF')
            for r in c.paragraphs[0].runs: r.bold = True; r.font.color.rgb = NAVY
    else:
        set_widths(t, auto_widths(rows))
    # 표 뒤 간격
    cell.add_paragraph().paragraph_format.space_after = Pt(0)


def prepare(cell):
    """칸의 글 필드를 떼어 내고(설정은 돌려줌), 칸이 쪽에 걸쳐 나뉘게, 첫 줄의 고정 줄 간격·노란 강조를 없앤다"""
    tc = cell._tc
    first = cell.paragraphs[0]
    sdt = first._p.find(qn('w:sdt'))
    pr = None
    if sdt is not None:
        pr = copy.deepcopy(sdt.find(qn('w:sdtPr')))
        first._p.remove(sdt)
        for k in ('w:text', 'w:rPr', 'w:showingPlcHdr'):
            el = pr.find(qn(k))
            if el is not None: pr.remove(el)
    pPr = first._p.find(qn('w:pPr'))
    if pPr is not None:
        for k in ('w:spacing', 'w:rPr'):
            el = pPr.find(qn(k))
            if el is not None: pPr.remove(el)
    trPr = tc.getparent().find(qn('w:trPr'))
    if trPr is not None:
        for el in trPr.findall(qn('w:cantSplit')): trPr.remove(el)
    return pr


def wrap(cell, pr):
    """칸 안 내용 전체를 표·그림도 담는 입력 필드(같은 태그)로 감싼다. 칸 끝에는 빈 줄 하나 (Word 규칙)"""
    if pr is None: return
    tc = cell._tc
    sdt = OxmlElement('w:sdt'); sdt.append(pr)
    content = OxmlElement('w:sdtContent')
    for el in list(tc):
        if el.tag in (qn('w:p'), qn('w:tbl')):
            tc.remove(el); content.append(el)
    sdt.append(content); tc.append(sdt)
    tc.append(OxmlElement('w:p'))


def storyboard(cell, rows):
    """그림 | 제목·설명 두 칸 표"""
    t = cell.add_table(rows=len(rows), cols=2)
    t.style = 'Table Grid'
    set_widths(t, (7.6, TABLE_CM - 7.6))
    for i, (name, title, desc) in enumerate(rows):
        a, b = t.cell(i, 0), t.cell(i, 1)
        a.paragraphs[0].add_run().add_picture(os.path.join(imgdir, name), width=Cm(7.0))
        r = b.paragraphs[0].add_run(title); r.bold = True; r.font.size = Pt(10.5); r.font.color.rgb = NAVY
        q = b.add_paragraph(); q.paragraph_format.space_before = Pt(3)
        r = q.add_run(desc); r.font.size = Pt(9.5)
        shade(b._tc, 'F4F8FE')
    cell.add_paragraph().paragraph_format.space_after = Pt(0)


def fill(cell, text):
    first = cell.paragraphs[0]
    for p in cell.paragraphs[1:]:
        p._element.getparent().remove(p._element)
    used_first = False

    def para():
        nonlocal used_first
        if not used_first:
            used_first = True
            return first
        return cell.add_paragraph()

    lines = text.strip().split('\n')
    k = 0
    while k < len(lines):
        line = lines[k]
        if line.startswith('|'):
            rows = []
            while k < len(lines) and lines[k].startswith('|'):
                rows.append([c.strip() for c in lines[k].strip().strip('|').split('|')])
                k += 1
            if not used_first:
                used_first = True
            add_table(cell, rows)
            continue
        if line.startswith('[[sb:'):
            rows = []
            while k < len(lines) and lines[k].startswith('[[sb:'):
                rows.append(lines[k][5:-2].split('|', 2))
                k += 1
            used_first = True
            storyboard(cell, rows)
            continue
        p = para()
        p.paragraph_format.space_after = Pt(3)
        if line.startswith('[[cap:'):
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            r = p.add_run(line[6:-2]); r.font.size = Pt(8.5); r.italic = True; r.font.color.rgb = GREY
            p.paragraph_format.space_after = Pt(6)
        elif line.startswith('[[img:'):
            name, cap = line[6:-2].split('|', 1)
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            p.paragraph_format.space_before = Pt(4)
            p.add_run().add_picture(os.path.join(imgdir, name), width=Cm(15.0))
            p.paragraph_format.keep_with_next = True
            c = cell.add_paragraph(); c.alignment = WD_ALIGN_PARAGRAPH.CENTER
            r = c.add_run(cap); r.font.size = Pt(8.5); r.italic = True; r.font.color.rgb = GREY
            c.paragraph_format.space_after = Pt(6)
        elif line.startswith('Q. '):
            r = p.add_run(line); r.bold = True; r.font.size = BODY; r.font.color.rgb = NAVY
            p.paragraph_format.space_before = Pt(5); p.paragraph_format.space_after = Pt(1)
            p.paragraph_format.keep_with_next = True
        elif line.startswith('A. '):
            p.paragraph_format.left_indent = Pt(12)
            r = p.add_run(line); r.font.size = BODY
        elif line.startswith('## '):
            r = p.add_run(line[3:]); r.bold = True; r.font.size = Pt(13); r.font.color.rgb = NAVY
            p.paragraph_format.space_after = Pt(5)
        elif line.startswith('# '):
            r = p.add_run(line[2:]); r.bold = True; r.font.size = Pt(10.5); r.font.color.rgb = BLUE
            if k > 0: p.paragraph_format.space_before = Pt(7)
            # 다음 줄이 글이면 붙여 둔다 (표·그림 앞에서는 붙이지 않음: 통째로 다음 쪽으로 밀려 빈 쪽이 생긴다)
            nxt = lines[k + 1] if k + 1 < len(lines) else ''
            p.paragraph_format.keep_with_next = nxt.startswith('[[sb:') or not nxt.startswith(('|', '[['))
        elif line.startswith('> '):
            left_bar(p); shade(p._p, 'EAF2FD')
            r = p.add_run(line[2:]); r.bold = True; r.font.size = Pt(11); r.font.color.rgb = NAVY
            p.paragraph_format.space_before = Pt(3); p.paragraph_format.space_after = Pt(6)
        elif line.strip().startswith('- '):
            ind = (len(line) - len(line.lstrip())) // 2
            p.paragraph_format.left_indent = Pt(10 + 12 * ind)
            p.paragraph_format.first_line_indent = Pt(-8)
            r = p.add_run(('· ' if ind == 0 else '– ') + line.strip()[2:]); r.font.size = BODY
        else:
            r = p.add_run(line); r.font.size = BODY
        k += 1


for i, text in enumerate(C.ITEMS, start=1):
    cell = d.tables[i].cell(0, 0)
    pr = prepare(cell)
    fill(cell, text)
    wrap(cell, pr)
d.save(out)
print('saved', out)

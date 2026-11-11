"""접수양식 02 장 채우기: 소제목·큰 줄·강조·표·그림. python3 fill2.py content.py 원본.docx 결과.docx 그림폴더"""
import sys, os, importlib.util
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
        widths = [2.2] + [3.0] * (len(rows[0]) - 2) + [5.6]
        t.autofit = False
        for j, gc in enumerate(t._tbl.tblGrid.findall(qn('w:gridCol'))):
            gc.set(qn('w:w'), str(int(widths[j] * 567)))
        for row in t.rows:
            for j, c in enumerate(row.cells):
                c.width = Cm(widths[j])
        for i in range(1, len(rows)):
            c = t.cell(i, len(rows[0]) - 1)
            shade(c._tc, 'D6E8FF')
            for r in c.paragraphs[0].runs: r.bold = True; r.font.color.rgb = NAVY
    # 표 뒤 간격
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
        p = para()
        p.paragraph_format.space_after = Pt(3)
        if line.startswith('[[img:'):
            name, cap = line[6:-2].split('|', 1)
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            p.paragraph_format.space_before = Pt(4)
            p.add_run().add_picture(os.path.join(imgdir, name), width=Cm(15.0))
            c = cell.add_paragraph(); c.alignment = WD_ALIGN_PARAGRAPH.CENTER
            r = c.add_run(cap); r.font.size = Pt(8.5); r.italic = True; r.font.color.rgb = GREY
            c.paragraph_format.space_after = Pt(6)
        elif line.startswith('Q. '):
            r = p.add_run(line); r.bold = True; r.font.size = BODY; r.font.color.rgb = NAVY
            p.paragraph_format.space_before = Pt(5); p.paragraph_format.space_after = Pt(1)
        elif line.startswith('A. '):
            p.paragraph_format.left_indent = Pt(12)
            r = p.add_run(line); r.font.size = BODY
        elif line.startswith('## '):
            r = p.add_run(line[3:]); r.bold = True; r.font.size = Pt(13); r.font.color.rgb = NAVY
            p.paragraph_format.space_after = Pt(5)
        elif line.startswith('# '):
            r = p.add_run(line[2:]); r.bold = True; r.font.size = Pt(10.5); r.font.color.rgb = BLUE
            if k > 0: p.paragraph_format.space_before = Pt(7)
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
    fill(d.tables[i].cell(0, 0), text)
d.save(out)
print('saved', out)

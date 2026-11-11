# 공모전 접수양식 만들기

`content.py` (02 장 1~8번 내용과 예상 질문) 를 고치고:

```
python3 tools/contest_form/fill.py tools/contest_form/content.py docs/contest-application-consent.docx docs/찰랑_공모전_접수양식.docx tools/contest_form/img
```

- 문법: `# ` 소제목, `## ` 큰 줄, `> ` 강조, `- ` 항목, `| a | b |` 표, `[[img:파일|설명]]` 그림, `[[sb:파일|제목|설명]]` 그림+설명 표(이어 쓰면 한 표), `[[cap:설명]]` 그림 설명 줄, `Q. `/`A. ` 질문·답
- 내용은 각 칸의 운영사 입력 필드(태그) 안에 들어간다
- 그림은 웹 앱(https://h-y-server.web.app/app/) 화면 캡처: 시연 대관령·거칠게 (hero·strip 주행, brief 3D 지형, report 도착 평가), 주말 나들이 휴게소에서 연 분석(trace)
- 01·03·04 장 개인정보·서명은 비워 둔다 (직접 작성)

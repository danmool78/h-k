# 시연 영상 만들기

시험 브라우저는 3D 물을 초당 몇 장밖에 못 그리므로, 앱 시계를 1/30초씩 직접 돌리며 한 장씩 찍어 이어 붙인다 (결과는 매끄러운 30fps).

1. 촬영 (약 20분): `D=작업폴더 node tools/contest_video/rec.js` — 배포된 웹 앱(https://h-y-server.web.app/app/)에서
   첫 화면 → 뒷좌석 왼쪽 탑승 → 시연(대관령·거칠게) → 위험 구간 카드 → 3D 미리보기 → 출발 → 시연 끝(도착 평가).
   `작업폴더/f/` 에 장면, `marks.json` 에 장면 경계.
   - 시계는 페이지가 뜨기 전에 넘겨받아야 앱 계산(물리·주행)도 같은 시계를 쓴다. 로딩 동안은 그냥 흐르게 두고 촬영 때 멈춘다.
2. 조립: 작업폴더에 `title.png`·`end.png` 를 두고 `python3 tools/contest_video/build.py 작업폴더 결과.mp4 tools/contest_video/split.json`
   - `split.json`: 주행 장면 자막 경계(주행 시작부터 장면 수: 위험 구간 진입, 창문 질문)와 덜어 낼 구간(`skip`)
   - 자막·제목·상세 분석(tools/contest_form/img/trace.png)·마무리 화면을 붙인다

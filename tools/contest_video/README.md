# 시연 영상 만들기

시험 브라우저는 3D 물을 초당 몇 장밖에 못 그리므로, 앱 시계를 1/30초씩 직접 돌리며 한 장씩 찍어 이어 붙인다 (결과는 매끄러운 30fps).

1. 촬영 (약 20분): `D=작업폴더 node tools/contest_video/rec.js` — 배포된 웹 앱(https://h-y-server.web.app/app/)에서
   첫 화면 → 뒷좌석 왼쪽 탑승 → 시연(대관령·거칠게) → 위험 구간 카드 → 3D 미리보기 → 출발 → 시연 끝(도착 평가).
   `작업폴더/f/` 에 장면, `marks.json` 에 장면 경계.
   - 시계는 페이지가 뜨기 전에 넘겨받아야 앱 계산(물리·주행)도 같은 시계를 쓴다. 로딩 동안은 그냥 흐르게 두고 촬영 때 멈춘다.
   - 아이별 멀미 학습 장면: `D=작업폴더 node tools/contest_video/rec_sick.js` (주행 중 "지금 멀미" 를 누르는 장면, `작업폴더/g/`),
     설명 화면은 `python3 tools/contest_video/learn.py 작업폴더 초` (`작업폴더/learn/`, 초 = learn 대사 길이 + 1)
   - 목적지 → 자료 내려받기 → 3D 구성 → 분석 장면: 대관령 일대 고도 타일(줌 13, x 7024~7028, y 3167~3169)을
     `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/13/x/y.png` 에서 `t_x_y.png` 로 받아 두고 `python3 tools/contest_video/pipeline.py 작업폴더 타일폴더 초` (`작업폴더/pipe/`)
2. 내레이션: `narration.json` 의 장면별 대사를 `python3 tools/contest_video/tts.py "대사" 작업폴더/voice/장면.mp3` 로 (Microsoft Edge 신경망 음성 ko-KR-SunHiNeural, 인터넷 필요)
3. 조립: 작업폴더에 `title.png`·`end.png`·`narration.json` 을 두고 `python3 tools/contest_video/build2.py 작업폴더 결과.mp4 tools/contest_video/split.json`
   - 장면마다 내레이션이 끝날 때까지 장면을 늘린다 (멈춘 장면은 앞뒤로 오가며)
   - 자막·제목·상세 분석(tools/contest_form/img/trace.png)·마무리 화면, 배경음악(대사가 나오면 자동으로 낮춤)
   - 배경음악: `MUSIC=음원.mp3 MUSIC_VOL=0.3` — 제출본은 Kevin MacLeod "Inspired" (incompetech.com, CC BY 4.0, 출처는 마무리 화면에 표기). MUSIC 이 없으면 직접 합성한 곡
   - `split.json`: 주행 장면 자막 경계(주행 시작부터 장면 수: 위험 구간 진입, 창문 질문)와 덜어 낼 구간(`skip`)

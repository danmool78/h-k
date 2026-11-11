// 앱 시계를 1/30초씩 돌리며 한 장씩 찍는다 (시험 브라우저가 느려도 결과는 매끄러운 30fps)
const { chromium } = require('playwright');
const fs = require('fs');
const D = process.env.D;
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1280, height: 800 } });
  // 앱은 진짜 시간으로 띄우고, 다 뜬 뒤에 시계를 넘겨받는다 (가짜 시계로 로딩하면 아주 느림)
  // 시계는 페이지가 뜨기 전에 넘겨받고(앱 계산도 같은 시계), 로딩 동안은 그냥 흐르게 둔 뒤 촬영 때 멈춘다
  await p.clock.install();
  await p.goto('https://h-y-server.web.app/app/');
  await p.waitForTimeout(15000);
  await p.clock.pauseAt(await p.evaluate(() => Date.now() + 500));
  console.log('ready');
  let n = 0;
  const marks = [];
  const mark = (name) => { marks.push([name, n]); console.log(name, n); };
  const cap = async (frames) => {
    for (let k = 0; k < frames; k++) {
      await p.clock.runFor(1000 / 30);
      await p.screenshot({ path: `${D}/f/${String(n).padStart(5, '0')}.png`, clip: { x: 0, y: 6, width: 1280, height: 720 }, timeout: 120000 });
      n++;
    }
  };
  // 시계는 조금만 흘리고 실제로 기다림 (네트워크·분석)
  const settle = async (sec) => { for (let i = 0; i < sec * 5; i++) { await p.clock.runFor(20); await p.waitForTimeout(200); } };
  mark('main'); await cap(75);
  await p.mouse.click(270, 770); mark('seat'); await cap(60);
  await p.mouse.click(1157, 681); await settle(1); mark('panel'); await cap(45);
  await p.mouse.click(509, 547); await settle(6); mark('cards'); await cap(90);
  await p.mouse.click(130, 598); await settle(5); mark('brief'); await cap(270);
  await p.mouse.click(75, 53); await settle(1);
  await p.mouse.click(1202, 56); mark('drive'); await cap(1350);
  await p.mouse.click(1206, 682); mark('report'); await cap(150);
  mark('end');
  fs.writeFileSync(`${D}/marks.json`, JSON.stringify(marks));
  await b.close();
})();

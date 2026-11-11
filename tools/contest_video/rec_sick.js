// 주행 중 "지금 멀미" 를 누르는 장면만 따로 찍는다
const { chromium } = require('playwright');
const D = process.env.D;
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1280, height: 800 } });
  await p.clock.install();
  await p.goto('https://h-y-server.web.app/app/');
  await p.waitForTimeout(15000);
  await p.mouse.click(270, 770); await p.waitForTimeout(1500);
  await p.mouse.click(1157, 681); await p.waitForTimeout(2500);
  await p.mouse.click(509, 547); await p.waitForTimeout(7000);
  await p.mouse.click(1202, 56); await p.waitForTimeout(1000);
  await p.clock.pauseAt(await p.evaluate(() => Date.now() + 500));
  console.log('driving');
  // 위험 구간 안으로 (찍지 않고 시계만): 약 40초
  for (let i = 0; i < 80; i++) await p.clock.runFor(500);
  console.log('in zone');
  let n = 0;
  const cap = async (frames) => { for (let k = 0; k < frames; k++) { await p.clock.runFor(1000 / 30); await p.screenshot({ path: `${D}/g/${String(n++).padStart(5, '0')}.png`, clip: { x: 0, y: 6, width: 1280, height: 720 }, timeout: 120000 }); } };
  await cap(75);
  await p.mouse.click(1150, 616);   // 지금 멀미
  console.log('press', n);
  await cap(180);
  console.log('done', n);
  await b.close();
})();

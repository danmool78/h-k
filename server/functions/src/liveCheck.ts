/**
 * Firebase 없이 실제 공공데이터 API로 수집 → 조회 흐름을 점검.
 *   DATA_GO_KR_KEY=... node lib/liveCheck.js [zcode=50] [lat] [lng]
 */
import { applyStatus, buildStations, nearby } from "./chargers";
import { fetchChargerInfo, fetchChargerStatus } from "./evApi";

async function main() {
  const key = process.env.DATA_GO_KR_KEY;
  if (!key) throw new Error("DATA_GO_KR_KEY 환경변수 필요");
  const [zcode = "50", lat = "33.2461812", lng = "126.4143365"] = process.argv.slice(2);

  let t = Date.now();
  const info = await fetchChargerInfo(zcode, key);
  const { stations, statuses } = buildStations(info);
  console.log(`getChargerInfo zcode=${zcode}: ${info.length}대 → 급속 충전소 ${Object.keys(stations).length}곳 / ${Object.keys(statuses).length}대 (${Date.now() - t}ms)`);

  t = Date.now();
  const changes = await fetchChargerStatus(10, key);
  const n = applyStatus(statuses, stations, changes);
  console.log(`getChargerStatus 10분: 전국 ${changes.length}건 중 이 지역 급속 ${n}건 반영 (${Date.now() - t}ms)`);

  const views = nearby(stations, statuses, Number(lat), Number(lng), 10, 5, Date.now());
  console.log(`\n(${lat}, ${lng}) 반경 10km 급속 충전소:`);
  for (const v of views) {
    const wait = v.waitMin == null ? "판단불가" : `${v.waitMin}분`;
    console.log(`  ${v.distanceKm}km ${v.name} | 최대 ${v.maxKw}kW | 빈 ${v.available}/${v.total}, 충전중 ${v.charging} | 예상대기 ${wait}`);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

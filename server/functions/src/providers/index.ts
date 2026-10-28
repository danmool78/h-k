/**
 * 사용할 충전소 데이터 출처 목록. 나라를 추가하려면 [ChargerProvider] 를 구현해 여기에 넣는다.
 * 후보와 붙이는 방법은 providers/README.md 참고.
 */
import { krKeco } from "./krKeco";
import { ChargerProvider } from "./types";

export const PROVIDERS: ChargerProvider[] = [
  krKeco,
];

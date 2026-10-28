/**
 * 나라별 충전소 데이터 출처(Provider) 공통 규격.
 *
 * 내부 형식은 충전 업계 표준 OCPI 2.2 의 Location / EVSE / Connector 를 단순화한 것이다.
 * 새 나라(또는 로밍 플랫폼, 현대·기아 충전 서비스)를 붙이려면 [ChargerProvider] 하나만 구현하면 된다.
 * 앱과 조회 API는 출처를 몰라도 된다.
 */

/** OCPI EVSE 상태의 부분집합. */
export type EvseStatus = "AVAILABLE" | "CHARGING" | "OUTOFORDER" | "INOPERATIVE" | "UNKNOWN";

export interface Evse {
  /** 대표 커넥터 규격 (OCPI ConnectorType 이름 또는 출처 원본 코드) */
  standard: string;
  /** 지원하는 커넥터 전부 (예: ["CHADEMO", "CCS1"]). 차와 맞는 충전기만 셀 때 쓴다 */
  connectors?: string[];
  /** 최대 출력 kW */
  kw: number;
}

/** 충전소 (OCPI Location). [id] 는 "국가:출처내ID" 로 전 세계에서 유일. */
export interface Station {
  id: string;
  provider: string;
  /** ISO 3166-1 alpha-2 */
  country: string;
  name: string;
  addr: string;
  lat: number;
  lng: number;
  operator: string;
  /** 경로에서 바로 들어가는 곳 (고속도로 휴게소 등) → 우회 시간 짧음 */
  highway: boolean;
  /** 출처 내부의 지역 구분 (부분 실패 시 그 지역만 이전 데이터 유지하는 데 사용) */
  region: string;
  /** evseId → 사양 */
  chargers: Record<string, Evse>;
}

/** 충전기 하나의 동적 상태. key = `${stationId}:${evseId}` */
export interface EvseState {
  status: EvseStatus;
  /** 상태 갱신 시각 (epoch ms) */
  updatedAt: number;
  /** 지금 충전 중이면 충전 시작 시각 (epoch ms) */
  sessionStartMs: number | null;
}

export type StationMap = Record<string, Station>;
export type StatusMap = Record<string, EvseState>;

export interface StatusUpdate {
  stationId: string;
  evseId: string;
  state: EvseState;
}

export interface StationSnapshot {
  stations: StationMap;
  statuses: StatusMap;
  /** 조회에 실패해 이전 데이터를 유지한 지역 */
  failedRegions: string[];
}

export interface ChargerProvider {
  /** 출처 식별자, 예: "kr-keco" */
  id: string;
  country: string;
  /** 사람이 읽는 이름 (관리자 페이지 표시) */
  label: string;

  /**
   * 충전소 전체 목록 (하루 1회 정도). [previous] 는 이 출처의 이전 목록 — 일부 지역 실패 시 유지용.
   * 급속 충전기만 돌려준다.
   */
  fetchStations(previous: StationMap): Promise<StationSnapshot>;

  /** 최근 상태 변경분 (5분마다). 실시간 상태가 없는 출처는 빈 배열. */
  fetchStatusChanges(): Promise<StatusUpdate[]>;
}

export const statusKey = (stationId: string, evseId: string) => `${stationId}:${evseId}`;

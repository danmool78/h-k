"""
EV 몬테카를로 프로토타입
  1) 목적지 도달 확률 (주행가능거리 "확률 게이지")
  2) 충전 경유 계획 (충전소별 대기시간까지 반영, 총소요시간 + 안전마진 균형)

의존성: numpy  (pip install numpy)
모든 수치는 예시 값입니다. 실제 차량/주행 로그로 파라미터를 피팅하세요.
"""
from dataclasses import dataclass
import numpy as np

rng = np.random.default_rng(42)
N = 20_000  # 샘플 수. 차량 내장 시 1,000~2,000으로도 충분한 경우가 많음


# ---------------------------------------------------------------
# 차량/환경 모델
# ---------------------------------------------------------------
@dataclass
class Vehicle:
    battery_kwh: float = 77.0          # 공칭 용량
    soh_mean: float = 0.95             # 배터리 건강도 평균
    soh_std: float = 0.015
    base_wh_per_km: float = 150.0      # 기준 전비 (20°C, 평지, 90km/h)
    reserve_soc: float = 0.10          # 도착 시 최소 허용 SOC


def sample_consumption(v: Vehicle, dist_km, temp_c, ascent_m, n=N):
    """구간 에너지 소비(kWh) 분포를 샘플링."""
    # 전비 변동: 운전 스타일/교통 -> 로그정규 (평균 1.0 부근)
    style = rng.lognormal(mean=0.0, sigma=0.10, size=n)

    # 기온 영향: 저온일수록 히터/내부저항으로 증가. 불확실성도 포함
    cold = np.clip(20.0 - temp_c, 0, None)
    temp_factor = 1.0 + 0.012 * cold + rng.normal(0, 0.03, size=n)

    # 경사 영향: 누적 상승고도의 위치에너지 (회생으로 일부 회수)
    regen = rng.uniform(0.5, 0.75, size=n)
    climb_kwh = (1900 * 9.81 * ascent_m / 3.6e6) * (1.0 - regen * 0.5)  # 차량 1.9t 가정

    flat_kwh = v.base_wh_per_km * dist_km / 1000.0 * style * temp_factor
    return flat_kwh + climb_kwh


def sample_usable_kwh(v: Vehicle, soc0, n=N):
    soh = np.clip(rng.normal(v.soh_mean, v.soh_std, size=n), 0.6, 1.0)
    return v.battery_kwh * soh * soc0


# ---------------------------------------------------------------
# 1) 도달 확률
# ---------------------------------------------------------------
def arrival_probability(v, soc0, dist_km, temp_c, ascent_m):
    avail = sample_usable_kwh(v, soc0)
    need = sample_consumption(v, dist_km, temp_c, ascent_m)
    soh_cap = v.battery_kwh * v.soh_mean
    remain_soc = (avail - need) / soh_cap
    p = float(np.mean(remain_soc >= v.reserve_soc))
    p10, p50, p90 = np.percentile(remain_soc, [10, 50, 90])
    return p, (p10, p50, p90)


# ---------------------------------------------------------------
# 2) 충전 경유 계획
# ---------------------------------------------------------------
@dataclass
class Charger:
    name: str
    at_km: float            # 출발지로부터 거리
    power_kw: float         # 평균 충전 출력
    wait_mean_min: float    # 대기시간 평균
    wait_sigma: float = 0.6 # 대기시간 로그정규 시그마


def trip_time_with_stop(v, soc0, total_km, temp_c, ascent_per_km, speed_kmh,
                        charger, charge_to_soc, target_p=0.95):
    """한 충전소 경유 시: (도달확률, 총소요시간 분포 샘플) 반환."""
    d1 = charger.at_km
    d2 = total_km - charger.at_km

    # 구간1: 출발 -> 충전소
    avail1 = sample_usable_kwh(v, soc0)
    need1 = sample_consumption(v, d1, temp_c, ascent_per_km * d1)
    soc_at_charger = (avail1 - need1)

    soh_cap = v.battery_kwh * v.soh_mean
    ok1 = soc_at_charger / soh_cap >= v.reserve_soc  # 충전소까지 가는 데 실패 여부

    # 충전: 목표 SOC까지 (실제 충전량은 도착 잔량에 따라 달라짐)
    target_kwh = soh_cap * charge_to_soc
    add_kwh = np.clip(target_kwh - np.clip(soc_at_charger, 0, None), 0, None)
    charge_min = add_kwh / charger.power_kw * 60.0
    wait_min = rng.lognormal(np.log(charger.wait_mean_min), charger.wait_sigma, size=N)

    # 구간2: 충전소 -> 목적지 (충전 후 SOC에서 출발)
    start2 = np.clip(soc_at_charger, 0, None) + add_kwh
    need2 = sample_consumption(v, d2, temp_c, ascent_per_km * d2)
    remain = (start2 - need2) / soh_cap
    ok2 = remain >= v.reserve_soc

    drive_min = total_km / speed_kmh * 60.0
    total_min = drive_min + wait_min + charge_min
    p = float(np.mean(ok1 & ok2))
    return p, total_min


def plan_charging(v, soc0, total_km, temp_c, ascent_per_km, speed_kmh,
                  chargers, target_p=0.95):
    """충전 없이 목표 확률을 못 넘기면, 확률 조건을 만족하는 최소 시간 계획을 탐색."""
    p0, _ = arrival_probability(v, soc0, total_km, temp_c, ascent_per_km * total_km)
    print(f"[무충전] 도달 확률 {p0:.1%} (목표 {target_p:.0%})")
    if p0 >= target_p:
        print("→ 충전 없이 가도 충분합니다.")
        return None

    best = None
    for c in chargers:
        for tgt in np.arange(0.30, 0.91, 0.05):
            p, t = trip_time_with_stop(v, soc0, total_km, temp_c, ascent_per_km,
                                       speed_kmh, c, tgt)
            if p < target_p:
                continue
            t50, t90 = np.percentile(t, [50, 90])
            # 비용: 중앙값 + 지연 리스크(P90) 가중
            score = 0.6 * t50 + 0.4 * t90
            if best is None or score < best["score"]:
                best = dict(charger=c.name, charge_to=float(tgt), p=p,
                            t50=t50, t90=t90, score=score)
    return best


# ---------------------------------------------------------------
# 데모
# ---------------------------------------------------------------
if __name__ == "__main__":
    v = Vehicle()

    print("=" * 56)
    print("시나리오: 서울 -> 부산 약 400km, 겨울(-3°C), SOC 90%")
    print("=" * 56)
    total_km, temp_c, soc0, speed = 400, -3, 0.90, 95
    ascent_per_km = 6.0  # m/km 누적 상승

    p, (p10, p50, p90) = arrival_probability(
        v, soc0, total_km, temp_c, ascent_per_km * total_km)
    print(f"도착 시 잔량 SOC: P10={p10:.0%} / P50={p50:.0%} / P90={p90:.0%}")
    print(f"(참고) 도달 확률 게이지: {p:.0%}\n")

    chargers = [
        Charger("A휴게소", at_km=150, power_kw=120, wait_mean_min=4),
        Charger("B휴게소", at_km=220, power_kw=200, wait_mean_min=12),
        Charger("C휴게소", at_km=280, power_kw=100, wait_mean_min=2),
    ]
    best = plan_charging(v, soc0, total_km, temp_c, ascent_per_km, speed, chargers)
    if best:
        print("\n추천 계획")
        print(f"  경유: {best['charger']}, SOC {best['charge_to']:.0%}까지 충전")
        print(f"  도달 확률: {best['p']:.1%}")
        print(f"  총 소요시간: 중앙값 {best['t50']:.0f}분 / P90 {best['t90']:.0f}분")
    else:
        print("\n목표 확률을 만족하는 계획이 없습니다. 충전소 후보를 늘리거나 목표를 조정하세요.")

    print("\n" + "=" * 56)
    print("시나리오: 도심 출퇴근 30km, 여름(28°C), SOC 45%")
    print("=" * 56)
    p, (p10, p50, p90) = arrival_probability(v, 0.45, 30, 28, 150)
    print(f"도달 확률 {p:.1%}  |  도착 잔량 SOC P10={p10:.0%} / P50={p50:.0%} / P90={p90:.0%}")

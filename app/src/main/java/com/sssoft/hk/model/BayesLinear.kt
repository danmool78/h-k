package com.sssoft.hk.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 베이지안 선형회귀 y = θ·x + ε (칼만 갱신) + 잊기.
 * 차량 소모 모델과 운전 습관 모델이 같이 쓴다.
 *
 * 갱신마다 공분산을 [forget] 으로 키워 오래된 관측의 비중을 줄인다 (유효 기억 ≈ 1/(1−forget) 회).
 * 단, 사전 분산보다 커지지 않게 막아 오래 관측 안 된 항목(예: 여름의 눈길)이 발산하지 않게 한다.
 */
class BayesLinear(
    val mean: DoubleArray,
    val cov: Array<DoubleArray>,
    private val priorStd: DoubleArray,
    private val forget: Double,
    /** 음수가 될 수 없는 계수 (물리량) */
    private val nonNegative: Boolean = false,
    val updates: Int = 0,
) {
    val size: Int get() = mean.size
    val std: DoubleArray get() = DoubleArray(size) { sqrt(cov[it][it]) }

    fun predict(x: DoubleArray): Double = dot(x, mean)

    /** 계수 불확실성에서 오는 예측 분산 xᵀΣx */
    fun predictVar(x: DoubleArray): Double = dot(x, mulVec(cov, x))

    fun update(x: DoubleArray, y: Double, noiseStd: Double): BayesLinear {
        val prior = inflated()
        val sx = mulVec(prior, x)
        val denom = dot(x, sx) + noiseStd * noiseStd
        val pred = dot(x, mean)
        val k = DoubleArray(size) { sx[it] / denom }
        val m = DoubleArray(size) { (mean[it] + k[it] * (y - pred)).let { v -> if (nonNegative) max(0.0, v) else v } }
        val c = Array(size) { i -> DoubleArray(size) { j -> prior[i][j] - k[i] * sx[j] } }
        return BayesLinear(m, symmetrize(c), priorStd, forget, nonNegative, updates + 1)
    }

    /** Σ/λ 후, 대각이 사전 분산을 넘는 항목은 행·열을 같이 줄여(D·Σ·D) 양의 정부호를 유지. */
    private fun inflated(): Array<DoubleArray> {
        val c = Array(size) { i -> DoubleArray(size) { j -> cov[i][j] / forget } }
        val d = DoubleArray(size) { i ->
            val pv = priorStd[i] * priorStd[i]
            if (c[i][i] > pv) sqrt(pv / c[i][i]) else 1.0
        }
        return Array(size) { i -> DoubleArray(size) { j -> c[i][j] * d[i] * d[j] } }
    }

    /** 몬테카를로용: θ ~ N(mean, cov) 를 [n] 개 뽑는다. */
    fun sample(rng: Random, n: Int): Array<DoubleArray> {
        val l = cholesky(cov)
        return Array(n) {
            val z = DoubleArray(size) { rng.nextGaussian() }
            DoubleArray(size) { i ->
                var v = mean[i]
                for (j in 0..i) v += l[i][j] * z[j]
                if (nonNegative) max(0.0, v) else v
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("mean", JSONArray(mean.toList()))
        .put("cov", JSONArray(cov.map { JSONArray(it.toList()) }))
        .put("updates", updates)

    companion object {
        fun prior(mean: DoubleArray, std: DoubleArray, forget: Double, nonNegative: Boolean = false) =
            BayesLinear(mean.copyOf(), diag(std), std, forget, nonNegative)

        /** 저장된 값 복원. 특징 수가 바뀌었으면(앱 업데이트) 사전값으로 다시 시작. */
        fun fromJson(o: JSONObject?, priorMean: DoubleArray, priorStd: DoubleArray, forget: Double, nonNegative: Boolean = false): BayesLinear {
            val m = o?.optJSONArray("mean")
            if (o == null || m == null || m.length() != priorMean.size) return prior(priorMean, priorStd, forget, nonNegative)
            val c = o.getJSONArray("cov")
            val n = priorMean.size
            return BayesLinear(
                DoubleArray(n) { m.getDouble(it) },
                Array(n) { i -> c.getJSONArray(i).let { r -> DoubleArray(n) { r.getDouble(it) } } },
                priorStd, forget, nonNegative, o.optInt("updates", 0),
            )
        }

        fun diag(std: DoubleArray) = Array(std.size) { i -> DoubleArray(std.size) { j -> if (i == j) std[i] * std[i] else 0.0 } }
        fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
        fun mulVec(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(m.size) { dot(m[it], v) }
        private fun symmetrize(m: Array<DoubleArray>) = Array(m.size) { i -> DoubleArray(m.size) { j -> (m[i][j] + m[j][i]) / 2 } }

        /** 하삼각 촐레스키. 수치 오차로 음수가 나오면 0으로 둔다. */
        fun cholesky(a: Array<DoubleArray>): Array<DoubleArray> {
            val n = a.size
            val l = Array(n) { DoubleArray(n) }
            for (i in 0 until n) for (j in 0..i) {
                var s = a[i][j]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                l[i][j] = if (i == j) sqrt(max(s, 0.0)) else if (l[j][j] > 1e-12) s / l[j][j] else 0.0
            }
            return l
        }
    }
}

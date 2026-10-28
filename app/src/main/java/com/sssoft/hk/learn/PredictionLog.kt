package com.sssoft.hk.learn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * 주행별 "내비 예측 vs 안기다려 예측 vs 실제" 기록 (최근 [MAX]회).
 * 핵심 지표: 안기다려가 내비보다 얼마나 정확한가.
 */
data class PredictionRecord(
    val at: Long,
    /** 도착 배터리 (0~1) */
    val navSoc: Double,
    val oursSoc: Double,
    val actualSoc: Double,
    /** 주행 시간 (분, 충전·정차 제외) */
    val navMin: Double,
    val oursMin: Double,
    val actualMin: Double,
) {
    fun toJson(): JSONObject = JSONObject().put("at", at)
        .put("navSoc", navSoc).put("oursSoc", oursSoc).put("actualSoc", actualSoc)
        .put("navMin", navMin).put("oursMin", oursMin).put("actualMin", actualMin)

    companion object {
        fun fromJson(o: JSONObject) = PredictionRecord(
            o.getLong("at"), o.getDouble("navSoc"), o.getDouble("oursSoc"), o.getDouble("actualSoc"),
            o.getDouble("navMin"), o.getDouble("oursMin"), o.getDouble("actualMin"),
        )
    }
}

/** 평균 절대 오차 비교 */
data class AccuracySummary(
    val trips: Int,
    val navSocErr: Double, val oursSocErr: Double,
    val navMinErr: Double, val oursMinErr: Double,
)

object PredictionLog {
    private const val FILE = "prediction_log.json"
    const val MAX = 50

    @Synchronized
    fun append(context: Context, r: PredictionRecord) {
        val list = (load(context) + r).takeLast(MAX)
        File(context.filesDir, FILE).writeText(JSONArray(list.map { it.toJson() }).toString())
    }

    fun load(context: Context): List<PredictionRecord> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching { JSONArray(f.readText()).let { a -> List(a.length()) { PredictionRecord.fromJson(a.getJSONObject(it)) } } }
            .getOrDefault(emptyList())
    }

    fun summarize(list: List<PredictionRecord>): AccuracySummary? {
        if (list.isEmpty()) return null
        fun mae(f: (PredictionRecord) -> Double) = list.map(f).average()
        return AccuracySummary(
            list.size,
            mae { abs(it.navSoc - it.actualSoc) }, mae { abs(it.oursSoc - it.actualSoc) },
            mae { abs(it.navMin - it.actualMin) }, mae { abs(it.oursMin - it.actualMin) },
        )
    }
}

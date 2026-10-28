package com.sssoft.hk.learn

import android.content.Context
import com.sssoft.hk.model.VehicleProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 학습 프로필과 최근 원본 기록 저장.
 * 원본 기록은 최근 [MAX_LOG]건만 남기고 오래된 것부터 버린다 (프로필은 이미 반영된 상태라 원본은 점검·재학습용).
 */
object ProfileStore {
    private const val PROFILE = "vehicle_profile.json"
    private const val LOG = "drive_log.jsonl"
    const val MAX_LOG = 500

    @Volatile private var cached: VehicleProfile? = null

    fun load(context: Context): VehicleProfile {
        cached?.let { return it }
        val f = File(context.filesDir, PROFILE)
        val p = runCatching { VehicleProfile.fromJson(f.readText()) }.getOrNull() ?: VehicleProfile()
        cached = p
        return p
    }

    @Synchronized
    fun save(context: Context, p: VehicleProfile) {
        val f = File(context.filesDir, PROFILE)
        val tmp = File(context.filesDir, "$PROFILE.tmp")
        tmp.writeText(p.toJson())
        tmp.renameTo(f)
        cached = p
    }

    @Synchronized
    fun update(context: Context, f: (VehicleProfile) -> VehicleProfile): VehicleProfile = f(load(context)).also { save(context, it) }

    /** 원본 기록 한 줄 추가. [MAX_LOG] 를 넘으면 오래된 것부터 버림. */
    @Synchronized
    fun appendLog(context: Context, record: JSONObject) {
        val f = File(context.filesDir, LOG)
        val lines = if (f.exists()) f.readLines() else emptyList()
        val kept = (lines + record.toString()).takeLast(MAX_LOG)
        f.writeText(kept.joinToString("\n"))
    }

    fun recentLog(context: Context, n: Int = 50): JSONArray {
        val f = File(context.filesDir, LOG)
        if (!f.exists()) return JSONArray()
        return JSONArray(f.readLines().takeLast(n).map { JSONObject(it) })
    }
}

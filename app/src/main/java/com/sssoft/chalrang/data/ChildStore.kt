package com.sssoft.chalrang.data

import android.content.Context
import com.sssoft.chalrang.core.Child
import com.sssoft.chalrang.core.Seat
import com.sssoft.chalrang.core.Sensitivity
import com.sssoft.chalrang.core.TripSummary
import com.sssoft.chalrang.engine.ChildRepo
import org.json.JSONArray
import org.json.JSONObject

/** 아이 프로필 저장 (차 안에만, 기기 밖으로 보내지 않는다) */
class ChildStore(context: Context) : ChildRepo {

    private val prefs = context.applicationContext.getSharedPreferences("children", Context.MODE_PRIVATE)

    /** 저장해 둔 길은 커서(수백 KB) 설정 대신 파일로 */
    private val dir = java.io.File(context.applicationContext.filesDir, "routes")

    override fun load(): List<Child> = decode(prefs.getString(KEY, null))

    override fun save(children: List<Child>) {
        prefs.edit().putString(KEY, encode(children)).apply()
    }

    override fun loadTrips(): List<TripSummary> = TripSummary.decode(prefs.getString(TRIPS, null))

    override fun saveTrips(trips: List<TripSummary>) {
        prefs.edit().putString(TRIPS, TripSummary.encode(trips)).apply()
    }

    override fun loadSettings(): String? = prefs.getString(SETTINGS, null)

    override fun saveSettings(text: String) {
        prefs.edit().putString(SETTINGS, text).apply()
    }

    override fun loadText(name: String): String? = runCatching { java.io.File(dir, safe(name)).takeIf { it.exists() }?.readText() }.getOrNull()

    override fun saveText(name: String, text: String?) {
        runCatching {
            val f = java.io.File(dir, safe(name))
            if (text == null) { f.delete(); return }
            dir.mkdirs()
            // 쓰다 끊겨도 반쯤 쓴 파일이 남지 않게: 옆에 쓰고 바꿔 끼운다
            val tmp = java.io.File(dir, safe(name) + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    companion object {
        private const val KEY = "list"
        private const val TRIPS = "trips"
        private const val SETTINGS = "settings"

        fun encode(children: List<Child>): String = JSONArray().apply {
            children.forEach {
                put(
                    JSONObject()
                        .put("id", it.id).put("name", it.name).put("seat", it.seat.name)
                        .put("level", it.level).put("calibrations", it.calibrations)
                        .put("periods", JSONArray(it.sensitivity.periods)),
                )
            }
        }.toString()

        fun decode(json: String?): List<Child> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(json)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Child(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        seat = Seat.valueOf(o.getString("seat")),
                        level = o.getDouble("level"),
                        calibrations = o.optInt("calibrations", 0),
                        sensitivity = Sensitivity(
                            o.optJSONArray("periods")?.let { p -> (0 until p.length()).map { k -> p.getDouble(k) } }.orEmpty(),
                        ),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}

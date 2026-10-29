package com.sssoft.chalrang.data

import android.content.Context
import com.sssoft.chalrang.core.Child
import com.sssoft.chalrang.core.Seat
import org.json.JSONArray
import org.json.JSONObject

/** 아이 프로필 저장 (차 안에만, 기기 밖으로 보내지 않는다) */
class ChildStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("children", Context.MODE_PRIVATE)

    fun load(): List<Child> = decode(prefs.getString(KEY, null))

    fun save(children: List<Child>) {
        prefs.edit().putString(KEY, encode(children)).apply()
    }

    companion object {
        private const val KEY = "list"

        fun encode(children: List<Child>): String = JSONArray().apply {
            children.forEach {
                put(
                    JSONObject()
                        .put("id", it.id).put("name", it.name).put("seat", it.seat.name)
                        .put("level", it.level).put("calibrations", it.calibrations),
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
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}

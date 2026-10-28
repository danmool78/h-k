package com.sssoft.hk.watch

import android.content.Context
import com.sssoft.hk.navi.LatLng

/** 지금 경유지로 가고 있는 충전소. 감시 서비스가 이 값을 기준으로 상태를 확인한다. */
data class ChargeTarget(val stationId: String, val name: String, val location: LatLng, val setAt: Long, val maxKw: Double? = null)

object ChargeTargetStore {
    private const val NAME = "charge_target"

    fun get(context: Context): ChargeTarget? {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val id = p.getString("id", null) ?: return null
        return ChargeTarget(
            id, p.getString("name", "")!!,
            LatLng(p.getFloat("lat", 0f).toDouble(), p.getFloat("lng", 0f).toDouble()),
            p.getLong("setAt", 0),
            p.getFloat("maxKw", -1f).takeIf { it > 0 }?.toDouble(),
        )
    }

    fun set(context: Context, t: ChargeTarget) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("id", t.stationId).putString("name", t.name)
            .putFloat("lat", t.location.lat.toFloat()).putFloat("lng", t.location.lng.toFloat())
            .putLong("setAt", t.setAt)
            .putFloat("maxKw", (t.maxKw ?: -1.0).toFloat())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

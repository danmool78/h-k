package com.sssoft.hk.trip

import android.content.Context
import com.sssoft.hk.model.Weather

/**
 * 화면에서 마지막으로 입력한 값. 외부 기온·날씨는 차량 SDK에 없어서
 * 기상청 예보 연동 전까지 이 값을 백그라운드(Gleo, 감시 서비스)에서도 쓴다.
 */
object TripPrefs {
    private const val NAME = "trip"
    private const val KEY_TEMP = "tempC"
    private const val KEY_WEATHER = "weather"
    private const val DEFAULT_TEMP = 10.0

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun tempC(context: Context): Double = prefs(context).getFloat(KEY_TEMP, DEFAULT_TEMP.toFloat()).toDouble()

    fun setTempC(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_TEMP, value).apply()
    }

    /** 목적지 도착 시 최소 배터리 (기본 30%) */
    fun arrivalReserve(context: Context): Double = prefs(context).getFloat("arrivalReserve", 0.30f).toDouble()

    fun setArrivalReserve(context: Context, value: Double) {
        prefs(context).edit().putFloat("arrivalReserve", value.toFloat()).apply()
    }

    fun weather(context: Context): Weather =
        runCatching { Weather.valueOf(prefs(context).getString(KEY_WEATHER, null)!!) }.getOrDefault(Weather.CLEAR)

    fun setWeather(context: Context, value: Weather) {
        prefs(context).edit().putString(KEY_WEATHER, value.name).apply()
    }
}

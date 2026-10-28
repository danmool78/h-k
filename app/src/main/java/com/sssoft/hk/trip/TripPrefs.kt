package com.sssoft.hk.trip

import android.content.Context

/** 화면에서 마지막으로 입력한 값. 외부 기온은 차량 SDK에 없어서 기상 API 연동 전까지 이 값을 쓴다. */
object TripPrefs {
    private const val NAME = "trip"
    private const val KEY_TEMP = "tempC"
    private const val DEFAULT_TEMP = 10.0

    fun tempC(context: Context): Double =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getFloat(KEY_TEMP, DEFAULT_TEMP.toFloat()).toDouble()

    fun setTempC(context: Context, value: Float) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_TEMP, value).apply()
    }
}

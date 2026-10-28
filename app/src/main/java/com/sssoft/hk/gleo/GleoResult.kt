package com.sssoft.hk.gleo

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Gleo Callback Schema. message 는 영어 한두 문장 (Gleo가 사용자 언어로 다시 말함). */
data class GleoResult(
    val functionName: String,
    val status: String,
    val message: String,
    val data: List<Pair<String, Any>> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("functionName", functionName)
        .put("status", status)
        .put("message", message)
        .apply {
            // data 는 key-value 객체 배열을 담은 "문자열"
            if (data.isNotEmpty()) put("data", JSONArray(data.map { (k, v) -> JSONObject().put(k, v) }).toString())
        }
        .toString()

    companion object {
        private const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

        fun success(fn: String, message: String, vararg data: Pair<String, Any>) = GleoResult(fn, "success", message, data.toList())
        fun asking(fn: String, message: String) = GleoResult(fn, "asking", message)
        fun fail(fn: String, message: String) = GleoResult(fn, "fail", message)

        fun send(context: Context, r: GleoResult) {
            context.sendBroadcast(
                Intent(ACTION_RESPONSE)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", r.toJson()),
            )
        }
    }
}

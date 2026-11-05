package com.sssoft.chalrang.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 안드로이드 기본 User-Agent(Dalvik/…)는 OSRM 공개 서버가 403 으로 막는다 */
private const val USER_AGENT = "Chalrang/0.1 (+https://github.com/danmool78/h-k)"

actual suspend fun httpRequest(url: String, method: String, body: String?): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) { android.util.Log.w("Http", "$code ${url.take(80)}"); null }
            else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }.onFailure { android.util.Log.w("Http", "실패 ${url.take(80)}", it) }.getOrNull()
}

actual suspend fun fetchTerrarium(z: Int, x: Int, y: Int): ShortArray? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("${TerrainTiles.URL}/$z/$x/$y.png").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.connectTimeout = 5_000
        c.readTimeout = 8_000
        val bytes = try {
            if (c.responseCode !in 200..299) return@runCatching null
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
        // 색 보정·알파 곱셈 없이 원래 값 그대로 (높이가 RGB 에 들어 있다)
        val opts = android.graphics.BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
        val n = bmp.width * bmp.height
        val px = IntArray(n)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        ShortArray(n) { i ->
            val p = px[i]
            val h = ((p shr 16) and 0xFF) * 256 + ((p shr 8) and 0xFF) + (p and 0xFF) / 256.0 - 32768
            h.toInt().coerceIn(-1000, 9000).toShort()
        }
    }.getOrNull()
}

package com.sssoft.chalrang.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush

/** Android 13(API 33)+ RuntimeShader(AGSL). 그 아래에서는 지원 안 함 → 2D 물컵 */
actual class WaterShader actual constructor() {
    private var shader: RuntimeShader? = null
    private var bitmap: Bitmap? = null
    private var brush: Brush? = null

    actual val supported: Boolean

    init {
        var ok = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                shader = RuntimeShader(WATER_SKSL)
                ok = true
            } catch (e: Exception) {
                Log.w("WaterShader", "셰이더 만들기 실패 → 2D 물컵", e)
            }
        }
        supported = ok
    }

    actual fun setUniforms(u: WaterUniforms) {
        val s = shader ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        s.setFloatUniform("uRes", u.width, u.height)
        s.setFloatUniform("uUnit", u.unit)
        s.setFloatUniform("uCx", u.cx)
        s.setFloatUniform("uCy", u.cy)
        s.setFloatUniform("uH0", u.h0)
        s.setFloatUniform("uPx", u.slopeX)
        s.setFloatUniform("uPy", u.slopeY)
        s.setFloatUniform("uTime", u.time)
        s.setFloatUniform("uWater", u.r, u.g, u.b)
    }

    actual fun setHeights(argb: IntArray, size: Int) {
        val s = shader ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val bmp = bitmap ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            bitmap = it
            val bs = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            bs.filterMode = BitmapShader.FILTER_MODE_LINEAR
            s.setInputShader("heights", bs)
        }
        bmp.setPixels(argb, 0, size, 0, 0, size, size)
    }

    actual fun brush(): Brush? {
        val s = shader ?: return null
        if (bitmap == null) return null
        return brush ?: ShaderBrush(s).also { brush = it }
    }

    actual fun release() {
        bitmap?.recycle()
        bitmap = null
        shader = null
        brush = null
    }
}

package com.sssoft.chalrang.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush

/** Android 13(API 33)+ RuntimeShader(AGSL). 그 아래에서는 지원 안 함 → 칸으로 칠하는 지형 */
actual class TerrainShader actual constructor() {
    private var shader: RuntimeShader? = null
    private var bitmap: Bitmap? = null
    private var brush: Brush? = null

    actual val supported: Boolean

    init {
        var ok = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                shader = RuntimeShader(TERRAIN_SKSL)
                ok = true
            } catch (e: Exception) {
                Log.w("TerrainShader", "셰이더 만들기 실패 → 칸 지형", e)
            }
        }
        supported = ok
    }

    actual fun setGrid(argb: IntArray, w: Int, h: Int) {
        val s = shader ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        bitmap?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, w, 0, 0, w, h)
        bitmap = bmp
        val bs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bs.filterMode = BitmapShader.FILTER_MODE_LINEAR
        s.setInputShader("heights", bs)
        brush = null
    }

    actual fun setUniforms(u: TerrainUniforms) {
        val s = shader ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        s.setFloatUniform("uEye", u.eye[0], u.eye[1], u.eye[2])
        s.setFloatUniform("uF", u.f[0], u.f[1], u.f[2])
        s.setFloatUniform("uR", u.r[0], u.r[1], u.r[2])
        s.setFloatUniform("uU", u.u[0], u.u[1], u.u[2])
        s.setFloatUniform("uFocal", u.focal)
        s.setFloatUniform("uC", u.cx, u.cy)
        s.setFloatUniform("uOrigin", u.ox, u.oy)
        s.setFloatUniform("uStep", u.step)
        s.setFloatUniform("uSize", u.nx, u.ny)
        s.setFloatUniform("uZScale", u.zScale)
        s.setFloatUniform("uZ0", u.z0)
        s.setFloatUniform("uHMin", u.hMin)
        s.setFloatUniform("uHRange", u.hRange)
        s.setFloatUniform("uFar", u.far)
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

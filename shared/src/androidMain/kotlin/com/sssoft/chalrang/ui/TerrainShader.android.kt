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

    private var coarseBitmap: Bitmap? = null

    actual fun setGrids(fine: EncodedGrid, coarse: EncodedGrid) {
        val s = shader ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        bitmap?.recycle()
        coarseBitmap?.recycle()
        bitmap = upload(s, "fine", fine)
        coarseBitmap = upload(s, "coarse", coarse)
        brush = null
    }

    private fun upload(s: RuntimeShader, name: String, e: EncodedGrid): Bitmap {
        val bmp = Bitmap.createBitmap(e.w, e.h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(e.argb, 0, e.w, 0, 0, e.w, e.h)
        val bs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        bs.filterMode = BitmapShader.FILTER_MODE_LINEAR
        s.setInputShader(name, bs)
        return bmp
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
        s.setFloatUniform("uOrigin", u.ox[0], u.oy[0])
        s.setFloatUniform("uStep", u.step[0])
        s.setFloatUniform("uSize", u.nx[0], u.ny[0])
        s.setFloatUniform("uHMin", u.hMin[0])
        s.setFloatUniform("uHRange", u.hRange[0])
        s.setFloatUniform("uOrigin2", u.ox[1], u.oy[1])
        s.setFloatUniform("uStep2", u.step[1])
        s.setFloatUniform("uSize2", u.nx[1], u.ny[1])
        s.setFloatUniform("uHMin2", u.hMin[1])
        s.setFloatUniform("uHRange2", u.hRange[1])
        s.setFloatUniform("uZScale", u.zScale)
        s.setFloatUniform("uZ0", u.z0)
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
        coarseBitmap?.recycle()
        coarseBitmap = null
        shader = null
        brush = null
    }
}

package com.sssoft.chalrang.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader

/** 웹(Skiko) RuntimeEffect. 매 프레임 셰이더를 새로 만들므로 오래된 것은 close 해 네이티브 메모리를 반납한다 */
actual class TerrainShader actual constructor() {
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null
    private var heightShader: Shader? = null
    private val recent = ArrayDeque<Shader>()

    actual val supported: Boolean

    init {
        var ok = false
        try {
            effect = RuntimeEffect.makeForShader(TERRAIN_SKSL)
            builder = RuntimeShaderBuilder(effect!!)
            ok = true
        } catch (e: Throwable) {
            println("TerrainShader: 셰이더 만들기 실패 → 칸 지형: ${e.message}")
        }
        supported = ok
    }

    /** 올린 그림 둘 (자세한 것, 넓은 것): 바꿀 때 이전 것을 닫는다 */
    private val held = ArrayList<org.jetbrains.skia.impl.Managed>()

    actual fun setGrids(fine: EncodedGrid, coarse: EncodedGrid) {
        val b = builder ?: return
        held.forEach { it.close() }
        held.clear()
        b.child("fine", upload(fine))
        b.child("coarse", upload(coarse))
        heightShader = held.last() as Shader
    }

    private fun upload(e: EncodedGrid): Shader {
        val bytes = ByteArray(e.w * e.h * 4)
        for (k in 0 until e.w * e.h) {
            val c = e.argb[k]
            bytes[k * 4] = (c shr 16 and 0xFF).toByte()
            bytes[k * 4 + 1] = (c shr 8 and 0xFF).toByte()
            bytes[k * 4 + 2] = (c and 0xFF).toByte()
            bytes[k * 4 + 3] = (c ushr 24).toByte()
        }
        val bmp = Bitmap()
        bmp.allocPixels(ImageInfo(e.w, e.h, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        bmp.installPixels(bmp.imageInfo, bytes, e.w * 4)
        val img = Image.makeFromBitmap(bmp)
        val sh = img.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE))
        held += bmp
        held += img
        held += sh
        return sh
    }

    actual fun setUniforms(u: TerrainUniforms) {
        val b = builder ?: return
        b.uniform("uEye", u.eye[0], u.eye[1], u.eye[2])
        b.uniform("uF", u.f[0], u.f[1], u.f[2])
        b.uniform("uR", u.r[0], u.r[1], u.r[2])
        b.uniform("uU", u.u[0], u.u[1], u.u[2])
        b.uniform("uFocal", u.focal)
        b.uniform("uC", u.cx, u.cy)
        b.uniform("uOrigin", u.ox[0], u.oy[0])
        b.uniform("uStep", u.step[0])
        b.uniform("uSize", u.nx[0], u.ny[0])
        b.uniform("uHMin", u.hMin[0])
        b.uniform("uHRange", u.hRange[0])
        b.uniform("uOrigin2", u.ox[1], u.oy[1])
        b.uniform("uStep2", u.step[1])
        b.uniform("uSize2", u.nx[1], u.ny[1])
        b.uniform("uHMin2", u.hMin[1])
        b.uniform("uHRange2", u.hRange[1])
        b.uniform("uZScale", u.zScale)
        b.uniform("uZ0", u.z0)
        b.uniform("uFar", u.far)
    }

    actual fun brush(): Brush? {
        val b = builder ?: return null
        if (heightShader == null) return null
        val sh = b.makeShader()
        recent.addLast(sh)
        while (recent.size > KEEP) recent.removeFirst().close()
        return ShaderBrush(sh)
    }

    actual fun release() {
        recent.forEach { it.close() }; recent.clear()
        held.forEach { it.close() }; held.clear()
        heightShader = null
        builder?.close(); builder = null
        effect?.close(); effect = null
    }

    private companion object {
        const val KEEP = 3
    }
}

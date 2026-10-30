package com.sssoft.chalrang.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader

/**
 * 웹(Skiko) RuntimeEffect. 매 프레임 셰이더를 다시 만들므로 이전 것은 바로 close 해 네이티브 메모리를 반납한다.
 */
actual class WaterShader actual constructor() {
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null
    private val bitmap = Bitmap()
    private var image: Image? = null
    private var heightShader: Shader? = null
    /** 그리기 기록이 아직 참조할 수 있어 최근 셰이더 몇 개는 남겨 두고 그보다 오래된 것만 닫는다 */
    private val recent = ArrayDeque<Shader>()
    private val oldImages = ArrayDeque<Pair<Image, Shader>>()
    private var size = 0

    actual val supported: Boolean

    init {
        var ok = false
        try {
            effect = RuntimeEffect.makeForShader(WATER_SKSL)
            builder = RuntimeShaderBuilder(effect!!)
            ok = true
        } catch (e: Throwable) {
            println("WaterShader: 셰이더 만들기 실패 → 2D 물컵: ${e.message}")
        }
        supported = ok
    }

    actual fun setUniforms(u: WaterUniforms) {
        val b = builder ?: return
        b.uniform("uRes", u.width, u.height)
        b.uniform("uUnit", u.unit)
        b.uniform("uCx", u.cx)
        b.uniform("uCy", u.cy)
        b.uniform("uH0", u.h0)
        b.uniform("uPx", u.slopeX)
        b.uniform("uPy", u.slopeY)
        b.uniform("uTime", u.time)
        b.uniform("uWater", u.r, u.g, u.b)
    }

    private val bytes = ByteArray(WaterSim.N * WaterSim.N * 4)

    actual fun setHeights(argb: IntArray, size: Int) {
        val b = builder ?: return
        if (this.size != size) {
            bitmap.allocPixels(ImageInfo.makeN32(size, size, ColorAlphaType.PREMUL))
            this.size = size
        }
        // N32 (웹 Skia 는 RGBA) 바이트로
        for (k in 0 until size * size) {
            val c = argb[k]
            bytes[k * 4] = (c shr 16 and 0xFF).toByte()
            bytes[k * 4 + 1] = (c shr 8 and 0xFF).toByte()
            bytes[k * 4 + 2] = (c and 0xFF).toByte()
            bytes[k * 4 + 3] = (c ushr 24).toByte()
        }
        bitmap.installPixels(bitmap.imageInfo, bytes, size * 4)
        bitmap.notifyPixelsChanged()
        val oldImage = image
        val oldShader = heightShader
        if (oldImage != null && oldShader != null) {
            oldImages.addLast(oldImage to oldShader)
            while (oldImages.size > KEEP) oldImages.removeFirst().let { (i, sh) -> sh.close(); i.close() }
        }
        image = Image.makeFromBitmap(bitmap)
        heightShader = image!!.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE))
        b.child("heights", heightShader!!)
    }

    actual fun brush(): Brush? {
        val b = builder ?: return null
        if (heightShader == null) return null
        val sh = b.makeShader()
        recent.addLast(sh)
        while (recent.size > KEEP) recent.removeFirst().close()
        return ShaderBrush(sh)
    }

    private companion object {
        const val KEEP = 3
    }

    actual fun release() {
        recent.forEach { it.close() }; recent.clear()
        oldImages.forEach { (i, sh) -> sh.close(); i.close() }; oldImages.clear()
        heightShader?.close(); heightShader = null
        image?.close(); image = null
        bitmap.close()
        builder?.close(); builder = null
        effect?.close(); effect = null
    }
}

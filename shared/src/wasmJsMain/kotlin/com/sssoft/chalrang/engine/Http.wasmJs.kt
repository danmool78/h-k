package com.sssoft.chalrang.engine

import kotlinx.coroutines.await
import kotlin.js.Promise

private fun jsFetch(url: String, method: String, body: String?): Promise<JsAny?> = js(
    """fetch(url, body == null ? { method: method } : { method: method, body: body, headers: { 'Content-Type': 'application/json' } })
        .then(r => r.ok ? r.text() : null).catch(() => null)""",
)

actual suspend fun httpRequest(url: String, method: String, body: String?): String? =
    runCatching { jsFetch(url, method, body).await<JsString?>()?.toString() }.getOrNull()

/** 타일 PNG → 높이 + 1000 을 글자 하나씩 (값을 하나씩 넘기면 느려서 글 한 덩이로) */
private fun jsTerrarium(url: String): Promise<JsString?> = js(
    """fetch(url).then(r => r.ok ? r.blob() : null)
        .then(b => b ? createImageBitmap(b, { colorSpaceConversion: 'none', premultiplyAlpha: 'none' }) : null)
        .then(img => {
            if (!img) return null;
            const c = new OffscreenCanvas(img.width, img.height);
            const g = c.getContext('2d', { willReadFrequently: true });
            g.drawImage(img, 0, 0);
            const d = g.getImageData(0, 0, img.width, img.height).data;
            const n = img.width * img.height;
            const parts = [];
            let s = '';
            for (let i = 0; i < n; i++) {
                let h = Math.round(d[4 * i] * 256 + d[4 * i + 1] + d[4 * i + 2] / 256 - 32768) + 1000;
                if (h < 0) h = 0; if (h > 10000) h = 10000;
                s += String.fromCharCode(h);
                if (s.length >= 4096) { parts.push(s); s = ''; }
            }
            parts.push(s);
            img.close();
            return parts.join('');
        }).catch(() => null)""",
)

actual suspend fun fetchTerrarium(z: Int, x: Int, y: Int): ShortArray? = runCatching {
    val s = jsTerrarium("${TerrainTiles.URL}/$z/$x/$y.png").await<JsString?>()?.toString() ?: return null
    ShortArray(s.length) { (s[it].code - 1000).toShort() }
}.getOrNull()

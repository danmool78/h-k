package com.sssoft.chalrang.engine

import kotlinx.coroutines.await
import kotlin.js.Promise

private fun jsFetch(url: String, method: String, body: String?): Promise<JsAny?> = js(
    """fetch(url, body == null ? { method: method } : { method: method, body: body, headers: { 'Content-Type': 'application/json' } })
        .then(r => r.ok ? r.text() : null).catch(() => null)""",
)

actual suspend fun httpRequest(url: String, method: String, body: String?): String? =
    runCatching { jsFetch(url, method, body).await<JsString?>()?.toString() }.getOrNull()

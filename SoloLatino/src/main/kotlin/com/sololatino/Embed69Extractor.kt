package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

object Embed69Extractor {

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val html = app.get(url, headers = mapOf("Referer" to referer)).text

        // =========================
        // dataLink
        // =========================
        Regex("""dataLink\s*=\s*(\[.*?\]);""")
            .find(html)?.groupValues?.getOrNull(1)?.let { json ->

                val parsed = AppUtils.tryParseJson<List<Map<String, Any>>>(json) ?: return

                parsed.forEach { lang ->
                    val embeds = lang["sortedEmbeds"] as? List<Map<String, Any>> ?: return@forEach

                    embeds.forEach { embed ->
                        val enc = embed["link"] as? String ?: return@forEach
                        val real = decode(enc) ?: return@forEach

                        loadExtractor(real, url, subtitleCallback, callback)
                    }
                }
            }

        // =========================
        // fallback iframe
        // =========================
        app.get(url).document.selectFirst("iframe")?.attr("src")?.let {
            loadExtractor(it, url, subtitleCallback, callback)
        }
    }

    private fun decode(enc: String): String? {
        return try {
            val parts = enc.split(".")
            if (parts.size != 3) return null

            var payload = parts[1]
            val pad = payload.length % 4
            if (pad != 0) payload += "=".repeat(4 - pad)

            val json = String(Base64.decode(payload, Base64.DEFAULT))

            Regex("\"link\":\"(.*?)\"")
                .find(json)?.groupValues?.getOrNull(1)

        } catch (e: Exception) {
            null
        }
    }
}

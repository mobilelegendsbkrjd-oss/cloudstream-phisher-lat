package com.latinluchas

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class Bysekoze : ExtractorApi() {

    override var name = "Bysekoze"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val headers = mapOf(
            "Referer" to (referer ?: url),
            "User-Agent" to "Mozilla/5.0"
        )

        val host = url.substringBefore("/e/")
        val id = Regex("/(?:e|v)/([a-zA-Z0-9]+)")
            .find(url)?.groupValues?.getOrNull(1)

        // ======================
        // MÉTODO 1 — API DIRECTA
        // ======================
        try {
            if (!id.isNullOrEmpty()) {
                val apiUrl = "$host/api/videos/$id/embed/playback"
                val response = app.get(apiUrl, headers = headers).text
                val json = JSONObject(response)

                if (json.has("sources")) {
                    val sources = json.getJSONArray("sources")
                    for (i in 0 until sources.length()) {
                        val obj = sources.getJSONObject(i)
                        val link = obj.getString("url")

                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                link,
                                url,
                                Qualities.Unknown.value,
                                link.contains(".m3u8")
                            )
                        )
                    }
                    return
                }
            }
        } catch (_: Exception) {}

        // ======================
        // MÉTODO 2 — JSUNPACKER
        // ======================
        try {
            val document = app.get(url, headers = headers).documentLarge
            val packed = document
                .selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()
                .orEmpty()

            if (packed.isNotEmpty()) {
                JsUnpacker(packed).unpack()?.let { unpacked ->
                    Regex("""file\s*:\s*["'](.*?)["']""")
                        .find(unpacked)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { link ->
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    link,
                                    url,
                                    Qualities.Unknown.value,
                                    link.contains(".m3u8")
                                )
                            )
                        }
                }
            }
        } catch (_: Exception) {}

        // ======================
        // MÉTODO 3 — Intentar Filemoon fallback
        // ======================
        try {
            loadExtractor(url.replace("byse", "filemoon"), referer, subtitleCallback, callback)
        } catch (_: Exception) {}
    }
}
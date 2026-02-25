package com.latinluchas

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.newExtractorLink
import org.json.JSONObject

class Bysekoze : ExtractorApi() {

    override var name = "Bysekoze"
    override var mainUrl = "https://bysekoze.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Referer" to (referer ?: mainUrl),
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // ==============================
        // MÉTODO 1 — API MODERNA
        // ==============================
        try {
            val id = Regex("/(?:e|v)/([a-zA-Z0-9]+)")
                .find(url)?.groupValues?.getOrNull(1)

            if (!id.isNullOrEmpty()) {
                val apiUrl = "$mainUrl/api/videos/$id/embed/playback"
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
                                url, // referer
                                Qualities.Unknown.value,
                                link.contains(".m3u8")
                            )
                        )
                    }
                    return
                }
            }
        } catch (_: Exception) { }

        // ==============================
        // MÉTODO 2 — FALLBACK JSUNPACKER
        // ==============================
        try {
            val document = app.get(url, headers = headers).documentLarge
            val packedScript = document
                .selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()
                .orEmpty()

            if (packedScript.isNotEmpty()) {
                JsUnpacker(packedScript).unpack()?.let { unpacked ->
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
                                    url, // referer
                                    Qualities.Unknown.value,
                                    link.contains(".m3u8")
                                )
                            )
                        }
                }
            }
        } catch (_: Exception) { }
    }
}
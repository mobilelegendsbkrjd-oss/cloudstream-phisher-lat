// Latinluchas/src/main/kotlin/com/latinluchas/LatinLuchas.kt

package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.json.JSONObject

class LatinLuchas : MainAPI() {
    override var name = "TV LatinLuchas"
    override var mainUrl = "https://tv.latinluchas.com/tv"
    override val hasMainPage = true
    override val lang = "es"
    override val supportedTypes = setOf(TvType.Live)

    private val defaultPoster = "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Por simplicidad, solo página 1 (puedes agregar paginación después si hay ?page=)
        if (page > 1) return newHomePageResponse { list = emptyList() }

        val document = app.get(mainUrl).document

        val home = document.select("article, .elementor-post, .post, a[href*='/tv/coli']").mapNotNull { element ->
            val href = element.attr("abs:href").takeIf { it.contains("/tv/coli") } ?: return@mapNotNull null
            val title = element.selectFirst("h2, h3, .entry-title")?.text()?.trim() ?: "Evento sin título"

            newSearchResponse(title, href, TvType.Live) {
                posterUrl = defaultPoster
            }
        }.distinctBy { it.url }

        return newHomePageResponse {
            name = "Eventos y Repeticiones"
            list = home
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.title().substringBefore(" - TV LatinLuchas").trim()
            .ifBlank { "Evento en vivo" }

        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".elementor-widget-container p")?.text()
            ?: "Repetición o transmisión en vivo"

        return newLiveStreamLoadResponse(title) {
            this.url = url
            apiName = name
            type = TvType.Live
            posterUrl = defaultPoster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        document.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("abs:src").trim()
            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"

            when {
                // OK.ru → nativo
                src.contains("ok.ru/videoembed") -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }

                // Dailymotion → nativo
                src.contains("dailymotion.com/embed/video") -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }

                // Bysekoze → intento API + fallback
                src.contains("bysekoze.com") || src.contains("filemoon") -> {
                    try {
                        val mediaId = src.substringAfterLast("/")
                        val host = src.substringAfter("https://").substringBefore("/")

                        val apiUrl = "https://$host/api/videos/$mediaId/embed/playback"

                        val response = app.get(apiUrl, headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        ))

                        if (response.isSuccessful) {
                            val json = JSONObject(response.text)
                            if (json.has("sources")) {
                                val sources = json.getJSONArray("sources")
                                for (i in 0 until sources.length()) {
                                    val s = sources.getJSONObject(i)
                                    val url = s.optString("url") ?: continue
                                    val label = s.optString("label", "Bysekoze ${i+1}")

                                    callback(ExtractorLink(
                                        source = "Bysekoze",
                                        name = "Opción 3 - $label",
                                        url = url,
                                        referer = src,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = url.contains(".m3u8")
                                    ))
                                    found = true
                                }
                            }
                        }
                    } catch (_: Throwable) {}

                    // Fallback directo (abre en navegador si no carga)
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }

                // upns.online → solo fallback
                src.contains("latinlucha.upns.online") -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }

                // Otros iframes
                else -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }
}

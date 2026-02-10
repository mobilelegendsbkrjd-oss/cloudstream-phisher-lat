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
    override val supportedTypes = setOf(TvType.Live, TvType.Others)

    private val defaultPoster = "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return HomePageResponse(emptyList()) // sin paginación por ahora

        val doc = app.get(mainUrl).document

        val elements = doc.select("article, .elementor-post, .post-item, a[href*='/tv/coli']").mapNotNull { el ->
            val href = el.attr("abs:href").takeIf { it.contains("/tv/coli") } ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, .entry-title")?.text()?.trim() ?: "Evento sin título"

            SearchResponse(
                name = title,
                url = href,
                apiName = name,
                type = TvType.Live,
                posterUrl = defaultPoster,
                year = null,
                id = null
            )
        }.distinctBy { it.url }

        return newHomePageResponse {
            list(
                name = "Eventos y Repeticiones",
                list = elements
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 30).document

        val rawTitle = doc.title().substringBeforeLast(" - TV LatinLuchas").trim()
        val title = rawTitle.ifBlank { "Evento en vivo" }

        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst(".elementor-widget-container p, .elementor-text-editor")?.text()
            ?: "Repetición o transmisión en vivo - TV LatinLuchas"

        return newLiveStreamLoadResponse(title) {
            data = url
            apiName = name
            type = TvType.Live
            posterUrl = defaultPoster
            plot = description
            comingSoon = title.contains("No disponible", ignoreCase = true) ||
                         description.contains("No disponible", ignoreCase = true)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 35).document
        var foundAny = false

        doc.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("abs:src").trim()
            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"

            when {
                // OK.ru
                src.contains("ok.ru/videoembed") -> {
                    callback(newExtractorLink("OkRu", "Opción 1 - OK.ru", src, data, Qualities.Unknown.value))
                    foundAny = true
                }

                // Dailymotion
                src.contains("dailymotion.com/embed/video") -> {
                    callback(newExtractorLink("Dailymotion", "Opción 4 - Dailymotion (English)", src, data, Qualities.Unknown.value))
                    foundAny = true
                }

                // Bysekoze
                src.contains("bysekoze.com") || src.contains("filemoon") -> {
                    try {
                        val mediaId = src.substringAfterLast("/")
                        val host = src.substringAfter("https://").substringBefore("/")

                        val apiUrl = "https://$host/api/videos/$mediaId/embed/playback"

                        val response = app.get(apiUrl, headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        ), timeout = 20)

                        if (response.isSuccessful) {
                            val json = JSONObject(response.text)
                            if (json.has("sources")) {
                                val sources = json.getJSONArray("sources")
                                for (i in 0 until sources.length()) {
                                    val s = sources.getJSONObject(i)
                                    val url = s.optString("url") ?: continue
                                    val label = s.optString("label", "Bysekoze ${i + 1}")

                                    callback(newExtractorLink("Bysekoze", "Opción 3 - $label", url, src, Qualities.Unknown.value, url.contains(".m3u8")))
                                    foundAny = true
                                }
                            }
                        }
                    } catch (_: Throwable) { }

                    // Fallback WebView
                    callback(newExtractorLink("BysekozeWeb", "Opción 3 - Bysekoze (navegador)", src, data, Qualities.Unknown.value, false).apply {
                        this.isDownloadSafe = true // evita que intente extraer
                    })
                    foundAny = true
                }

                // upns.online
                src.contains("latinlucha.upns.online") -> {
                    callback(newExtractorLink("UpnsWeb", "Opción 2 - upns.online (navegador)", src, data, Qualities.Unknown.value, false).apply {
                        this.isDownloadSafe = true
                    })
                    foundAny = true
                }

                // Genérico
                else -> {
                    callback(newExtractorLink("Generic", "Reproductor externo", src, data, Qualities.Unknown.value, false).apply {
                        this.isDownloadSafe = true
                    })
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}

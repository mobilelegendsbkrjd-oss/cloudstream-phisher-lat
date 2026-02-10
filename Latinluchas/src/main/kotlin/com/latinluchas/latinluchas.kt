// Latinluchas/src/main/kotlin/com/latinluchas/LatinLuchas.kt

package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class LatinLuchas : MainAPI() {
    override val name = "TV LatinLuchas"
    override val mainUrl = "https://tv.latinluchas.com/tv"
    override val hasMainPage = true
    override val lang = "es"
    override val supportedTypes = setOf(TvType.Live)

    private val defaultPoster = "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse { list(emptyList()) }

        val document = app.get(mainUrl).document

        val home = document.select("article, .elementor-post, .post, a[href*='/tv/coli']").mapNotNull { element ->
            val href = element.attr("abs:href").takeIf { it.contains("/tv/coli") } ?: return@mapNotNull null
            val title = element.selectFirst("h2, h3, .entry-title, a")?.text()?.trim() ?: "Evento sin título"

            newSearchResponse(title, href, TvType.Live) {
                posterUrl = defaultPoster
            }
        }.distinctBy { it.url }

        return newHomePageResponse {
            name = "Eventos y Repeticiones"
            list(home)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.title().substringBefore(" - TV LatinLuchas").trim()
            .ifBlank { "Evento en vivo" }

        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".elementor-widget-container p, .elementor-text-editor")?.text()
            ?: "Repetición o transmisión en vivo - TV LatinLuchas"

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
        val response = app.get(data).text
        val videoLinks = mutableSetOf<String>()
        var success = false

        // 1. IFRAMES PRINCIPALES (como en Tlnovelas)
        val iframePattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
        iframePattern.findAll(response).forEach { match ->
            val link = match.groupValues[1]
            if (!link.contains("ads") && !link.contains("google") && !link.contains("analytics")) {
                videoLinks.add(link)
            }
        }

        // 2. Búsqueda agresiva de enlaces de video (mp4, m3u8, embed, player, etc.)
        val directPatterns = listOf(
            Regex("""https?://[^"'\s]+\.(mp4|m3u8|mkv|avi|mov|flv|wmv|webm)[^"'\s]*""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^"'\s]+/v/[/\w\.]+)"""),
            Regex("""(https?://[^"'\s]+/embed/[/\w\.]+)"""),
            Regex("""(https?://[^"'\s]+/player/[/\w\.]+)"""),
            Regex("""(https?://[^"'\s]+/e/[/\w\.]+)""")  // para bysekoze e/...
        )

        directPatterns.forEach { pattern ->
            pattern.findAll(response).forEach { match ->
                val link = match.value
                videoLinks.add(link)
            }
        }

        // 3. API específica para bysekoze si detectamos su dominio
        if (response.contains("bysekoze.com") || response.contains("filemoon")) {
            val bysekozeMatch = Regex("""src=["'](https?://[^"']*bysekoze[^"']*/e/[^"']+)["']""").find(response)
            bysekozeMatch?.let { match ->
                val src = match.groupValues[1]
                val mediaId = src.substringAfterLast("/")
                val host = src.substringAfter("https://").substringBefore("/")

                val apiUrl = "https://$host/api/videos/$mediaId/embed/playback"

                val apiResp = app.get(apiUrl, headers = mapOf(
                    "Referer" to data,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                ))

                if (apiResp.isSuccessful) {
                    val json = JSONObject(apiResp.text)
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
                            success = true
                        }
                    }
                }
            }
        }

        // Procesar todos los enlaces encontrados
        app.postNotification("LatinLuchas: Encontrados ${videoLinks.size} enlaces potenciales")

        videoLinks.distinct().forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    success = true
                    app.postNotification("Éxito con: $link")
                }
            } catch (e: Exception) {
                // Continuar con el siguiente
            }
        }

        return success || videoLinks.isNotEmpty()
    }
}

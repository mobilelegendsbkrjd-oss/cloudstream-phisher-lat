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

    override suspend fun getMainPage(): HomePageResponse {
        val doc = app.get(mainUrl).document

        val homeItems = mutableListOf<HomePageList>()

        // Buscamos los posts de eventos (ajusta selectores si la home cambia)
        val posts = doc.select("article, .elementor-post, .post, .elementor-widget-container a[href*='/tv/coli']")

        val elements = posts.mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("abs:href").takeIf { it.contains("/tv/coli") } ?: return@mapNotNull null
            val titleEl = el.selectFirst("h2, h3, .entry-title, a")
            val title = titleEl?.text()?.trim() ?: "Evento sin título"

            HomePageListElement(
                name = title,
                url = href,
                posterImage = defaultPoster,
                type = TvType.Live
            )
        }.distinctBy { it.url }

        if (elements.isNotEmpty()) {
            homeItems.add(
                HomePageList(
                    name = "Eventos y Repeticiones",
                    list = elements,
                    isHorizontalImages = false
                )
            )
        }

        return HomePageResponse(homeItems, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 30).document

        val rawTitle = doc.title().substringBeforeLast(" - TV LatinLuchas").trim()
        val title = rawTitle.ifBlank { "Evento en vivo" }

        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst(".elementor-widget-container p, .elementor-text-editor")?.text()
            ?: "Repetición o transmisión en vivo - TV LatinLuchas"

        return LiveStreamLoadResponse(
            data = url,
            name = title,
            type = TvType.Live,
            url = url,
            posterUrl = defaultPoster,
            plot = description,
            comingSoon = title.contains("No disponible", ignoreCase = true) ||
                         description.contains("No disponible", ignoreCase = true)
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(data, timeout = 35).document

        // Extraemos todos los iframes de reproductor
        doc.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("abs:src").trim()
            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"

            when {
                // Opción 1 - OK.ru
                src.contains("ok.ru/videoembed") -> {
                    callback(
                        ExtractorLink(
                            "OkRu",
                            "Opción 1 - OK.ru",
                            src,
                            referer = data,
                            quality = Qualities.Unknown.value
                        )
                    )
                }

                // Opción 4 - Dailymotion
                src.contains("dailymotion.com/embed/video") -> {
                    callback(
                        ExtractorLink(
                            "Dailymotion",
                            "Opción 4 - Dailymotion (English)",
                            src,
                            referer = data,
                            quality = Qualities.Unknown.value
                        )
                    )
                }

                // Opción 3 - bysekoze.com / filemoon
                src.contains("bysekoze.com") || src.contains("filemoon") -> {
                    try {
                        val mediaId = src.substringAfterLast("/")
                        val host = src.substringAfter("https://").substringBefore("/")

                        val apiUrl = "https://$host/api/videos/$mediaId/embed/playback"

                        val response = app.get(
                            apiUrl,
                            headers = mapOf(
                                "Referer" to data,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            ),
                            timeout = 20
                        )

                        if (response.isSuccessful && response.text.isNotBlank()) {
                            val json = JSONObject(response.text)

                            if (json.has("sources")) {
                                val sources = json.getJSONArray("sources")
                                for (i in 0 until sources.length()) {
                                    val s = sources.getJSONObject(i)
                                    val url = s.optString("url") ?: continue
                                    val label = s.optString("label", "Bysekoze ${i + 1}")

                                    callback(
                                        ExtractorLink(
                                            "Bysekoze",
                                            "Opción 3 - $label",
                                            url,
                                            referer = src,
                                            quality = Qualities.Unknown.value,
                                            isM3u8 = url.contains(".m3u8")
                                        )
                                    )
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // fall-through
                    }

                    // Siempre fallback WebView
                    callback(
                        ExtractorLink(
                            "BysekozeWeb",
                            "Opción 3 - Bysekoze (navegador)",
                            src,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            extract = false
                        )
                    )
                }

                // Opción 2 - upns.online
                src.contains("latinlucha.upns.online") -> {
                    callback(
                        ExtractorLink(
                            "UpnsWeb",
                            "Opción 2 - upns.online (navegador)",
                            src,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            extract = false
                        )
                    )
                }

                // Cualquier otro reproductor desconocido
                else -> {
                    callback(
                        ExtractorLink(
                            "GenericIframe",
                            "Reproductor externo",
                            src,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            extract = false
                        )
                    )
                }
            }
        }
    }
}

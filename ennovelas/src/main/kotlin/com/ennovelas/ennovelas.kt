package com.ennovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.jsoup.nodes.Document
import java.util.Base64

class EnNovelas : MainAPI() {

    override var mainUrl = "https://l.ennovelas-tv.com"
    override var name = "EnNovelas"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "es-ES,es;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/episodes" to "Últimos Capítulos",
        "$mainUrl/series" to "Series",
        "$mainUrl/movies" to "Películas"
    )

    // =============================
    // MAIN PAGE
    // =============================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(request.data).document

        val items = doc.select(".block-post").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
            val img = element.selectFirst("img")?.attr("data-img")
                ?.ifEmpty { element.selectFirst("img")?.attr("src") }
                ?: ""

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img)
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items))
        )
    }

    // =============================
    // SEARCH
    // =============================

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select(".block-post").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
            val img = element.selectFirst("img")?.attr("data-img")
                ?.ifEmpty { element.selectFirst("img")?.attr("src") }
                ?: ""

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img)
            }
        }
    }

    // =============================
    // LOAD SERIES / MOVIE
    // =============================

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.ownText()?.trim() ?: return null
        val description = doc.selectFirst(".postDesc, .post-entry, .story")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("img.imgLoaded, img[alt*='poster'], .poster img")
            ?.attr("data-img")
            ?.ifEmpty { doc.selectFirst("img")?.attr("src") }
            ?: ""

        val isMovie = url.contains("/movies/") || url.contains("/pelicula/")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = fixUrlNull(poster)
                plot = description
            }
        }

        val episodes = doc.select("ul.eplist a.epNum").mapIndexed { index, a ->
            val epUrl = fixUrl(a.attr("href"))
            val epName = a.selectFirst("span")?.text()?.trim()
                ?: "Episodio ${index + 1}"

            newEpisode(epUrl) {
                name = epName
                episode = index + 1
                season = 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = fixUrlNull(poster)
            plot = description
        }
    }

    // =============================
    // LOAD LINKS (PARTE IMPORTANTE)
    // =============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {

        val doc: Document = app.get(data).document

        // Extraer URL del botón
        val proxyUrl = doc.selectFirst("a[href*='a.poiw.online/enn.php?post=']")
            ?.attr("href")
            ?: return@coroutineScope false

        val base64Part = proxyUrl.substringAfter("post=").trim()

        val servers: Map<String, String> = try {
            val decoded = String(Base64.getDecoder().decode(base64Part))
            Json.decodeFromString(decoded)
        } catch (e: Exception) {
            return@coroutineScope false
        }

        if (servers.isEmpty()) return@coroutineScope false

        fun fixEmbed(url: String): String {
            return url.replace("\\/", "/")
                .replace("uqload.net", "uqload.to")
                .replace("uqload.com", "uqload.to")
                .replace("vidspeeds.com", "vidsspeeds.com")
                .replace("vidhide.com", "vidhidepro.com")
        }

        var found = false

        // Prioridad de servidores (VK al final)
        val priority = listOf("uqload", "vidspeeds", "vk")

        priority.forEach { key ->
            servers[key]?.let { raw ->
                val embedUrl = fixEmbed(raw)

                val referer = when {
                    embedUrl.contains("vk.com") -> "https://vk.com/"
                    embedUrl.contains("okcdn.ru") -> "https://vk.com/"
                    else -> data
                }

                val resolved = loadExtractor(
                    url = embedUrl,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (resolved) found = true
            }
        }

        return@coroutineScope found
    }
}

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

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "es-ES,es;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/episodes" to "Últimos Capítulos",
        "$mainUrl/series" to "Series",
        "$mainUrl/movies" to "Películas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(request.data, headers = commonHeaders).document
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
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = commonHeaders).document
        return doc.select(".block-post").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
            val img = element.selectFirst("img")?.attr("data-img")
                ?.ifEmpty { element.selectFirst("img")?.attr("src") } ?: ""
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = commonHeaders).document
        val title = doc.selectFirst("h1")?.ownText()?.trim() ?: return null
        val description = doc.selectFirst(".postDesc, .post-entry, .story")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("img.imgLoaded, img[alt*='poster'], .poster img")
            ?.attr("data-img")?.ifEmpty { doc.selectFirst("img")?.attr("src") } ?: ""

        val isMovie = url.contains("/movies/") || url.contains("/pelicula/")
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = fixUrlNull(poster)
                plot = description
            }
        }

        val episodes = doc.select("ul.eplist a.epNum").mapIndexed { index, a ->
            val epUrl = fixUrl(a.attr("href"))
            val epName = a.selectFirst("span")?.text()?.trim() ?: "Episodio ${index + 1}"
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val episodeDoc = app.get(data, headers = commonHeaders).document

        val proxyUrl = episodeDoc.selectFirst("a[href*='a.poiw.online/enn.php?post=']")?.attr("href")
            ?: return@coroutineScope false

        // Intentamos cargar la página del proxy con referer del episodio
        val proxyResponse = app.get(proxyUrl, referer = data, headers = commonHeaders)
        if (!proxyResponse.isSuccessful) return@coroutineScope false

        val proxyDoc = proxyResponse.document

        // Extraer base64 del post= (por si acaso)
        val base64Part = proxyUrl.substringAfter("post=").trim()

        val servers: Map<String, String>? = try {
            val decoded = String(Base64.getDecoder().decode(base64Part))
            Json.decodeFromString(decoded)
        } catch (e: Exception) {
            null
        }

        if (servers.isNullOrEmpty()) return@coroutineScope false

        // Limpieza de dominios
        fun fixEmbed(url: String): String = url.replace("\\/", "/")
            .replace("uqload.net", "uqload.to")
            .replace("vidspeeds.com", "vidsspeeds.com")
            .replace("vidhide.com", "vidhidepro.com")

        var found = false

        // Prioridad de servidores
        listOf("vk", "uqload", "vidsspeeds", "vidspeeds").forEach { key ->
            servers[key]?.let { raw ->
                val embedUrl = fixEmbed(raw)

                // Referer del proxy (clave para pasar anti-bot)
                val embedReferer = proxyUrl

                val success = loadExtractor(
                    url = embedUrl,
                    referer = embedReferer,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (success) found = true
            }
        }

        return@coroutineScope found
    }
}

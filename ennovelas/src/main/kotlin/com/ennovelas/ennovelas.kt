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

    override val mainPage = mainPageOf(
        "$mainUrl/episodes" to "Últimos Capítulos",
        "$mainUrl/series" to "Series",
        "$mainUrl/movies" to "Películas"
    )

    // getMainPage y search se quedan igual (ya funcionan)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = when (request.name) {
            "Últimos Capítulos" -> "$mainUrl/episodes"
            "Series" -> "$mainUrl/series"
            "Películas" -> "$mainUrl/movies"
            else -> "$mainUrl/episodes"
        }
        val doc = app.get(url).document
        val items = doc.select(".block-post").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".title")?.text()?.trim() ?: ""
            var img = element.selectFirst("img")?.let { it.attr("data-img").ifEmpty { it.attr("src") } } ?: ""
            if (img.contains("grey.gif") || img.isEmpty()) {
                element.selectFirst(".imgSer")?.attr("data-img")?.let { bg ->
                    img = bg.substringAfter("url(").substringBefore(")")
                }
            }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(img)
            }
        }
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select(".block-post").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".title")?.text()?.trim() ?: ""
            val img = element.selectFirst("img")?.let { it.attr("data-img").ifEmpty { it.attr("src") } } ?: ""
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(img)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.ownText()?.trim() ?: return null
        val description = doc.selectFirst(".postDesc, .post-entry, .story")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("img.imgLoaded, img[alt*='poster'], .poster img")?.attr("data-img")
            ?.ifEmpty { doc.selectFirst("img")?.attr("src") } ?: ""
        val isMovie = url.contains("/movies/") || url.contains("/pelicula/")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        }
        val episodes = doc.select("ul.eplist a.epNum").mapIndexed { idx, a ->
            val epUrl = a.attr("href").let { if (it.startsWith("/")) mainUrl + it else it }
            val epName = a.selectFirst("span")?.text()?.trim() ?: "Episodio ${idx + 1}"
            newEpisode(epUrl) {
                name = epName
                episode = idx + 1
                season = 1
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val episodeDoc = app.get(data).document

        // Paso 3: Encontrar botón "Ver Capítulo" y su href al blog/proxy
        var proxyUrl: String? = episodeDoc.selectFirst("a[href*='a.poiw.online/enn.php?post=']")?.attr("href")
        if (proxyUrl == null) {
            val regex = Regex("""https?://a\.poiw\.online/enn\.php\?post=([A-Za-z0-9+/=]+)""")
            proxyUrl = regex.find(episodeDoc.outerHtml())?.value
        }

        if (proxyUrl.isNullOrBlank()) return@coroutineScope false

        // Paso 4: Cargar la página intermedia (blog) con referer = página episodio
        val proxyDoc = app.get(proxyUrl, referer = data).document

        // Decodificar base64 del post= (ya lo tenemos de la URL)
        val base64Part = proxyUrl.substringAfter("post=").substringBefore("&").trim()
        val servers: Map<String, String>? = try {
            val decoded = Base64.getDecoder().decode(base64Part)
            val json = String(decoded, Charsets.UTF_8)
            Json.decodeFromString<Map<String, String>>(json)
        } catch (e: Exception) {
            null
        }

        if (servers.isNullOrEmpty()) return@coroutineScope false

        // Limpieza de URLs
        fun fixEmbed(url: String): String = url.replace("\\/", "/")
            .replace("uqload.net", "uqload.to")
            .replace("uqload.com", "uqload.to")
            .replace("vidspeeds.com", "vidsspeeds.com")
            .replace("vidhidepremium.com", "vidhidepro.com")

        var found = false

        servers.forEach { (serverName, rawEmbed) ->
            val embedUrl = fixEmbed(rawEmbed)

            val displayName = when (serverName.lowercase()) {
                "vk" -> "VK.com"
                "vidsspeeds", "vidspeeds" -> "Vidspeeds"
                "uqload" -> "Uqload"
                else -> serverName.uppercase()
            }

            // Paso 5: Resolver video con referer = página intermedia (proxyUrl)
            val resolved = loadExtractor(
                url = embedUrl,
                referer = proxyUrl,  // ← CAMBIO CLAVE: referer del blog/proxy
                subtitleCallback = subtitleCallback,
                callback = callback
            )

            if (resolved) found = true

            // Fallback directo con referer proxy
            if (!resolved) {
                val link = newExtractorLink(
                    source = displayName,
                    name = "$displayName - $serverName (fallback)",
                    url = embedUrl
                )
                link.referer = proxyUrl  // ← Referer proxy
                link.quality = Qualities.Unknown.value
                callback(link)
                found = true
            }
        }

        return@coroutineScope found
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(request.data).document
        val items = doc.select(".block-post").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
            var img = element.selectFirst("img")?.let { it.attr("data-img").ifEmpty { it.attr("src") } } ?: ""
            if (img.contains("grey.gif") || img.isEmpty()) {
                element.selectFirst(".imgSer")?.attr("data-img")?.let { bg ->
                    img = bg.substringAfter("url(").substringBefore(")")
                }
            }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img)
            }
        }
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select(".block-post").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
            val img = element.selectFirst("img")?.let { it.attr("data-img").ifEmpty { it.attr("src") } } ?: ""
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
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
        // 1. Página del episodio
        val episodeDoc = app.get(data).document

        // 2. Botón "Ver Capítulo" → proxy
        val proxyUrl = episodeDoc.selectFirst("a[href*='a.poiw.online/enn.php?post=']")?.attr("href")
            ?: return@coroutineScope false

        // 3. Intentar cargar el proxy con referer del episodio
        val proxyResponse = app.get(proxyUrl, referer = data)
        if (!proxyResponse.isSuccessful) return@coroutineScope false
        val proxyDoc = proxyResponse.document

        // 4. Decodificar base64 del post= (fallback si no hay iframes)
        val base64Part = proxyUrl.substringAfter("post=").trim()
        val servers = try {
            val decoded = String(Base64.getDecoder().decode(base64Part))
            Json.decodeFromString<Map<String, String>>(decoded)
        } catch (e: Exception) { null }

        // 5. Buscar iframes o scripts en el proxy (lo que carga en navegador)
        val iframes = proxyDoc.select("iframe[src]").map { it.attr("abs:src") }.filter { it.isNotBlank() }
        val scriptUrls = proxyDoc.select("script").mapNotNull { script ->
            script.data().takeIf { it.contains("url =") || it.contains("file:") || it.contains(".mp4") || it.contains(".m3u8") }
                ?.let { data ->
                    data.substringAfter("url = '").substringBefore("'")
                        .ifBlank { data.substringAfter("file: \"").substringBefore("\"") }
                        .ifBlank { null }
                }
        }

        val allEmbeds = (iframes + scriptUrls).distinct().filter { it.isNotBlank() }

        var found = false

        // 6. Resolver embeds encontrados (prioridad iframes > base64)
        allEmbeds.forEach { embed ->
            val clean = embed.replace("\\/", "/")
                .replace("uqload.net", "uqload.to")
                .replace("vidspeeds.com", "vidsspeeds.com")

            val success = loadExtractor(
                url = clean,
                referer = proxyUrl,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            if (success) found = true
        }

        // 7. Fallback al JSON base64 si no hay iframes
        if (!found && servers != null) {
            servers.forEach { (serverName, rawEmbed) ->
                val embedUrl = rawEmbed.replace("\\/", "/")
                    .replace("uqload.net", "uqload.to")
                    .replace("vidspeeds.com", "vidsspeeds.com")

                val displayName = when (serverName.lowercase()) {
                    "vk" -> "VK.com"
                    "vidsspeeds", "vidspeeds" -> "Vidspeeds"
                    "uqload" -> "Uqload"
                    else -> serverName.uppercase()
                }

                val success = loadExtractor(
                    url = embedUrl,
                    referer = proxyUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                if (success) found = true

                // Último fallback directo
                if (!success) {
                    callback(
                        newExtractorLink(
                            source = displayName,
                            name = "$displayName - fallback",
                            url = embedUrl
                        ) {
                            this.referer = proxyUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            }
        }

        return@coroutineScope found
    }
}

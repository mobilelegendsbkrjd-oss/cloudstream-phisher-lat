package com.ennovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    // ────────────────────────────────────────────────
    // getMainPage, search y load → se mantienen casi iguales (solo pequeños fixes)
    // ────────────────────────────────────────────────

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

            // Fix para imágenes lazy con background
            if (img.contains("grey.gif") || img.isEmpty()) {
                element.selectFirst(".imgSer")?.attr("data-img")?.let { bg ->
                    img = bg.substringAfter("url(").substringBefore(")")
                }
            }

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(img)
            }
        }

        return HomePageResponse(listOf(HomePageList(request.name, items)))
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

        // Series: episodios en .eplist > a.epNum
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

    // ────────────────────────────────────────────────
    // loadLinks → AQUÍ ESTÁ LA MAGIA (decodificar el post= base64)
    // ────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val doc: Document = app.get(data).document

        // 1. Buscar el enlace del proxy pooiw
        var pooiwUrl: String? = doc.selectFirst("a[href*='a.poiw.online/enn.php?post=']")?.attr("href")

        // Si no aparece en <a>, buscar en todo el HTML (a veces está en texto o script)
        if (pooiwUrl == null) {
            val regexMatch = Regex("""https?://a\.poiw\.online/enn\.php\?post=([A-Za-z0-9+/=]+)""")
                .find(doc.outerHtml())
            pooiwUrl = regexMatch?.value
        }

        if (pooiwUrl.isNullOrBlank()) return@coroutineScope false

        // 2. Extraer la parte base64 después de post=
        val base64Part = pooiwUrl.substringAfter("post=").substringBefore("&").trim()

        // 3. Decodificar base64 → JSON
        val servers: Map<String, String>? = try {
            val decodedBytes = Base64.getDecoder().decode(base64Part)
            val jsonStr = String(decodedBytes, Charsets.UTF_8)
            json.decodeFromString<Map<String, String>>(jsonStr)
        } catch (e: Exception) {
            null
        }

        if (servers.isNullOrEmpty()) return@coroutineScope false

        // 4. Cargar cada servidor como extractor link
        servers.forEach { (serverName, embedUrl) ->
            // Limpiar posibles escapes en la URL
            val cleanUrl = embedUrl.replace("\\/", "/")

            // Puedes priorizar o renombrar servers si quieres
            val name = when (serverName.lowercase()) {
                "vk" -> "VK.com"
                "vidsspeeds", "vidspeeds" -> "Vidspeeds"
                "uqload" -> "Uqload"
                else -> serverName.uppercase()
            }

            // Enviar al extractor (CloudStream intentará resolverlo automáticamente)
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name - $serverName",
                    url = cleanUrl,
                    referer = data,
                    quality = Qualities.Unknown.value,
                    isM3u8 = cleanUrl.contains(".m3u8") || cleanUrl.contains("master.m3u8"),
                    headers = mapOf("Referer" to "https://l.ennovelas-tv.com/")
                )
            )
        }

        return@coroutineScope true
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

package com.pelisgratishd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.delay

class PelisGratisHD : MainAPI() {
    override var mainUrl = "https://www.pelisgratishd.net"
    override var name = "PelisGratisHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // URL del archivo de configuración en GitHub
    private val configUrl = "https://raw.githubusercontent.com/tu-usuario/tu-repo/main/pelisgratishd-config.json"

    // Configuración por defecto (se actualizará desde GitHub)
    private var currentConfig = CloudflareConfig(
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        cookies = mapOf(
            "cf_clearance" to "W.Y3VnM4QJF_eY68R866Te.OoyfqJGsU3lBZSVzCV6o-1770225451-1.2.1.1-yF4q6VlfO4oa.cfVpL0B_WrB3TXE1Pvn.qq3XYKopxpUa4Ap0PepuS896HNI9yFFKJRMSu_ren_RcGQBnDaO2ew.wNDvFvdolp4ZPpR29EZ6Zr1I_3aF3KDX_wpHmTj4yai3PwBQHWuU_eiCbPYVnUFJ59NeQoZg0rl9jHUAmz4LYzZO5ujd53Z_oRDnaoiP3_jYprlDzMpy_zF7pixan7iOpKb0IYbDeV5chgcAAO4",
            "pelisgratishd_session" to "eyJpdiI6IlpuT2ZNUzNIUzA0RzZNNGtNcXI3WXc9PSIsInZhbHVlIjoiMVpUakFGYXBzWUQxYTJCL21Bb3dPOVMwcDZ1SDN1TGs4RzJJVnpvS3lHNDJVWXpsOGorVHZCWDU4Nm10YmdkdFpmelN5cnZSRnNpOUwyVS8vNVJUY0VVT3JYRDAzbGJUOGlMWnRjM2h2VG04K3F5K0hSa0c3UFFTU2lJcDZ2NTMiLCJtYWMiOiJlM2JmNGE4YjVhMGZiNGY3Y2QxMDZiYzBjYTVlNzIzZjhkNzhmM2MxNmZhNzYxYzlmYzgyNWNiMzQ1MTFmZWUzIiwidGFnIjoiIn0%3D"
        ),
        headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Cache-Control" to "max-age=0"
        )
    )

    private var configLastUpdated = 0L
    private val configUpdateInterval = 3600000L // 1 hora en milisegundos

    // Estructura de configuración
    data class CloudflareConfig(
        val userAgent: String,
        val cookies: Map<String, String>,
        val headers: Map<String, String>,
        val lastUpdated: String = ""
    )

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas" to "🎬 Películas",
        "$mainUrl/series" to "📺 Series",
        "$mainUrl/peliculas/genero/accion" to "🔥 Acción",
        "$mainUrl/peliculas/genero/comedia" to "😂 Comedia",
        "$mainUrl/peliculas/genero/terror" to "👻 Terror",
        "$mainUrl/peliculas/genero/drama" to "🎭 Drama",
        "$mainUrl/peliculas/genero/romance" to "💖 Romance",
        "$mainUrl/peliculas/genero/ciencia-ficcion" to "🚀 Ciencia Ficción",
        "$mainUrl/peliculas/estreno" to "🆕 Estrenos",
        "$mainUrl/peliculas/mas-vistas" to "👀 Más Vistas"
    )

    /* =========================
       ACTUALIZACIÓN DE CONFIGURACIÓN
       ========================= */

    private suspend fun updateConfigIfNeeded() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - configLastUpdated > configUpdateInterval) {
            try {
                val response = app.get(configUrl, timeout = 30)
                if (response.code == 200) {
                    val json = response.text

                    // Parsear JSON simple
                    val userAgent = extractFromJson(json, "userAgent") ?: currentConfig.userAgent
                    val cookies = parseCookiesFromJson(json)
                    val headers = parseHeadersFromJson(json)

                    currentConfig = CloudflareConfig(
                        userAgent = userAgent,
                        cookies = cookies.ifEmpty { currentConfig.cookies },
                        headers = headers.ifEmpty { currentConfig.headers },
                        lastUpdated = extractFromJson(json, "lastUpdated") ?: ""
                    )

                    configLastUpdated = currentTime
                }
            } catch (e: Exception) {
                // Silenciar error, usar configuración por defecto
            }
        }
    }

    private fun extractFromJson(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun parseCookiesFromJson(json: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()

        // Buscar la sección de cookies
        val cookiesPattern = "\"cookies\"\\s*:\\s*\\{([^}]+)\\}".toRegex()
        val cookiesMatch = cookiesPattern.find(json)

        cookiesMatch?.groupValues?.get(1)?.let { cookiesStr ->
            val cookiePattern = "\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            cookiePattern.findAll(cookiesStr).forEach { match ->
                cookies[match.groupValues[1]] = match.groupValues[2]
            }
        }

        return cookies
    }

    private fun parseHeadersFromJson(json: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        val headersPattern = "\"headers\"\\s*:\\s*\\{([^}]+)\\}".toRegex()
        val headersMatch = headersPattern.find(json)

        headersMatch?.groupValues?.get(1)?.let { headersStr ->
            val headerPattern = "\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            headerPattern.findAll(headersStr).forEach { match ->
                headers[match.groupValues[1]] = match.groupValues[2]
            }
        }

        return headers
    }

    private fun getHeaders(referer: String = ""): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        // Agregar User-Agent
        headers["User-Agent"] = currentConfig.userAgent

        // Agregar headers de configuración
        headers.putAll(currentConfig.headers)

        // Agregar referer si se proporciona
        if (referer.isNotEmpty()) {
            headers["Referer"] = referer
        }

        return headers
    }

    /* =========================
       UTILIDADES IMÁGENES
       ========================= */

    private fun fixImageUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }

    private fun getImage(el: Element?): String? {
        if (el == null) return null
        val candidates = listOf(
            el.attr("src"),
            el.attr("data-src"),
            el.attr("data-original"),
            el.attr("content")
        )

        for (src in candidates) {
            if (src.isNotBlank() && !src.startsWith("data:image")) {
                val fixed = fixImageUrl(src.trim())
                if (fixed.isNotBlank()) {
                    return fixed
                }
            }
        }
        return null
    }

    private fun extractPoster(doc: Document): String? {
        val metaTags = listOf(
            doc.selectFirst("meta[property=og:image]"),
            doc.selectFirst("meta[name=og:image]"),
            doc.selectFirst("meta[property=twitter:image]"),
            doc.selectFirst("meta[name=twitter:image]"),
            doc.selectFirst("meta[itemprop=image]")
        )

        metaTags.forEach { meta ->
            meta?.attr("content")?.let { url ->
                if (url.isNotBlank()) {
                    return fixImageUrl(url)
                }
            }
        }

        val imgSelectors = listOf(
            ".mi2-img img",
            ".poster img",
            ".cover img",
            ".thumbnail img",
            "img.poster",
            "img.cover",
            "img[itemprop=image]",
            "#poster img",
            ".single-poster img"
        )

        imgSelectors.forEach { selector ->
            doc.selectFirst(selector)?.let { img ->
                getImage(img)?.let { return it }
            }
        }

        doc.selectFirst("img")?.let { img ->
            getImage(img)?.let { return it }
        }

        return null
    }

    /* =========================
       REQUEST HELPER CON CLOUDFLARE BYPASS
       ========================= */

    private suspend fun makeRequest(
        url: String,
        referer: String = mainUrl,
        retryCount: Int = 2
    ): Document? {

        // Actualizar configuración si es necesario
        updateConfigIfNeeded()

        var attempt = 0

        while (attempt < retryCount) {
            try {
                val response = app.get(
                    url,
                    headers = getHeaders(referer),
                    cookies = currentConfig.cookies,
                    timeout = 45,
                    allowRedirects = true
                )

                // Verificar si es Cloudflare
                val html = response.text
                if (html.contains("Just a moment") || html.contains("cf-browser-verification")) {
                    // Si es Cloudflare, esperar y reintentar
                    attempt++
                    delay(2000L * attempt.toLong()) // Delay incremental
                    continue
                }

                return response.document

            } catch (e: Exception) {
                attempt++
                if (attempt >= retryCount) {
                    // Mostrar mensaje amigable al usuario
                    throw ErrorLoadingException(
                        "🔒 Cloudflare bloqueando acceso\n\n" +
                                "Solución:\n" +
                                "1. Abre Chrome/Edge en tu dispositivo\n" +
                                "2. Visita: $mainUrl\n" +
                                "3. Completa el captcha 'No soy un robot'\n" +
                                "4. Vuelve a intentar\n\n" +
                                "Esto actualizará las cookies automáticamente."
                    )
                }
                delay(1000L)
            }
        }

        return null
    }

    /* =========================
       HOME
       ========================= */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page" else request.data
        val doc = makeRequest(url) ?: return newHomePageResponse(emptyList(), false)

        val items = mutableListOf<SearchResponse>()

        doc.select(".movie-item2, article, .item").forEach { item ->
            try {
                val link = item.selectFirst("a") ?: return@forEach
                val href = link.attr("href").trim()
                if (href.isEmpty()) return@forEach

                val titleElement = item.selectFirst(".mi2-title, .title, h2, h3, .name, .entry-title")
                val title = titleElement?.text()?.trim() ?: return@forEach

                val imgElement = item.selectFirst("img")
                val poster = getImage(imgElement)

                val isSeries = href.contains("/series/") ||
                        href.contains("/serie/") ||
                        item.selectFirst(".serie-label, .tv-label") != null

                if (isSeries) {
                    items.add(newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                        posterUrl = poster
                    })
                } else {
                    items.add(newMovieSearchResponse(title, fixUrl(href)) {
                        posterUrl = poster
                    })
                }
            } catch (e: Exception) {
                // Continuar con siguiente item
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items.distinctBy { it.url }, false)),
            hasNext = items.isNotEmpty() && doc.select(".pagination, .next, .page-nav").isNotEmpty()
        )
    }

    /* =========================
       SEARCH
       ========================= */

    override suspend fun search(query: String): List<SearchResponse> {
        val searchQuery = query.replace(" ", "+")
        val url = "$mainUrl/buscar?q=$searchQuery"

        val doc = makeRequest(url) ?: return emptyList()
        val results = mutableListOf<SearchResponse>()

        doc.select(".movie-item2, .post, .movie-item, article").forEach { item ->
            try {
                val link = item.selectFirst("a") ?: return@forEach
                val href = link.attr("href").trim()
                if (href.isEmpty()) return@forEach

                val titleElement = item.selectFirst(".mi2-title, .title, h2, h3")
                val title = titleElement?.text()?.trim() ?: return@forEach

                val poster = getImage(item.selectFirst("img"))
                val fixedUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val isSeries = fixedUrl.contains("/series/") ||
                        fixedUrl.contains("/serie/") ||
                        item.selectFirst(".serie-label") != null

                if (isSeries) {
                    results.add(newTvSeriesSearchResponse(title, fixedUrl, TvType.TvSeries) {
                        posterUrl = poster
                    })
                } else {
                    results.add(newMovieSearchResponse(title, fixedUrl) {
                        posterUrl = poster
                    })
                }
            } catch (e: Exception) {
                // Continuar con siguiente item
            }
        }

        return results
    }

    /* =========================
       LOAD
       ========================= */

    override suspend fun load(url: String): LoadResponse? {
        val doc = makeRequest(url) ?: return null
        return loadContent(doc, url)
    }

    private suspend fun loadContent(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1, .title, .entry-title")?.text()?.trim() ?: return null
        val poster = extractPoster(doc)

        val plotSelectors = listOf(
            ".full-desc",
            ".full-text",
            ".description",
            ".sinopsis",
            ".content",
            "[itemprop=description]",
            ".entry-content"
        )

        var plot: String? = null
        for (selector in plotSelectors) {
            val element = doc.selectFirst(selector)
            if (element != null) {
                plot = element.text().trim()
                if (plot.isNotEmpty()) break
            }
        }

        val isSeries = url.contains("/series/") ||
                url.contains("/serie/") ||
                doc.select("a[href*='temporada'], a[href*='season'], .seasons, .episodes").isNotEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.plot = plot

                doc.select(".info, .metadata, .details").forEach { info ->
                    val text = info.text().lowercase()
                    when {
                        text.contains("año") || text.contains("año:") -> {
                            year = Regex("\\d{4}").find(info.text())?.value?.toIntOrNull()
                        }
                        text.contains("dura") || text.contains("duración") -> {
                            duration = Regex("\\d+").find(info.text())?.value?.toIntOrNull()
                        }
                        text.contains("calif") || text.contains("rating") -> {
                            val ratingStr = Regex("\\d+\\.?\\d*").find(info.text())?.value
                            ratingStr?.toFloatOrNull()?.let { ratingFloat ->
                                rating = (ratingFloat * 10).toInt()
                            }
                        }
                    }
                }
            }
        }

        val episodes = mutableListOf<Episode>()

        val seasonElements = doc.select("a[href*='temporada'], a[href*='season'], .season-item, .season-link")

        if (seasonElements.isNotEmpty()) {
            seasonElements.forEach { seasonElement ->
                val seasonUrl = fixUrl(seasonElement.attr("href"))
                if (seasonUrl.isBlank()) return@forEach

                val seasonNumber = extractNumber(seasonUrl, listOf("temporada", "season"))

                try {
                    val seasonDoc = makeRequest(seasonUrl, url) ?: return@forEach

                    val episodeElements = seasonDoc.select("a[href*='episodio'], a[href*='episode'], .episode-item, .episode-link")

                    if (episodeElements.isEmpty()) {
                        extractEpisodesFromPage(seasonDoc, seasonNumber, episodes, seasonUrl)
                    } else {
                        episodeElements.forEach { episodeElement ->
                            val episodeUrl = fixUrl(episodeElement.attr("href"))
                            if (episodeUrl.isBlank()) return@forEach

                            val episodeNumber = extractNumber(episodeUrl, listOf("episodio", "episode"))
                            val episodeTitle = episodeElement.selectFirst(".title, .name, h3")?.text()?.trim()

                            episodes.add(
                                newEpisode(episodeUrl) {
                                    this.season = seasonNumber
                                    this.episode = episodeNumber
                                    this.name = episodeTitle ?: "Episodio $episodeNumber"
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Continuar con siguiente temporada
                }
            }
        } else {
            extractEpisodesFromPage(doc, 1, episodes, url)
        }

        episodes.sortWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = poster
            this.plot = plot

            doc.select(".info, .metadata").forEach { info ->
                val text = info.text().lowercase()
                when {
                    text.contains("año") -> {
                        year = Regex("\\d{4}").find(info.text())?.value?.toIntOrNull()
                    }
                    text.contains("genero") || text.contains("género") -> {
                        tags = info.select("a").map { it.text().trim() }
                    }
                }
            }
        }
    }

    private fun extractNumber(url: String, patterns: List<String>): Int {
        for (pattern in patterns) {
            val regex = Regex("$pattern[_-]?(\\d+)", RegexOption.IGNORE_CASE)
            regex.find(url)?.let { match ->
                return match.groupValues[1].toIntOrNull() ?: 1
            }
        }
        return 1
    }

    private suspend fun extractEpisodesFromPage(
        doc: Document,
        seasonNumber: Int,
        episodes: MutableList<Episode>,
        referer: String
    ) {
        val episodePatterns = listOf(
            "episodio-\\d+",
            "episode-\\d+",
            "capitulo-\\d+",
            "chapter-\\d+"
        )

        doc.select("a").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                for (pattern in episodePatterns) {
                    if (href.contains(Regex(pattern, RegexOption.IGNORE_CASE))) {
                        val episodeNumber = extractNumber(href, listOf("episodio", "episode", "capitulo", "chapter"))
                        val title = link.text().trim()

                        episodes.add(
                            newEpisode(fixUrl(href)) {
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.name = if (title.isNotEmpty()) title else "Episodio $episodeNumber"
                            }
                        )
                        break
                    }
                }
            }
        }
    }

    /* =========================
       LINKS
       ========================= */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = makeRequest(data) ?: return false
        return extractLinksFromDocument(doc, data, subtitleCallback, callback)
    }

    private suspend fun extractLinksFromDocument(
        doc: Document,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        doc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").trim()
            if (src.isNotEmpty()) {
                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http")) {
                    loadExtractor(src, referer, subtitleCallback, callback)
                    found = true
                }
            }
        }

        val videoSelectors = listOf(
            "video source",
            "[data-video]",
            ".video-player",
            ".player-container",
            ".embed-container"
        )

        videoSelectors.forEach { selector ->
            doc.select(selector).forEach { element ->
                val videoSrc = element.attr("src") ?: element.attr("data-video") ?: element.attr("data-src")
                if (videoSrc.isNotBlank() && videoSrc.startsWith("http")) {
                    loadExtractor(videoSrc, referer, subtitleCallback, callback)
                    found = true
                }
            }
        }

        doc.select("a").forEach { link ->
            val href = link.attr("href").lowercase()
            if (href.contains("ver") || href.contains("watch") || href.contains("player")) {
                val text = link.text().lowercase()
                if (text.contains("ver") || text.contains("watch") || text.contains("online")) {
                    val url = fixUrl(link.attr("href"))
                    if (url.isNotBlank() && url.startsWith("http")) {
                        try {
                            val playerDoc = makeRequest(url, referer)
                            if (playerDoc != null) {
                                extractLinksFromDocument(playerDoc, url, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            // Continuar con siguiente enlace
                        }
                    }
                }
            }
        }

        return found
    }
}
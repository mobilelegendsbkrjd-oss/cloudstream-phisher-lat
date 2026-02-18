package com.doramasmp4

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log

class DoramasMP4 : MainAPI() {
    override var name = "DoramasMP4"
    override var mainUrl = "https://doramasmp4.io"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-MX,es;q=0.9",
        "Referer" to "$mainUrl/",
        "Connection" to "keep-alive"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/capitulos" to "Últimos Capítulos",
        "$mainUrl/estrenos" to "Estrenos",
        "$mainUrl/doramas" to "Doramas",
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/variedades" to "Variedades"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page > 1) "$baseUrl/page/$page" else baseUrl
        Log.d("DoramasMP4", "Scrapeando: $url")

        val doc = app.get(url, headers = headers).document

        val container = doc.selectFirst("div.sc-efHYUO.hmHDog") ?: doc.body()
        val items = container.select("article.sc-jNnpgg.rxQig").mapNotNull { it.toSearchResult() }

        Log.d("DoramasMP4", "Items encontrados: ${items.size}")

        return newHomePageResponse(request.name, items, hasNext = doc.select("a.next, .pagination a[href*='page/']").isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = selectFirst("a[href^='/capitulos/'], a[href^='/ver/']") ?: return null
        val href = linkElem.attr("abs:href")

        val seasonEpSpan = selectFirst("span.sc-cOifOu.hcqBJx")
        val seasonEp = seasonEpSpan?.text()?.trim() ?: ""

        val titleElem = selectFirst("h2")
        val title = titleElem?.text()?.trim() ?: ""
        val fullName = if (seasonEp.isNotBlank()) "$title ($seasonEp)" else title
        if (fullName.isBlank()) return null

        val poster = selectFirst("img")?.let { img ->
            img.attr("abs:src").takeIf { it.isNotBlank() && it.contains("tmdb.org") }
                ?: img.attr("abs:data-src")
        }

        val date = selectFirst("header > span:not(.sc-cOifOu)")?.text()?.trim() ?: ""

        val isMovie = href.contains("/pelicula/") || select(".pelicula").isNotEmpty()

        return if (isMovie) {
            newMovieSearchResponse(fullName, href, TvType.Movie) {
                posterUrl = poster
                            }
        } else {
            newTvSeriesSearchResponse(fullName, href, TvType.AsianDrama) {
                posterUrl = poster
                if (seasonEp.isNotBlank()) " • $seasonEp" else ""
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?q=$query"
        val doc = app.get(url, headers = headers).document

        val container = doc.selectFirst("div.sc-efHYUO.hmHDog") ?: doc.body()
        return container.select("article.sc-jNnpgg.rxQig").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1, h2.title, .entry-title")?.text()?.trim() ?: "Sin título"
        val poster = doc.selectFirst("img[src*='tmdb.org'], .poster img, meta[property='og:image']")?.attr("abs:content")
            ?: doc.selectFirst("img")?.attr("abs:src")
        val plot = doc.selectFirst(".sinopsis, .description, .entry-content p")?.text()?.trim()

        val episodes = doc.select("article.sc-jNnpgg.rxQig").mapNotNull { article ->
            val link = article.selectFirst("a[href^='/capitulos/'], a[href^='/ver/']") ?: return@mapNotNull null
            val epUrl = link.attr("abs:href")

            val seasonEp = article.selectFirst("span.sc-cOifOu")?.text()?.trim() ?: ""
            val epTitle = article.selectFirst("h2")?.text()?.trim() ?: ""
            val epNum = seasonEp.substringAfter("E").toIntOrNull() ?: 1
            val seasonNum = seasonEp.substringAfter("S").substringBefore(".").toIntOrNull() ?: 1

            newEpisode(epUrl) {
                name = epTitle.ifBlank { "Episodio $epNum" }
                episode = epNum
                season = seasonNum
                posterUrl = poster
                description = article.selectFirst("header > span:last-child")?.text()?.trim()
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document
        var found = false

        // Iframes (reproductores embebidos)
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src").trim()
            if (src.isNotBlank() && src.startsWith("http")) {
                Log.d("DoramasMP4", "Iframe encontrado: $src")
                loadExtractor(src, data, subtitleCallback, callback).also { if (it) found = true }
            }
        }

        // Fuentes directas de video
        doc.select("video source[src], source[src]").forEach { source ->
            val src = source.attr("abs:src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback).also { if (it) found = true }
            }
        }

        // Links de embeds comunes
        doc.select("a[href*='dood'], a[href*='streamtape'], a[href*='voe'], a[href*='mixdrop']").forEach { a ->
            val href = a.attr("abs:href")
            if (href.isNotBlank()) {
                loadExtractor(href, data, subtitleCallback, callback).also { if (it) found = true }
            }
        }

        // Regex para capturar cualquier embed escondido en scripts o texto
        val embedRegex = Regex("""https?://[^"'\s<>()]+(?:dood|voe|filemoon|streamwish|mp4upload|ok\.ru|player|embed|mixdrop|sbembed)[^"'\s<>()]*""", RegexOption.IGNORE_CASE)
        embedRegex.findAll(doc.html()).forEach { match ->
            Log.d("DoramasMP4", "Embed regex encontrado: ${match.value}")
            loadExtractor(match.value, data, subtitleCallback, callback).also { if (it) found = true }
        }

        return found
    }
}
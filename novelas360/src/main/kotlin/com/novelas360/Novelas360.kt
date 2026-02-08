package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ==============================
    // HTTP SAFE
    // ==============================
    private suspend fun getDoc(url: String): Document? =
        runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to mainUrl
                )
            ).document
        }.getOrNull()

    private fun fixUrl(url: String?): String? =
        if (url.isNullOrBlank()) null
        else if (url.startsWith("//")) "https:$url" else url

    // ==============================
    // MAIN PAGE (CATEGORÍAS)
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val categories = listOf(
            "Telenovelas México" to "/telenovelas/mexico/",
            "Telenovelas Turcas" to "/telenovelas/turcas/",
            "Telenovelas Colombianas" to "/telenovelas/colombianas/",
            "Telenovelas Brasileñas" to "/telenovelas/brasilenas/",
            "Telenovelas Argentinas" to "/telenovelas/argentinas/"
        )

        val home = categories.mapNotNull { (title, path) ->
            val doc = getDoc("$mainUrl$path") ?: return@mapNotNull null
            
            // FIX: Selector más genérico para WordPress (Videotube theme)
            // Busca 'article' o '.post'
            val items = doc.select("article, .post, .video").mapNotNull { it.toSearchResult() }

            if (items.isEmpty()) null else HomePageList(title, items)
        }

        return newHomePageResponse(home, false)
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query") ?: return emptyList()

        // FIX: Usar el mismo parser genérico que en mainPage
        return document.select("article, .post, .video").mapNotNull { it.toSearchResult() }
    }

    // ==============================
    // LOAD SERIE
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url) ?: throw ErrorLoadingException()

        val title = document.selectFirst("h1, h2.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("–")?.trim()
            ?: "Novela"

        val plot = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val poster = fixUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        // FIX: Selector de episodios. A veces están en listas o tablas.
        // Se mantiene el original pero se añade un fallback por si acaso cambia el diseño.
        val episodesAsc = document.select("div.item h3 a, .entry-content a[href*='/video/']")
            .distinctBy { it.attr("href") }
            .mapIndexedNotNull { index, el ->
                val epUrl = el.attr("href")
                if (epUrl.isBlank()) return@mapIndexedNotNull null

                newEpisode(epUrl) {
                    name = el.text().trim()
                    episode = index + 1
                }
            }

        // 🔥 ORDEN DESCENDENTE
        val episodes = episodesAsc.reversed()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.plot = plot
            this.posterUrl = poster
        }
    }

    // ==============================
    // LOAD LINKS
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = getDoc(data) ?: return false

        // FIX: Simplificación masiva.
        // 1. Buscamos todos los iframes.
        // 2. Se los pasamos a loadExtractor. Cloudstream maneja la lógica interna (Dailymotion, Okru, etc).
        // 3. Evitamos hacer requests manuales (app.get) para no romper por CORS o IO.

        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src")) ?: return@forEach
            
            // Pasamos la URL directamente al sistema de extractores de Cloudstream
            loadExtractor(src, data, subtitleCallback, callback)
        }
        
        return true
    }

    // ==============================
    // PARSER (Helper)
    // ==============================
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        if (href.isBlank()) return null

        // Títulos en WP suelen ser h2 o h3
        val title = selectFirst("h2, h3, .entry-title")?.text()?.trim() 
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }
}

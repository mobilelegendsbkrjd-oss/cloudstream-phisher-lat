package com.cinecalidad

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Cinecalidad : MainAPI() {
    override var mainUrl = "https://www.cinecalidad.ec"
    override var name = "Cinecalidad"
    override val hasMainPage = true
    override var lang = "es"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/ver-serie/page/" to "Series",
        "$mainUrl/page/" to "Peliculas",
        "$mainUrl/genero-de-la-pelicula/peliculas-en-calidad-4k/page/" to "4K UHD",
        "$mainUrl/fecha-de-lanzamiento/2024/" to "Estrenos",
        "$mainUrl/genero-de-la-pelicula/accion/" to "Acción",
        "$mainUrl/genero-de-la-pelicula/animacion/" to "Animación",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("page")) {
            request.data + page
        } else {
            request.data
        }

        val document = app.get(url).document
        val home = document.select(".item, article.item, .movies, .post").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("div.in_title, h2, .title, h3")
        val title = titleElement?.text()?.trim() ?: return null

        val href = attr("href").takeIf { it.isNotBlank() } ?: selectFirst("a")?.attr("href") ?: return null
        val image = selectFirst("img.lazy, img[data-src], img")?.attr("data-src")
            ?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: selectFirst("img")?.attr("src")
                ?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: ""

        val isMovie = href.contains("/ver-pelicula/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = image
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document

        return document.select(".item, article.item, .movies, .post").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".single_left h1, h1.entry-title")?.text()?.trim() ?: "Desconocido"
        val poster = document.selectFirst(".alignnone, img[data-src], img.size-full")?.attr("data-src")
            ?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()

        val description = document.selectFirst("div.single_left table tbody tr td p, .entry-content p")?.text()?.trim()

        // Extraer episodios para series
        val episodes = document.select("div.se-c div.se-a ul.episodios li, .episodios li, ul.episodios li").mapNotNull { li ->
            val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epThumb = li.selectFirst("img.lazy, img[data-src]")?.attr("data-src")
                ?.takeIf { it.isNotBlank() && it.startsWith("http") }
                ?: ""
            val name = li.selectFirst(".episodiotitle a, .title, a")?.text()?.trim() ?: "Episodio"

            val seasonInfo = li.selectFirst(".numerando")?.text() ?: ""
            val seasonid = seasonInfo.replace(Regex("(S|E)"), "").let { str ->
                str.split("-").mapNotNull { subStr -> subStr.toIntOrNull() }
            }

            val isValid = seasonid.size == 2
            val episode = if (isValid) seasonid.getOrNull(1) else null
            val season = if (isValid) seasonid.getOrNull(0) else null

            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = episode
                this.posterUrl = epThumb.takeIf { it.isNotBlank() }
            }
        }

        val isMovie = url.contains("/ver-pelicula/")

        return if (isMovie) {
            val movieUrl = document.selectFirst("#playeroptionsul li.dooplay_player_option")?.attr("data-option")
                ?: url

            newMovieLoadResponse(title, url, TvType.Movie, movieUrl) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Enlaces para ver online
        document.select("#playeroptionsul li.dooplay_player_option").forEach { element ->
            val url = element.attr("data-option")
            if (url.isNotBlank()) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }

        // Enlaces de descarga
        document.select("#panel_descarga a[href*='download']").forEach { element ->
            val url = element.attr("href")
            if (url.isNotBlank()) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }

        // Enlaces directos en player
        document.select("div.pane ul.linklist a").forEach { element ->
            val url = element.attr("href")
            if (url.isNotBlank() && !url.startsWith("#")) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
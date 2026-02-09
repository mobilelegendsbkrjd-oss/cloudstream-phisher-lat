package com.utelevision

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.TvType.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class utelevision : MainAPI() {

    override var mainUrl = "https://utelevision.cc"
    override var name = "uTelevision"
    override var lang = "es"

    override val supportedTypes = setOf(Movie, TvSeries)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Películas",
        "$mainUrl/series" to "Series"
    )

    // ==========================
    // HOME
    // ==========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(request.data).document
        val items = doc.select("a.card-movie")

        return HomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    items.mapNotNull { it.toSearch() },
                    false
                )
            )
        )
    }

    // ==========================
    // DATA
    // ==========================
    data class GStreamResponse(
        val success: Boolean,
        val data: GStreamData?
    )

    data class GStreamData(
        val link: String?
    )

    // ==========================
    // SEARCH ITEM
    // ==========================
    private fun Element.toSearch(): SearchResponse? {
        val title = selectFirst("h3")?.text() ?: return null
        val href = fixUrl(attr("href"))
        val poster = selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }

        return newMovieSearchResponse(title, href, Movie) {
            posterUrl = poster
        }
    }

    // ==========================
    // SEARCH
    // ==========================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=${query.replace(" ", "+")}"
        return app.get(url).document.select("a.card-movie")
            .mapNotNull { it.toSearch() }
    }

    // ==========================
    // LOAD
    // ==========================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: name
        val poster = doc.selectFirst("img[data-src]")?.attr("data-src")?.let { fixUrl(it) }
        val plot = doc.selectFirst("p.text-muted")?.text()

        if (url.contains("/serie/")) {
            val episodes = mutableListOf<Episode>()

            doc.select(".card-episode a").forEach {
                val epUrl = fixUrl(it.attr("href"))
                val nameEp = it.text().trim()

                val match = Regex("""S(\d+)E(\d+)""").find(epUrl)
                val season = match?.groupValues?.get(1)?.toIntOrNull()
                val episode = match?.groupValues?.get(2)?.toIntOrNull()

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = nameEp
                        this.season = season
                        this.episode = episode
                    }
                )
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, Movie, url) {
            posterUrl = poster
            this.plot = plot
        }
    }

    // ==========================
    // LINKS (FIX DEFINITIVO)
    // ==========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // data = URL real del item
        val page = app.get(data).text

        // 1️⃣ EXTRAER IMDB REAL
        val imdbId = Regex("""tt\d{7,8}""")
            .find(page)?.value ?: return false

        val streams = listOf("stream1", "stream2", "stream3", "stream4", "stream5")

        // 2️⃣ PELÍCULA
        if (!data.contains("/episode/")) {
            for (stream in streams) {
                val gUrl =
                    "$mainUrl/gStream?id=$stream|movie|imdb:$imdbId" +
                            "&movie=$stream|movie|imdb:$imdbId" +
                            "&is_init=false&captcha="

                val json = app.get(
                    gUrl,
                    headers = mapOf("Accept" to "application/json")
                ).parsedSafe<GStreamResponse>() ?: continue

                val embed = json.data?.link ?: continue
                loadExtractor(embed, mainUrl, subtitleCallback, callback)
            }
            return true
        }

        // 3️⃣ SERIE
        val episodeId = Regex("""S\d+E\d+""").find(data)?.value ?: return false

        for (stream in streams) {
            val gUrl =
                "$mainUrl/gStream?id=$stream|serie|imdb:$imdbId|$episodeId" +
                        "&movie=$stream|serie|imdb:$imdbId|$episodeId" +
                        "&is_init=false&captcha="

            val json = app.get(
                gUrl,
                headers = mapOf("Accept" to "application/json")
            ).parsedSafe<GStreamResponse>() ?: continue

            val embed = json.data?.link ?: continue
            loadExtractor(embed, mainUrl, subtitleCallback, callback)
        }

        return true
    }
}

package com.sololatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.*
import org.jsoup.nodes.Element

class SoloLatino : MainAPI() {

    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "mx"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    private val sagasJsonUrl =
        "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/ListasSL.json"

    // =========================
    // HOMEPAGE CACHE (24H)
    // =========================

    private var cachedHome: HomePageResponse? = null
    private var cacheTime: Long = 0
    private val cacheDuration = 24 * 60 * 60 * 1000L

    // =========================
    // NETWORK STABILITY
    // =========================

    private suspend fun safeGet(url: String, retries: Int = 3): String? {
        repeat(retries) {
            try {
                return app.get(url, timeout = 30).text
            } catch (_: Exception) {
                delay(600)
            }
        }
        return null
    }

    // =========================
    // IMAGE EXTRACTION
    // =========================

    private fun bestSrcset(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        val sources = srcset.split(",")
        var best: String? = null
        var size = 0

        for (s in sources) {
            val p = s.trim().split(" ")
            if (p.size >= 2) {
                val w = p[1].replace("w", "").toIntOrNull() ?: 0
                if (w > size) {
                    size = w
                    best = p[0]
                }
            }
        }
        return best ?: sources.first().trim().split(" ").first()
    }

    private fun getImage(el: Element?): String? {
        if (el == null) return null

        val attrs = listOf(
            "data-srcset",
            "data-src",
            "data-litespeed-src",
            "data-lazy-src",
            "srcset",
            "src"
        )

        for (attr in attrs) {
            val v = el.attr(attr)
            if (v.isNotBlank() && !v.startsWith("data:image")) {
                return if (attr.contains("srcset"))
                    bestSrcset(v)
                else v
            }
        }

        return null
    }

    // =========================
    // MAIN PAGE
    // =========================

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? = coroutineScope {

        val now = System.currentTimeMillis()

        if (page == 1 && cachedHome != null && now - cacheTime < cacheDuration) {
            return@coroutineScope cachedHome
        }

        val sections = listOf(
            "🔥 Sagas" to sagasJsonUrl,
            "🎥 Películas" to "$mainUrl/peliculas",
            "📺 Series" to "$mainUrl/series",
            "🌸 Animes" to "$mainUrl/animes",
            "🦁 Cartoons" to "$mainUrl/genre_series/toons",
            "💕 Doramas" to "$mainUrl/genre_series/kdramas/",
            "🎬 Netflix" to "$mainUrl/network/netflix/",
            "🟠 Amazon" to "$mainUrl/network/amazon/",
            "🐭 Disney+" to "$mainUrl/network/disney/",
            "🟣 HBO Max" to "$mainUrl/network/hbo-max/",
            "🍎 Apple TV" to "$mainUrl/network/apple-tv/",
            "🟢 Hulu" to "$mainUrl/network/hulu/",
            "🏔️ Paramount+" to "$mainUrl/network/paramount/"
        )

        val items = mutableListOf<HomePageList>()

        sections.chunked(3).forEach { batch ->

            val tasks: List<Deferred<HomePageList?>> = batch.map { (name, url) ->
                async {

                    try {

                        val tvType =
                            if (name == "🎥 Películas") TvType.Movie
                            else TvType.TvSeries

                        val home = if (name == "🔥 Sagas") {

                            val jsonText = safeGet(url)?.trim() ?: return@async null
                            val sagas = mutableListOf<SearchResponse>()

                            val cleanJson =
                                jsonText.removePrefix("[")
                                    .removeSuffix("]")
                                    .trim()

                            if (cleanJson.isNotEmpty()) {

                                val objs =
                                    cleanJson.split("},")
                                        .map { it.trim() + "}" }

                                objs.forEach { obj ->

                                    try {

                                        val title =
                                            Regex(""""title"\s*:\s*"([^"]*)"""")
                                                .find(obj)
                                                ?.groupValues?.get(1)
                                                ?: return@forEach

                                        val link =
                                            Regex(""""url"\s*:\s*"([^"]*)"""")
                                                .find(obj)
                                                ?.groupValues?.get(1)
                                                ?: return@forEach

                                        val poster =
                                            Regex(""""poster"\s*:\s*"([^"]*)"""")
                                                .find(obj)
                                                ?.groupValues?.get(1)

                                        sagas.add(
                                            newTvSeriesSearchResponse(
                                                title,
                                                link,
                                                tvType
                                            ) {
                                                posterUrl = poster
                                            }
                                        )

                                    } catch (_: Exception) {}
                                }
                            }

                            sagas

                        } else {

                            val finalUrl =
                                if (page > 1) "$url/page/$page/"
                                else url

                            val html =
                                safeGet(finalUrl) ?: return@async null

                            val doc =
                                org.jsoup.Jsoup.parse(html)

                            doc.select("div.items article.item")
                                .mapNotNull {

                                    val title =
                                        it.selectFirst("a div.data h3")?.text()
                                            ?: return@mapNotNull null

                                    val link =
                                        it.selectFirst("a")?.attr("href")
                                            ?: return@mapNotNull null

                                    val img =
                                        getImage(
                                            it.selectFirst("div.poster img")
                                        )

                                    newTvSeriesSearchResponse(
                                        title,
                                        link,
                                        tvType,
                                        true
                                    ) {
                                        posterUrl = img
                                    }
                                }
                        }

                        if (home.isNotEmpty())
                            HomePageList(name, home)
                        else null

                    } catch (_: Exception) {
                        return@async null
                    }
                }
            }

            val batchItems =
                tasks.awaitAll().filterNotNull()

            items.addAll(batchItems)
        }

        val response = newHomePageResponse(items)

        if (page == 1) {
            cachedHome = response
            cacheTime = System.currentTimeMillis()
        }

        return@coroutineScope response
    }

    // =========================
    // SEARCH
    // =========================

    override suspend fun search(query: String): List<SearchResponse> {

        return try {

            val doc =
                app.get("$mainUrl/?s=$query").document

            doc.select("div.items article.item")
                .mapNotNull {

                    val title =
                        it.selectFirst("a div.data h3")?.text()
                            ?: return@mapNotNull null

                    val link =
                        it.selectFirst("a")?.attr("href")
                            ?: return@mapNotNull null

                    val img =
                        getImage(
                            it.selectFirst("div.poster img")
                        )

                    newTvSeriesSearchResponse(
                        title,
                        link,
                        TvType.TvSeries
                    ) {
                        posterUrl = img
                    }
                }

        } catch (_: Exception) {
            emptyList()
        }
    }

    // =========================
    // LOAD
    // =========================

    override suspend fun load(url: String): LoadResponse? {

        val doc = app.get(url).document

        val tvType =
            if (url.contains("peliculas"))
                TvType.Movie
            else TvType.TvSeries

        val title =
            doc.selectFirst("div.data h1")?.text() ?: ""

        val poster =
            getImage(doc.selectFirst("div.poster img"))

        val backimage =
            doc.selectFirst(".wallpaper")
                ?.attr("style")
                ?.substringAfter("url(")
                ?.substringBefore(");")

        val description =
            doc.selectFirst("div.wp-content")?.text() ?: ""

        val episodes =
            if (tvType == TvType.TvSeries) {

                doc.select("div#seasons div.se-c")
                    .flatMap { season ->

                        season.select("ul.episodios li")
                            .mapNotNull {

                                val epurl =
                                    it.selectFirst("a")
                                        ?.attr("href")
                                        ?: return@mapNotNull null

                                val epTitle =
                                    it.selectFirst(
                                        "div.episodiotitle div.epst"
                                    )?.text() ?: ""

                                newEpisode(epurl) {

                                    name = epTitle

                                    posterUrl =
                                        getImage(
                                            it.selectFirst(
                                                "div.imagen img"
                                            )
                                        )
                                }
                            }
                    }

            } else emptyList()

        return when (tvType) {

            TvType.TvSeries ->
                newTvSeriesLoadResponse(
                    title,
                    url,
                    tvType,
                    episodes
                ) {

                    posterUrl = poster
                    backgroundPosterUrl =
                        backimage ?: poster

                    plot = description
                }

            TvType.Movie ->
                newMovieLoadResponse(
                    title,
                    url,
                    tvType,
                    url
                ) {

                    posterUrl = poster
                    backgroundPosterUrl =
                        backimage ?: poster

                    plot = description
                }

            else -> null
        }
    }

    // =========================
    // LINKS
    // =========================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        try {

            val doc =
                app.get(data).document

            val iframe =
                doc.selectFirst("iframe")?.attr("src")
                    ?: return false

            loadExtractor(
                fixHostsLinks(iframe),
                data,
                subtitleCallback,
                callback
            )

        } catch (_: Exception) {}

        return true
    }

    // =========================
    // HOST FIX
    // =========================

    private fun fixHostsLinks(url: String): String {

        return url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com", "https://streamwish.to")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://do7go.com", "https://dood.la")
            .replaceFirst("https://doodstream.com", "https://dood.la")
            .replaceFirst("https://streamtape.com", "https://streamtape.cc")
    }
}
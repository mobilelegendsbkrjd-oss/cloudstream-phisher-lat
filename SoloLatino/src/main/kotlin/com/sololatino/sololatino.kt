package com.sololatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class SoloLatinoPlugin : MainAPI() {
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

    private val sagasJsonUrl = "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/ListasSL.json"

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

        val sections = listOf(
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
            "🔥 Sagas" to sagasJsonUrl
        )

        sections.forEach { (name, url) ->
            val tvType = when (name) {
                "🎥 Películas" -> TvType.Movie
                "📺 Series", "💕 Doramas", "🎬 Netflix", "🟠 Amazon", "🐭 Disney+", "🟣 HBO Max", "🍎 Apple TV", "🟢 Hulu", "🔥 Sagas" -> TvType.TvSeries
                "🌸 Animes" -> TvType.Anime
                "🦁 Cartoons" -> TvType.Cartoon
                else -> TvType.Others
            }

            val home = if (name == "🔥 Sagas") {
                try {
                    val jsonText = app.get(url, timeout = 20).text.trim()
                    val sagas = mutableListOf<SearchResponse>()
                    val cleanJson = jsonText.removePrefix("[").removeSuffix("]").trim()
                    if (cleanJson.isEmpty()) return@forEach

                    val objetos = cleanJson.split("},").map { it.trim() + "}" }

                    objetos.forEach { objStr ->
                        try {
                            val titleMatch = Regex(""""title"\s*:\s*"([^"]*)"""").find(objStr)
                            val urlMatch = Regex(""""url"\s*:\s*"([^"]*)"""").find(objStr)
                            val posterMatch = Regex(""""poster"\s*:\s*"([^"]*)"""").find(objStr)

                            val title = titleMatch?.groupValues?.get(1) ?: return@forEach
                            val link = urlMatch?.groupValues?.get(1) ?: return@forEach
                            val poster = posterMatch?.groupValues?.get(1)

                            sagas.add(
                                newTvSeriesSearchResponse(title, link, tvType) {
                                    this.posterUrl = poster
                                }
                            )
                        } catch (e: Exception) {}
                    }

                    sagas
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                val finalUrl = if (page > 1) "$url/page/$page/" else url
                val doc = app.get(finalUrl).document
                doc.select("div.items article.item").map {
                    val title = it.selectFirst("a div.data h3")?.text()
                    val link = it.selectFirst("a")?.attr("href")
                    val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
                    newTvSeriesSearchResponse(title!!, link!!, tvType, true) {
                        this.posterUrl = img
                    }
                }
            }

            if (home.isNotEmpty()) {
                items.add(HomePageList(name, home))
            }
        }

        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.items article.item").map {
            val title = it.selectFirst("a div.data h3")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
            newTvSeriesSearchResponse(title!!, link!!, TvType.TvSeries) {
                this.posterUrl = img
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val isUserList = url.contains("/listas/") && doc.selectFirst("div.infoCard") != null

        if (isUserList) {
            val title = doc.selectFirst("div.infoCard h1")?.text()?.trim() ?: "Saga Curada"
            val description = doc.selectFirst("div.infoCard article p")?.text()?.trim() ?: ""
            val author = doc.selectFirst("div.infoCard a.createdbyT span")?.text()?.trim() ?: "Usuario"
            val likes = doc.selectFirst("div.infoCard div.createdbyT span")?.text()?.trim() ?: "0"

            var episodes = doc.select("div#archive-content article.item").mapIndexedNotNull { index, it ->
                val epurl = it.selectFirst("a")?.attr("href") ?: return@mapIndexedNotNull null
                val epTitle = it.selectFirst("h3")?.text()?.trim() ?: "Parte ${index + 1}"
                val epYear = it.selectFirst(".data p")?.text()?.trim()
                var realimg: String? = it.selectFirst("div.poster img")?.attr("data-srcset")
                if (realimg.isNullOrBlank()) realimg = it.selectFirst("img")?.attr("data-src")

                val isMovie = epurl.contains("/peliculas/") || epurl.contains("/episodios/")

                if (isMovie) {
                    newEpisode(epurl) {
                        name = epTitle + if (epYear != null) " ($epYear)" else ""
                        posterUrl = realimg
                    }
                } else {
                    newEpisode(epurl) {
                        name = epTitle + " (Serie)"
                        posterUrl = realimg
                    }
                }
            }.reversed()

            episodes = episodes.mapIndexed { idx, ep ->
                ep.apply {
                    season = 1
                    episode = idx + 1
                }
            }

            val poster = episodes.firstOrNull()?.posterUrl
                ?: doc.selectFirst("div.infoCard .uAvatar img")?.attr("data-src")
                ?: "https://sololatino.net/wp-content/uploads/2022/11/logo-final.png"

            return newTvSeriesLoadResponse(
                title,
                url, TvType.TvSeries, episodes
            ) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = buildString {
                    append(description.ifBlank { "Saga mixta curada - pelis y series." })
                    append("\n\nCreada por: $author • Favoritos: $likes")
                    append("\nPelículas reproducen directo | Series abren temporadas al clickear")
                }
                tags = listOf("Saga", "Mixta", "Curada", "Maratón")
            }
        }

        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("data-src") ?: ""
        val backimage = doc.selectFirst(".wallpaper")?.attr("style")?.substringAfter("url(")?.substringBefore(");")
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }
        var episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { season ->
                season.select("ul.episodios li").map {
                    val epurl = fixUrl(it.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = it.selectFirst("div.episodiotitle div.epst")?.text() ?: ""
                    val seasonEpisodeNumber = it.selectFirst("div.episodiotitle div.numerando")?.text()?.split("-")?.map {
                        it.trim().toIntOrNull()
                    }
                    val realimg = it.selectFirst("div.imagen img")?.attr("data-src")
                    newEpisode(epurl) {
                        name = epTitle
                        // NO forzamos season/episode aquí, dejamos los valores del sitio
                        posterUrl = realimg
                    }
                }
            }
        } else emptyList()

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesLoadResponse(title, url, tvType, episodes) {
                posterUrl = poster
                backgroundPosterUrl = backimage ?: poster
                plot = description
                this.tags = tags
            }
            TvType.Movie -> newMovieLoadResponse(title, url, tvType, url) {
                posterUrl = poster
                backgroundPosterUrl = backimage ?: poster
                plot = description
                this.tags = tags
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.selectFirst("iframe")?.attr("src")?.let {
            if (it.startsWith("https://embed69.org/")) {
                Embed69Extractor.load(it, data, subtitleCallback, callback)
            } else if (it.startsWith("https://xupalace.org/video")) {
                val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                regex.findAll(app.get(it).document.html()).map { it.groupValues.get(2) }
                    .toList().amap {
                        loadExtractor(fixHostsLinks(it), data, subtitleCallback, callback)
                    }
            } else {
                app.get(it).document.selectFirst("iframe")?.attr("src")?.let {
                    loadExtractor(fixHostsLinks(it), data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
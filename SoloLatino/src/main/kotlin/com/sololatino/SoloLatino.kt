package com.sololatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series" to "Series",
        "$mainUrl/animes" to "Animes",
        "$mainUrl/genre_series/toons" to "Cartoons",
        "$mainUrl/listas/" to "Listas de la comunidad"   // ← Nueva sección
    )

    private fun getImage(el: Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src", "data-srcset", "data-lazy-src", "src", "srcset")
        for (attr in attrs) {
            val v = el.attr(attr).trim()
            if (v.isNotBlank() && !v.startsWith("data:image")) {
                return v.split(",").first().trim().split(" ").firstOrNull { it.startsWith("http") }
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Películas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Cartoons", "$mainUrl/genre_series/toons"),
            Pair("Listas de la comunidad", "$mainUrl/listas/")
        )

        urls.amap { (name, baseUrl) ->
            val tvType = when (name) {
                "Películas" -> TvType.Movie
                "Series", "Listas de la comunidad" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                "Cartoons" -> TvType.Cartoon
                else -> TvType.Others
            }

            val url = if (page > 1) "$baseUrl/page/$page/" else baseUrl
            val doc = app.get(url).document

            val home = if (name == "Listas de la comunidad") {
                // Selector tentativo para la página principal de listas
                doc.select("article.item, .lista-item, h2:has(a)").mapNotNull {
                    val title = it.selectFirst("h2, h3, .data h3")?.text()?.trim() ?: return@mapNotNull null
                    val link = it.selectFirst("a")?.attr("href")?.let { href ->
                        if (href.startsWith("/")) mainUrl + href else href
                    } ?: return@mapNotNull null

                    val img = getImage(it.selectFirst("img"))

                    newTvSeriesSearchResponse(title, link, tvType) {
                        posterUrl = img
                        // description NO existe aquí → lo omitimos o lo ponemos en load si quieres
                    }
                }
            } else {
                doc.select("div.items article.item").map {
                    val title = it.selectFirst("a div.data h3")?.text()
                    val link = it.selectFirst("a")?.attr("href")?.let { href ->
                        if (href.startsWith("/")) mainUrl + href else href
                    }
                    val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")

                    newTvSeriesSearchResponse(title!!, link!!, tvType, true) {
                        this.posterUrl = img
                    }
                }
            }

            items.add(HomePageList(name, home))
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.items article.item").map {
            val title = it.selectFirst("a div.data h3")?.text()
            val link = it.selectFirst("a")?.attr("href")?.let { href ->
                if (href.startsWith("/")) mainUrl + href else href
            }
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
            val listTitle = doc.selectFirst("div.infoCard h1")?.text()?.trim() ?: "Lista de la Comunidad"
            val description = doc.selectFirst("div.infoCard article p")?.text()?.trim() ?: ""
            val author = doc.selectFirst("div.infoCard a.createdbyT span")?.text()?.trim() ?: "Usuario"
            val likes = doc.selectFirst("div.infoCard div.createdbyT span")?.text()?.trim() ?: "0"

            // Extraer items y revertir para orden cronológico
            val rawItems = doc.select("#archive-content article.item").mapIndexedNotNull { idx, article ->
                val linkEl = article.selectFirst("a") ?: return@mapIndexedNotNull null
                val epUrl = linkEl.attr("href").let { href ->
                    if (href.startsWith("/")) mainUrl + href else href
                }
                val epName = article.selectFirst("h3")?.text()?.trim() ?: "Parte ${idx + 1}"
                val epYear = article.selectFirst(".data p")?.text()?.trim()
                val epImg = getImage(article.selectFirst("img.lazyload"))

                newEpisode(epUrl) {
                    name = epName + if (epYear != null) " ($epYear)" else ""
                    // season y episode se asignan DESPUÉS del reversed
                    posterUrl = epImg
                }
            }.reversed()  // ← orden cronológico: 1 al final

            // Reasignar números de episodio después de revertir
            val episodes = rawItems.mapIndexed { newIdx, ep ->
                ep.apply {
                    season = 1
                    episode = newIdx + 1
                }
            }

            // Poster = el de la primera película (ahora episodio 1)
            val listPoster = episodes.firstOrNull()?.posterUrl
                ?: doc.selectFirst("div.infoCard .uAvatar img")?.attr("data-src")
                ?: "https://sololatino.net/wp-content/uploads/2022/11/logo-final.png"

            return newTvSeriesLoadResponse(
                listTitle,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = listPoster
                backgroundPosterUrl = listPoster
                plot = buildString {
                    append(description.ifBlank { "Colección curada por usuarios." })
                    append("\n\nCreada por: $author")
                    append("\nFavoritos: $likes")
                    append("\nLista de SoloLatino - orden cronológico para maratón")
                }
                tags = listOf("Comunidad", "Saga", "Lista", "Maratón")
            }
        }

        // Parte original para películas y series (sin cambios)
        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")!!.attr("data-src")
        val backimage = doc.selectFirst(".wallpaper")?.attr("style")?.substringAfter("url(")?.substringBefore(");")
        val description = doc.selectFirst("div.wp-content")!!.text()
        val tags = doc.select("div.sgeneros a").map { it.text() }
        var episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { season ->
                season.select("ul.episodios li").map {
                    val epurl = it.selectFirst("a")?.attr("href")?.let { href ->
                        if (href.startsWith("/")) mainUrl + href else href
                    } ?: ""
                    val epTitle = it.selectFirst("div.episodiotitle div.epst")!!.text()
                    val seasonEpisodeNumber =
                        it.selectFirst("div.episodiotitle div.numerando")?.text()?.split("-")?.map {
                            it.trim().toIntOrNull()
                        }
                    val realimg = it.selectFirst("div.imagen img")?.attr("data-src")
                    newEpisode(epurl) {
                        this.name = epTitle
                        this.season = seasonEpisodeNumber?.getOrNull(0)
                        this.episode = seasonEpisodeNumber?.getOrNull(1)
                        this.posterUrl = realimg
                    }
                }
            }
        } else listOf()

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage ?: poster
                    this.plot = description
                    this.tags = tags
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage ?: poster
                    this.plot = description
                    this.tags = tags
                }
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

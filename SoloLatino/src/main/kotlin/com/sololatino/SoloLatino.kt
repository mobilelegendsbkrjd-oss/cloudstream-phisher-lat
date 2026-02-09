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

    // URL del JSON externo con las listas curadas
    private val listasJsonUrl = "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/ListasSL.json"

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Cartoons", "$mainUrl/genre_series/toons"),
            Pair("Doramas", "$mainUrl/genre_series/kdramas/"),
            Pair("Listas curadas", listasJsonUrl)   // ← Nueva sección desde JSON
        )

        urls.amap { (name, url) ->
            val tvType = when (name) {
                "Peliculas" -> TvType.Movie
                "Series", "Doramas", "Listas curadas" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                "Cartoons" -> TvType.Cartoon
                else -> TvType.Others
            }

            val home = if (name == "Listas curadas") {
                try {
                    val jsonText = app.get(url, timeout = 20).text.trim()

                    // Parsing muy simple y seguro (sin Gson ni dependencias extras)
                    // Asume formato: [ { "title":"...", "url":"...", "poster":"..." }, ... ]
                    val listas = mutableListOf<SearchResponse>()

                    // Quitamos corchetes y dividimos por objetos
                    val cleanJson = jsonText.removePrefix("[").removeSuffix("]").trim()
                    if (cleanJson.isEmpty()) return@amap HomePageList(name, emptyList())

                    val objetos = cleanJson.split("},").map { it.trim() + "}" }

                    objetos.forEach { objStr ->
                        try {
                            val titleMatch = Regex(""""title"\s*:\s*"([^"]*)"""").find(objStr)
                            val urlMatch = Regex(""""url"\s*:\s*"([^"]*)"""").find(objStr)
                            val posterMatch = Regex(""""poster"\s*:\s*"([^"]*)"""").find(objStr)

                            val title = titleMatch?.groupValues?.get(1) ?: return@forEach
                            val link = urlMatch?.groupValues?.get(1) ?: return@forEach
                            val poster = posterMatch?.groupValues?.get(1)

                            listas.add(
                                newTvSeriesSearchResponse(title, link, tvType) {
                                    this.posterUrl = poster
                                }
                            )
                        } catch (e: Exception) {
                            // Ignora objeto mal formado
                        }
                    }

                    listas
                } catch (e: Exception) {
                    // Si falla la conexión o parseo → categoría vacía sin crash
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

            items.add(HomePageList(name, home))
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

    class MainTemporada(elements: Map<String, List<MainTemporadaElement>>) :
        HashMap<String, List<MainTemporadaElement>>(elements)

    data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val isUserList = url.contains("/listas/") && doc.selectFirst("div.infoCard") != null

        if (isUserList) {
            val title = doc.selectFirst("div.infoCard h1")?.text()?.trim() ?: "Lista de la Comunidad"
            val description = doc.selectFirst("div.infoCard article p")?.text()?.trim() ?: ""
            val author = doc.selectFirst("div.infoCard a.createdbyT span")?.text()?.trim() ?: "Usuario"
            val likes = doc.selectFirst("div.infoCard div.createdbyT span")?.text()?.trim() ?: "0"

            var episodes = doc.select("div#archive-content article.item").mapIndexedNotNull { index, it ->
                val epurl = it.selectFirst("a")?.attr("href")
                val epTitle = it.selectFirst("h3")?.text()?.trim() ?: "Parte ${index + 1}"
                val epYear = it.selectFirst(".data p")?.text()?.trim()
                val realimg = it.selectFirst("div.poster img")?.attr("data-srcset") ?: it.selectFirst("img")?.attr("data-src")

                newEpisode(epurl ?: "") {
                    this.name = epTitle + if (epYear != null) " ($epYear)" else ""
                    this.posterUrl = realimg
                }
            }.reversed()

            episodes = episodes.mapIndexed { idx, ep ->
                ep.apply {
                    this.season = 1
                    this.episode = idx + 1
                }
            }

            val poster = episodes.firstOrNull()?.posterUrl
                ?: doc.selectFirst("div.infoCard .uAvatar img")?.attr("data-src")
                ?: "https://sololatino.net/wp-content/uploads/2022/11/logo-final.png"

            return newTvSeriesLoadResponse(
                title,
                url, TvType.TvSeries, episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = buildString {
                    append(description.ifBlank { "Saga o colección curada por usuarios." })
                    append("\n\nCreada por: $author • Favoritos: $likes")
                    append("\nLista de SoloLatino - orden cronológico para maratón")
                }
                this.tags = listOf("Comunidad", "Lista", "Saga", "Maratón")
            }
        }

        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")!!.attr("data-src")
        val backimage = doc.selectFirst(".wallpaper")?.attr("style")?.substringAfter("url(")?.substringBefore(");")
        val description = doc.selectFirst("div.wp-content")!!.text()
        val tags = doc.select("div.sgeneros a").map { it.text() }
        var episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { season ->
                season.select("ul.episodios li").map {
                    val epurl = fixUrl(it.selectFirst("a")?.attr("href") ?: "")
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
            } else { // https://xupalace.org/uqlink.php or others
                app.get(it).document.selectFirst("iframe")?.attr("src")?.let {
                    loadExtractor(fixHostsLinks(it), data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
                                                    }

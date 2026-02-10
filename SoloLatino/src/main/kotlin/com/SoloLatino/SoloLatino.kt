package com.sololatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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

    private val sagasJsonUrl = "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/ListasSL.json"
    private val seriesCuradasJsonUrl = "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/seriesSL.json"

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()

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
            "📚 Categorias (solo TV)" to seriesCuradasJsonUrl
        )

        sections.forEach { (name, url) ->
            val tvType = when (name) {
                "🎥 Películas" -> TvType.Movie
                else -> TvType.TvSeries
            }

            val home = if (name == "🔥 Sagas" || name == "📚 Categorias (solo TV)") {
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

            val isSeriesCurada = url.contains(seriesCuradasJsonUrl)

            // Primero recolectamos todos los ítems con su información
            val sagaItems = doc.select("div#archive-content article.item").mapNotNull { it ->
                val epurl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epTitle = it.selectFirst("h3")?.text()?.trim() ?: ""
                val epYearText = it.selectFirst(".data p")?.text()?.trim() ?: ""
                var realimg: String? = it.selectFirst("div.poster img")?.attr("data-srcset")
                if (realimg.isNullOrBlank()) realimg = it.selectFirst("img")?.attr("data-src")

                // Extraer el año del texto (generalmente está al final entre paréntesis o solo)
                val yearMatch = Regex("""(\d{4})""").find(epYearText)
                val epYear = yearMatch?.value?.toIntOrNull() ?: 0

                val isMovie = epurl.contains("/peliculas/") || epurl.contains("/episodios/")

                EpisodeData(
                    url = epurl,
                    title = epTitle,
                    poster = realimg,
                    year = epYear,
                    isMovie = isMovie
                )
            }

            // Ordenar por año (más antiguo primero)
            val sortedItems = sagaItems.sortedBy { it.year }

            // Separar y crear los objetos apropiados
            val movieEpisodes = sortedItems.filter { it.isMovie }.mapIndexed { index, data ->
                newEpisode(data.url) {
                    name = data.title + if (data.year > 0) " (${data.year})" else ""
                    posterUrl = data.poster
                    season = 1
                    episode = index + 1
                }
            }

            val seriesSuggestions = sortedItems.filter { !it.isMovie }.map { data ->
                newTvSeriesSearchResponse(data.title, data.url, TvType.TvSeries) {
                    posterUrl = data.poster
                    if (data.year > 0) {
                        this.year = data.year
                    }
                }
            }

            val episodes = if (isSeriesCurada) emptyList() else movieEpisodes

            val poster = episodes.firstOrNull()?.posterUrl ?: seriesSuggestions.firstOrNull()?.posterUrl
            ?: doc.selectFirst("div.infoCard .uAvatar img")?.attr("data-src")
            ?: "https://sololatino.net/wp-content/uploads/2022/11/logo-final.png"

            // Contar años únicos para mostrar en la descripción
            val uniqueYears = sortedItems.mapNotNull { if (it.year > 0) it.year else null }.distinct().sorted()
            val yearRangeText = if (uniqueYears.isNotEmpty()) {
                if (uniqueYears.size == 1) {
                    "Año: ${uniqueYears.first()}"
                } else {
                    "Años: ${uniqueYears.first()} - ${uniqueYears.last()}"
                }
            } else ""

            return newTvSeriesLoadResponse(
                title,
                url, TvType.TvSeries, episodes
            ) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = buildString {
                    append(description.ifBlank { "Lista curada por usuarios." })
                    append("\n\nCreada por: $author • Favoritos: $likes")
                    if (yearRangeText.isNotEmpty()) {
                        append("\n$yearRangeText")
                    }
                    if (!isSeriesCurada && sortedItems.isNotEmpty()) {
                        append("\nOrden cronológico (${movieEpisodes.size} películas)")
                    }
                    if (seriesSuggestions.isNotEmpty()) {
                        append("\n\nSeries relacionadas (${seriesSuggestions.size} series - en TV verás sugerencias clicables abajo):")
                        if (seriesSuggestions.size <= 5) { // Mostrar solo las primeras 5 para no saturar
                            seriesSuggestions.take(5).forEachIndexed { idx, series ->
                                val yearText = if (series.year != null) " (${series.year})" else ""
                                append("\n${idx + 1}. ${series.name}$yearText")
                            }
                            if (seriesSuggestions.size > 5) {
                                append("\n... y ${seriesSuggestions.size - 5} más")
                            }
                        }
                    }
                }
                tags = listOf("Curada", "Maratón")
                recommendations = seriesSuggestions
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
                    val realimg = it.selectFirst("div.imagen img")?.attr("data-src")
                    newEpisode(epurl) {
                        name = epTitle
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

    // Clase de datos auxiliar para manejar la información de los episodios
    private data class EpisodeData(
        val url: String,
        val title: String,
        val poster: String?,
        val year: Int,
        val isMovie: Boolean
    )
}
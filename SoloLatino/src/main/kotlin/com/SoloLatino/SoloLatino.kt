package com.sololatino

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
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

    // Clase para parsear el JSON más rápido
    data class GitHubItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
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

        // CAMBIO IMPORTANTE: Usamos 'apmap' en lugar de 'forEach' o 'map'
        // Esto carga todas las secciones simultáneamente.
        val items = sections.apmap { (name, url) ->
            try {
                val tvType = when (name) {
                    "🎥 Películas" -> TvType.Movie
                    else -> TvType.TvSeries
                }

                val home = if (name == "🔥 Sagas" || name == "📚 Categorias (solo TV)") {
                    try {
                        // Optimización: Usamos el parser de JSON nativo en lugar de Regex manual
                        val jsonText = app.get(url, timeout = 20).text
                        val parsed = AppUtils.tryParseJson<List<GitHubItem>>(jsonText)
                        
                        parsed?.mapNotNull { item ->
                            val title = item.title ?: return@mapNotNull null
                            val link = item.url ?: return@mapNotNull null
                            newTvSeriesSearchResponse(title, link, tvType) {
                                this.posterUrl = item.poster
                            }
                        } ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    val finalUrl = if (page > 1) "$url/page/$page/" else url
                    val doc = app.get(finalUrl).document
                    doc.select("div.items article.item").map {
                        val title = it.selectFirst("a div.data h3")?.text() ?: return@map null
                        val link = it.selectFirst("a")?.attr("href") ?: return@map null
                        val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
                        newTvSeriesSearchResponse(title, link, tvType, true) {
                            this.posterUrl = img
                        }
                    }.filterNotNull()
                }

                if (home.isNotEmpty()) {
                    HomePageList(name, home)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }.filterNotNull()

        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        return try {
            val doc = app.get(url).document
            doc.select("div.items article.item").mapNotNull {
                val title = it.selectFirst("a div.data h3")?.text() ?: return@mapNotNull null
                val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = img
                }
            }
        } catch (e: Exception) {
            emptyList()
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

            val sagaItems = doc.select("div#archive-content article.item").mapNotNull { it ->
                val epurl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epTitle = it.selectFirst("h3")?.text()?.trim() ?: ""
                val epYearText = it.selectFirst(".data p")?.text()?.trim() ?: ""
                var realimg: String? = it.selectFirst("div.poster img")?.attr("data-srcset")
                if (realimg.isNullOrBlank()) realimg = it.selectFirst("img")?.attr("data-src")

                val yearMatch = Regex("""(\d{4})""").find(epYearText)
                val epYear = yearMatch?.value?.toIntOrNull() ?: 0
                val isMovie = epurl.contains("/peliculas/") || epurl.contains("/episodios/")

                EpisodeData(epurl, epTitle, realimg, epYear, isMovie)
            }

            val sortedItems = sagaItems.sortedBy { it.year }

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
                    if (data.year > 0) this.year = data.year
                }
            }

            val episodes = if (isSeriesCurada) emptyList() else movieEpisodes
            val poster = episodes.firstOrNull()?.posterUrl 
                ?: seriesSuggestions.firstOrNull()?.posterUrl
                ?: doc.selectFirst("div.infoCard .uAvatar img")?.attr("data-src")
                ?: "https://sololatino.net/wp-content/uploads/2022/11/logo-final.png"

            // Optimización visual de rangos de años
            val uniqueYears = sortedItems.mapNotNull { if (it.year > 0) it.year else null }.distinct().sorted()
            val yearRangeText = if (uniqueYears.isNotEmpty()) {
                if (uniqueYears.size == 1) "Año: ${uniqueYears.first()}" 
                else "Años: ${uniqueYears.first()} - ${uniqueYears.last()}"
            } else ""

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = buildString {
                    append(description.ifBlank { "Lista curada por usuarios." })
                    append("\n\nCreada por: $author • Favoritos: $likes")
                    if (yearRangeText.isNotEmpty()) append("\n$yearRangeText")
                    if (seriesSuggestions.isNotEmpty()) {
                        append("\n\nSeries relacionadas (${seriesSuggestions.size}):")
                        seriesSuggestions.take(5).forEachIndexed { idx, series ->
                            append("\n${idx + 1}. ${series.name}")
                        }
                        if (seriesSuggestions.size > 5) append("\n... y ${seriesSuggestions.size - 5} más")
                    }
                }
                tags = listOf("Curada", "Maratón")
                recommendations = seriesSuggestions
            }
        }

        // Lógica normal para Películas y Series
        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("data-src") ?: ""
        val backimage = doc.selectFirst(".wallpaper")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(");")
        val description = doc.selectFirst("div.wp-content")?.text() ?: ""
        val tags = doc.select("div.sgeneros a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { season ->
                season.select("ul.episodios li").mapNotNull {
                    val epurl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
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
        try {
            val doc = app.get(data).document
            // Buscar iframe principal
            val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: return false
            
            if (iframeSrc.startsWith("https://embed69.org/")) {
                Embed69Extractor.load(iframeSrc, data, subtitleCallback, callback)
            } else if (iframeSrc.startsWith("https://xupalace.org/video")) {
                // Optimización: Extraemos los IDs y cargamos en paralelo
                val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                val html = app.get(iframeSrc).text
                
                regex.findAll(html).map { it.groupValues.get(2) }
                    .toList()
                    .apmap { // Usamos apmap aquí también para cargar extractores en paralelo
                        loadExtractor(Embed69Extractor.fixHostsLinks(it), data, subtitleCallback, callback)
                    }
            } else {
                // Caso genérico
                loadExtractor(Embed69Extractor.fixHostsLinks(iframeSrc), data, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            // Error silencioso
        }
        return true
    }
    
    // Asumimos que fixUrl ya existe o es un helper, si no, habría que importarlo.
    // Como no está definido en tu código original, uso el url directo, pero si es de Utils, ok.
    
    private data class EpisodeData(
        val url: String,
        val title: String,
        val poster: String?,
        val year: Int,
        val isMovie: Boolean
    )
}

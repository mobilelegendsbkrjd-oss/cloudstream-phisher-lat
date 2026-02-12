package com.sololatino

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = coroutineScope {
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
            "📚 Sugerencias Comunidad" to seriesCuradasJsonUrl
        )

        val items = ArrayList<HomePageList>()

        // CORRECCIÓN: Usamos 'chunked(3)' para pedir de 3 en 3.
        // Pedir todo junto bloquea el servidor, pedir 1 por 1 es muy lento.
        // 3 es el equilibrio perfecto.
        sections.chunked(3).forEach { batch ->
            val batchItems = batch.map { (name, url) ->
                async {
                    try {
                        val tvType = when (name) {
                            "🎥 Películas" -> TvType.Movie
                            else -> TvType.TvSeries
                        }

                        val home = if (name == "🔥 Sagas" || name == "📚 Categorias (solo TV)") {
                            try {
                                // RESTAURADO: Tu lógica original con Regex para leer el JSON "sucio" sin errores
                                val jsonText = app.get(url, timeout = 20).text.trim()
                                val sagas = mutableListOf<SearchResponse>()
                                val cleanJson = jsonText.removePrefix("[").removeSuffix("]").trim()
                                
                                if (cleanJson.isNotEmpty()) {
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
                                }
                                sagas
                            } catch (e: Exception) {
                                emptyList()
                            }
                        } else {
                            // Aumenté el timeout a 30s por si el servidor tarda en responder los lotes
                            val finalUrl = if (page > 1) "$url/page/$page/" else url
                            val doc = app.get(finalUrl, timeout = 30).document
                            doc.select("div.items article.item").mapNotNull {
                                val title = it.selectFirst("a div.data h3")?.text() ?: return@mapNotNull null
                                val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
                                newTvSeriesSearchResponse(title, link, tvType, true) {
                                    this.posterUrl = img
                                }
                            }
                        }

                        if (home.isNotEmpty()) {
                            HomePageList(name, home)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull() // Esperamos a que terminen los 3 de este lote
            
            items.addAll(batchItems)
        }

        return@coroutineScope newHomePageResponse(items)
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
    ): Boolean = coroutineScope {
        try {
            val doc = app.get(data).document
            val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: return@coroutineScope false
            
            if (iframeSrc.startsWith("https://embed69.org/")) {
                Embed69Extractor.load(iframeSrc, data, subtitleCallback, callback)
            } else if (iframeSrc.startsWith("https://xupalace.org/video")) {
                val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                val html = app.get(iframeSrc).text
                
                regex.findAll(html).map { it.groupValues.get(2) }
                    .toList()
                    .map { link ->
                        async {
                            loadExtractor(fixHostsLinks(link), data, subtitleCallback, callback)
                        }
                    }.awaitAll()
            } else {
                loadExtractor(fixHostsLinks(iframeSrc), data, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            // Error silencioso
        }
        return@coroutineScope true
    }
    
    // Función auxiliar para fixHostsLinks dentro de la clase
    private fun fixHostsLinks(url: String): String {
        return url
            .replaceFirst("https://hglink.to", "https://streamwish.to")
            .replaceFirst("https://swdyu.com", "https://streamwish.to")
            .replaceFirst("https://cybervynx.com", "https://streamwish.to")
            .replaceFirst("https://dumbalag.com", "https://streamwish.to")
            .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
            .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
            .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
            .replaceFirst("https://filemoon.link", "https://filemoon.sx")
            .replaceFirst("https://sblona.com", "https://watchsb.com")
            .replaceFirst("https://lulu.st", "https://lulustream.com")
            .replaceFirst("https://uqload.io", "https://uqload.com")
            .replaceFirst("https://do7go.com", "https://dood.la")
    }

    private data class EpisodeData(
        val url: String,
        val title: String,
        val poster: String?,
        val year: Int,
        val isMovie: Boolean
    )
}

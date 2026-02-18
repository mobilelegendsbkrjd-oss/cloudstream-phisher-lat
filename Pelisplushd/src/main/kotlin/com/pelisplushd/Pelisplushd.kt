package com.pelisplushd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

// =============================
// MODELOS JSON
// =============================
@Serializable
data class ApiResponse(
    @SerialName("props") var props: Props? = null
)

@Serializable
data class Props(
    @SerialName("pageProps") var pageProps: PageProps? = null
)

@Serializable
data class PageProps(
    @SerialName("thisMovie") var thisMovie: MediaItem? = null,
    @SerialName("thisSerie") var thisSerie: MediaItem? = null,
    @SerialName("episode") var episode: EpisodeInfo? = null,
    @SerialName("relatedMovies") var relatedMovies: List<RelatedMovie>? = null
)

@Serializable
data class RelatedMovie(
    val titles: Titles? = null,
    val slug: Slug? = null,
    val images: Images? = null
)

@Serializable
data class Titles(val name: String? = null)

@Serializable
data class Slug(val name: String? = null)

@Serializable
data class Images(val poster: String? = null)

@Serializable
data class MediaItem(
    @SerialName("videos") var videos: Videos? = null,
    @SerialName("seasons") var seasons: List<SeasonInfo>? = null
)

@Serializable
data class SeasonInfo(
    @SerialName("number") val number: Int? = null,
    @SerialName("episodes") val episodes: List<JsonEpisode>? = null
)

@Serializable
data class JsonEpisode(
    @SerialName("title") val title: String? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("url") val url: JsonUrl? = null,
    @SerialName("image") val image: String? = null
)

@Serializable
data class JsonUrl(val slug: String? = null)

@Serializable
data class EpisodeInfo(
    @SerialName("videos") var videos: Videos? = null
)

@Serializable
data class Videos(
    @SerialName("latino") var latino: ArrayList<VideoInfo>? = null,
    @SerialName("spanish") var spanish: ArrayList<VideoInfo>? = null,
    @SerialName("english") var english: ArrayList<VideoInfo>? = null
)

@Serializable
data class VideoInfo(
    @SerialName("result") var result: String? = null
)

// =============================
// MAIN API
// =============================
class Pelisplushd : MainAPI() {
    override var mainUrl = "https://www.pelisplushd.la"
    override var name = "Pelisplus HD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/page/" to "Películas",
        "$mainUrl/series/page/" to "Series",
        "$mainUrl/animes/page/" to "Animes",
        "$mainUrl/peliculas/estrenos/page/" to "Estrenos Películas",
        "$mainUrl/series/estrenos/page/" to "Estrenos Series",
        "$mainUrl/peliculas/populares/page/" to "Películas Populares",
        "$mainUrl/series/populares/page/" to "Series Populares",
        "$mainUrl/generos/dorama/page/" to "Doramas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.endsWith("/")) "${request.data}$page" else "${request.data}/$page"

        val doc = try {
            app.get(url, headers = requestHeaders).document
        } catch (e: Exception) {
            app.get(request.data, headers = requestHeaders).document
        }

        val items = doc.select("div.Posters a.Posters-link, .MovieList .TPostMv a, main .Posters a")
            .mapNotNull { element ->
                element.toSearchResult()
            }
            .distinctBy { it.url.trimEnd('/').substringAfterLast("/") }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = when {
            hasAttr("data-title") -> attr("data-title")?.substringBefore(" Online Gratis HD")?.trim()
            else -> selectFirst(".listing-content p")?.text()
                ?: selectFirst("p")?.text()
        } ?: return null

        val href = attr("href") ?: return null

        // Determinar el tipo por la clase centrado dentro del elemento
        val type = when {
            selectFirst(".movies.centrado") != null -> TvType.Movie
            selectFirst(".series.centrado") != null -> TvType.TvSeries
            selectFirst(".animes.centrado") != null -> TvType.Anime
            href.contains("/serie/") -> TvType.TvSeries
            href.contains("/anime/") || href.contains("/animes/") -> TvType.Anime
            href.contains("/generos/dorama") || href.contains("/dorama") -> TvType.AsianDrama
            else -> TvType.Movie
        }

        val poster = selectFirst("img")?.let { extractPoster(it) }

        return when (type) {
            TvType.Movie -> newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
            }
            TvType.Anime -> newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = poster
            }
            TvType.AsianDrama -> newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) {
                this.posterUrl = poster
            }
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?s=$encoded"

        val doc = app.get(searchUrl, headers = requestHeaders).document

        return doc.select("div.Posters a.Posters-link")
            .mapNotNull { element ->
                element.toSearchResult()
            }
            .distinctBy { it.url.trimEnd('/').substringAfterLast("/") }
    }

    override suspend fun load(url: String): LoadResponse {
        val currentUrl = url
        val doc = app.get(currentUrl, headers = requestHeaders).document

        // Título
        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - ")
            ?: "Sin título"

        // Póster
        val poster = doc.selectFirst("img[src*='/poster/'], .card-body img.img-fluid")?.let { extractPoster(it) }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }

        // Descripción - FORZAMOS String no nulo
        val description: String = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst(".text-large")?.text()
            ?: ""

        // Año
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()

        // Rating - Convertir de escala 0-10 a 0-5
        val ratingText = doc.selectFirst(".rating .ion-md-star span, .font-size-18.ion-md-star")?.text()
        val rating = ratingText?.replace("/10", "")?.toFloatOrNull()?.let {
            (it * 5 / 10).toInt()
        }

        // Determinar si es serie
        val isSeries = currentUrl.contains("/serie/") ||
                currentUrl.contains("/anime/") ||
                currentUrl.contains("/dorama") ||
                doc.select(".VideoPlayer ul.TbVideoNv li a[href*='pills-vertical']").isNotEmpty()

        val episodesList = mutableListOf<Episode>()
        val recommendations = mutableListOf<SearchResponse>()

        if (isSeries) {
            // Procesar temporadas y episodios
            val seasonTabs = doc.select(".VideoPlayer ul.TbVideoNv li a[href*='pills-vertical']")

            seasonTabs.forEachIndexed { index, tab ->
                val seasonNumber = index + 1
                val tabId = tab.attr("href").replace("#", "").replace(" ", "")

                // Buscar el panel de esta temporada
                val seasonPanel = doc.selectFirst("div.tab-content div#$tabId, div.tab-content div[id*='$tabId']")

                if (seasonPanel != null) {
                    // Buscar todos los botones de episodios dentro del panel
                    val episodeButtons = seasonPanel.select("a.btn-block[href*='/capitulo/']")

                    episodeButtons.forEach { button ->
                        val episodeUrl = button.attr("href")
                        val episodeText = button.text()

                        // Extraer número de episodio de la URL
                        val episodeMatch = Regex("""capitulo/(\d+)""").find(episodeUrl)
                        val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                        episodesList.add(newEpisode(fixUrl(episodeUrl)) {
                            this.name = episodeText
                            this.season = seasonNumber
                            this.episode = episodeNumber
                        })
                    }
                }
            }

            // Si no encuentra con tabs, buscar directamente todos los enlaces de episodios
            if (episodesList.isEmpty()) {
                doc.select("a.btn-block[href*='/capitulo/']").forEach { button ->
                    val episodeUrl = button.attr("href")
                    val episodeText = button.text()

                    val seasonMatch = Regex("""temporada/(\d+)/""").find(episodeUrl)
                    val episodeMatch = Regex("""capitulo/(\d+)""").find(episodeUrl)

                    val seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    episodesList.add(newEpisode(fixUrl(episodeUrl)) {
                        this.name = episodeText
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    })
                }
            }
        }

        // Recomendaciones
        doc.select("aside .Posters a.Posters-link, .related .Posters a, .MovieList a.Posters-link")
            .forEach { el ->
                val recTitle = el.attr("data-title")?.substringBefore(" Online Gratis HD")?.trim()
                    ?: el.selectFirst(".listing-content p")?.text()
                    ?: return@forEach

                val recHref = el.attr("href") ?: return@forEach
                val recPoster = extractPoster(el.selectFirst("img"))

                val recType = when {
                    el.selectFirst(".movies.centrado") != null -> TvType.Movie
                    el.selectFirst(".series.centrado") != null -> TvType.TvSeries
                    el.selectFirst(".animes.centrado") != null -> TvType.Anime
                    recHref.contains("/serie/") -> TvType.TvSeries
                    recHref.contains("/anime/") -> TvType.Anime
                    recHref.contains("/dorama") -> TvType.AsianDrama
                    else -> TvType.Movie
                }

                val resp = when (recType) {
                    TvType.Movie -> newMovieSearchResponse(recTitle, fixUrl(recHref), TvType.Movie) {
                        this.posterUrl = recPoster
                    }
                    TvType.TvSeries -> newTvSeriesSearchResponse(recTitle, fixUrl(recHref), TvType.TvSeries) {
                        this.posterUrl = recPoster
                    }
                    TvType.Anime -> newAnimeSearchResponse(recTitle, fixUrl(recHref), TvType.Anime) {
                        this.posterUrl = recPoster
                    }
                    TvType.AsianDrama -> newTvSeriesSearchResponse(recTitle, fixUrl(recHref), TvType.AsianDrama) {
                        this.posterUrl = recPoster
                    }
                    else -> null
                }
                if (resp != null) recommendations.add(resp)
            }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, currentUrl, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.rating = rating
                this.recommendations = recommendations.distinctBy { it.url }.take(12)
            }
        } else {
            newMovieLoadResponse(title, currentUrl, TvType.Movie, currentUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.rating = rating
                this.recommendations = recommendations.distinctBy { it.url }.take(12)
            }
        }
    }

    private fun extractPoster(imgElement: Element?): String? {
        if (imgElement == null) return null

        var src = imgElement.attr("src")
        if (src.isBlank()) src = imgElement.attr("data-src")

        return if (src.isNotBlank()) {
            if (src.startsWith("http")) src else fixUrl(src)
        } else null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val doc = app.get(data, headers = requestHeaders).document

        // Buscar los enlaces en los elementos li.playurl con data-url
        val videoItems = doc.select("li.playurl[data-url]")

        videoItems.forEach { item ->
            val embedUrl = item.attr("data-url")
            val quality = item.attr("data-name") ?: "Latino"

            if (embedUrl.isNotBlank()) {
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        // Si no encuentra con el método anterior, buscar iframes directamente
        if (!found) {
            doc.select("iframe[src], video source[src]").forEach { element ->
                val src = element.attr("src") ?: element.attr("data-src") ?: return@forEach
                if (src.isNotBlank() && src.contains("http")) {
                    if (loadExtractor(src, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        return found
    }
}
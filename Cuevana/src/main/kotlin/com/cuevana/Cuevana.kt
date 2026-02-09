package com.cuevana

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- MODELOS ---
@Serializable data class ApiResponse(@SerialName("props") var props: Props? = Props())
@Serializable data class Props(@SerialName("pageProps") var pageProps: PageProps? = PageProps())
@Serializable data class PageProps(
    @SerialName("thisMovie") var thisMovie: MediaItem? = MediaItem(),
    @SerialName("episode") var episode: EpisodeInfo? = EpisodeInfo()
)
@Serializable data class MediaItem(@SerialName("videos") var videos: Videos? = Videos())
@Serializable data class EpisodeInfo(@SerialName("videos") var videos: Videos? = Videos())
@Serializable data class Videos(
    @SerialName("latino") var latino: ArrayList<VideoInfo>? = arrayListOf(),
    @SerialName("spanish") var spanish: ArrayList<VideoInfo>? = arrayListOf(),
    @SerialName("english") var english: ArrayList<VideoInfo>? = arrayListOf()
)
@Serializable data class VideoInfo(@SerialName("result") var result: String? = null)

class Cuevana : MainAPI() {
    override var mainUrl = "https://wv3.cuevana3.eu"
    override var name = "Cuevana"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/estrenos/page/" to "Estrenos Películas",
        "$mainUrl/series/estrenos/page/" to "Estrenos Series",
        "$mainUrl/genero/accion/page/" to "Acción",
        "$mainUrl/genero/animacion/page/" to "Animación",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("page")) "${request.data}$page" else "${request.data}/page/$page"
        val doc = app.get(url).document
        val items = doc.select(".MovieList .TPost, article.TPost, .TPost.C").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".Title")?.text() ?: return null
        val originalHref = selectFirst("a")?.attr("href") ?: return null

        val slug = originalHref.trimEnd('/').substringAfterLast("/")
        val isSeries = originalHref.contains("/serie") || originalHref.contains("/series/")
        val fixedHref = if (isSeries) "/ver-serie/$slug" else "/ver-pelicula/$slug"

        // ARREGLO DE IMÁGENES (Más agresivo)
        val img = selectFirst("img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            ?: img?.attr("src")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(fixedHref), TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fixUrl(fixedHref), TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - ")
            ?: doc.selectFirst("h1.Title")?.text() ?: "Sin título"
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".Image img")?.attr("src")
        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")

        if (url.contains("/ver-serie/")) {
            val episodes = doc.select("ul.all-episodes li.TPostMv").mapNotNull { li ->
                val link = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val name = li.selectFirst(".Title")?.text() ?: "Episodio"
                val seasonText = li.selectFirst(".Year")?.text()

                newEpisode(fixUrl(link)) {
                    this.name = name
                    this.season = seasonText?.substringBefore("x")?.toIntOrNull()
                    this.episode = seasonText?.substringAfterLast("x")?.toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val doc = app.get(data).document
        var found = false

        // 1. EXTRAER DEL JSON (MÉTODO PRINCIPAL)
        try {
            val jsonData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            if (jsonData != null) {
                val res = parseJson<ApiResponse>(jsonData)
                val videoData = res.props?.pageProps?.thisMovie?.videos ?: res.props?.pageProps?.episode?.videos

                listOfNotNull(videoData?.latino, videoData?.spanish, videoData?.english).flatten().forEach {
                    val link = it.result?.replace("\\/", "/") ?: return@forEach
                    if (loadExtractor(link, data, subtitleCallback, callback)) found = true
                }
            }
        } catch (e: Exception) { }

        // 2. EXTRAER POR REGEX (PARA PLAYER.PHP Y EMBEDS)
        val regex = Regex("(https?://[^\\\"'\\s]+)")
        regex.findAll(doc.html()).forEach { match ->
            val link = match.value.replace("\\/", "/")
            if (link.contains(Regex("streamwish|filemoon|vidhide|voe|dood|tomatomatoma|embedsito|gamovideo|okru"))) {
                if (loadExtractor(link, data, subtitleCallback, callback)) found = true
            }
        }

        // 3. MÉTODO FALLBACK: PETICIÓN A API EXTERNA (La que tenías al principio)
        if (!found) {
            val response = app.post(
                "$mainUrl/wp-json/aio-dl/video",
                data = mapOf("url" to data),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data)
            ).text
            regex.findAll(response).forEach { match ->
                val link = match.value.replace("\\/", "/")
                if (loadExtractor(link, data, subtitleCallback, callback)) found = true
            }
        }

        return found
    }
}
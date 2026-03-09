package com.cuevana

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

// ================= JSON MODELS =================

@Serializable
data class ApiResponse(
    @SerialName("props") val props: Props? = null
)

@Serializable
data class Props(
    @SerialName("pageProps") val pageProps: PageProps? = null
)

@Serializable
data class PageProps(
    @SerialName("thisMovie") val thisMovie: MediaItem? = null,
    @SerialName("thisSerie") val thisSerie: MediaItem? = null,
    @SerialName("episode") val episode: EpisodeInfo? = null
)

@Serializable
data class MediaItem(
    @SerialName("videos") val videos: Videos? = null,
    @SerialName("seasons") val seasons: List<SeasonInfo>? = null
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
    @SerialName("url") val url: JsonUrl? = null
)

@Serializable
data class JsonUrl(
    @SerialName("slug") val slug: String? = null
)

@Serializable
data class EpisodeInfo(
    @SerialName("videos") val videos: Videos? = null
)

@Serializable
data class Videos(
    @SerialName("latino") val latino: List<VideoInfo>? = null,
    @SerialName("spanish") val spanish: List<VideoInfo>? = null,
    @SerialName("english") val english: List<VideoInfo>? = null
)

@Serializable
data class VideoInfo(
    @SerialName("result") val result: String? = null
)

// ================= MAIN API =================

class Cuevana : MainAPI() {

    override var name = "Cuevana"
    override var mainUrl = "https://wv3.cuevana3.eu"
    override var lang = "es"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    // ================= HOME =================

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/estrenos/page/" to "Películas",
        "$mainUrl/series/estrenos/page/" to "Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = request.data + page

        val doc = app.get(url, headers = headers).document

        val items = doc.select(".MovieList li.TPostMv")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"

        val doc = app.get(url, headers = headers).document

        return doc.select(".MovieList li.TPostMv")
            .mapNotNull { it.toSearchResult() }
    }

    // ================= PARSER =================

    private fun Element.toSearchResult(): SearchResponse? {

        val title = selectFirst(".Title")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null

        val poster = extractPoster(selectFirst("img"))

        val isSeries = href.contains("/serie")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url, headers = headers).document

        val title =
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - ")
                ?: "Sin título"

        val poster =
            doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description =
            doc.selectFirst("meta[property=og:description]")
                ?.attr("content")

        val isSeries = url.contains("/ver-serie")

        val episodes = mutableListOf<Episode>()

        if (isSeries) {

            try {

                val json = doc.selectFirst("script#__NEXT_DATA__")?.data()
                val parsed = parseJson<ApiResponse>(json!!)

                parsed.props?.pageProps?.thisSerie?.seasons?.forEach { s ->

                    s.episodes?.forEach { e ->

                        val slug = e.url?.slug ?: return@forEach

                        episodes.add(
                            newEpisode("$mainUrl/$slug") {
                                this.name = e.title
                                this.season = s.number
                                this.episode = e.number
                            }
                        )
                    }
                }

            } catch (_: Exception) {}
        }

        return if (isSeries) {

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = description
            }

        } else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // ================= VIDEO RESOLVER =================

    private fun extractFinalVideo(script: String): String? {

        Regex("var\\s*url\\s*=\\s*['\"](.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }

        Regex("window.location.href\\s*=\\s*['\"](.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }

        Regex("location.replace\\(['\"](.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }

        Regex("sources:\\s*\\[\\{file:\\s*['\"](.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }

        Regex("file:\\s*['\"](https?://.*?)['\"]")
            .find(script)?.groupValues?.get(1)?.let { return it }

        return null
    }

    // ================= LINKS =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = headers).document

        var found = false

        val json = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return false

        val parsed = parseJson<ApiResponse>(json)

        val videos =
            parsed.props?.pageProps?.thisMovie?.videos
                ?: parsed.props?.pageProps?.episode?.videos
                ?: return false

        suspend fun process(video: VideoInfo) {

            val embed = video.result ?: return

            val embedDoc = app.get(embed, referer = data).document

            val scripts = embedDoc.select("script")

            var finalUrl: String? = null

            for (s in scripts) {

                val extracted = extractFinalVideo(s.data())

                if (extracted != null) {
                    finalUrl = extracted
                    break
                }
            }

            if (!finalUrl.isNullOrBlank()) {

                if (loadExtractor(finalUrl, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        videos.latino?.forEach { process(it) }
        videos.spanish?.forEach { process(it) }
        videos.english?.forEach { process(it) }

        return found
    }

    // ================= UTILS =================

    private fun extractPoster(img: Element?): String? {

        if (img == null) return null

        var src = img.attr("data-src")

        if (src.isBlank()) src = img.attr("src")

        if (src.contains("_next/image")) {

            val encoded = src.substringAfter("url=").substringBefore("&")

            return URLDecoder.decode(encoded, "UTF-8")
        }

        return src
    }
}

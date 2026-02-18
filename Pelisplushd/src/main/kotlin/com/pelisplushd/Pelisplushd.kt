package com.pelisplushd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

class Pelisplushd : MainAPI() {
    override var name = "Pelisplushd"
    override var mainUrl = "https://embed69.org"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "1865f43a0549ca50d341dd9ab8b29f49"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("Películas Populares", "$tmdbAPI/movie/popular?api_key=$apiKey&language=es-ES&page=$page"),
            Pair("Series Populares", "$tmdbAPI/tv/popular?api_key=$apiKey&language=es-ES&page=$page"),
            Pair("Estrenos", "$tmdbAPI/movie/now_playing?api_key=$apiKey&language=es-ES&page=$page"),
        )

        val items = arrayListOf<HomePageList>()

        for ((name, url) in urls) {
            val home = app.get(url).parsedSafe<TmdbResults>()?.results?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: continue

            items.add(HomePageList(name, home))
        }

        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$tmdbAPI/search/multi?api_key=$apiKey&language=es-ES&query=$query&page=1"
        return app.get(url).parsedSafe<TmdbResults>()?.results?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: emptyList()
    }

    private fun TmdbMedia.toSearchResponse(): SearchResponse? {
        val title = title ?: name ?: return null
        val type = if (mediaType == "tv") TvType.TvSeries else TvType.Movie
        val poster = if (!posterPath.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null

        return newMovieSearchResponse(title, Data(id, mediaType ?: "movie").toJson(), type) {
            this.posterUrl = poster
            this.score = voteAverage?.let { Score.from10(it) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = if (data.type == "tv") TvType.TvSeries else TvType.Movie

        val apiUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&language=es-ES&append_to_response=credits,videos"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&language=es-ES&append_to_response=credits,videos"
        }

        val details = app.get(apiUrl).parsedSafe<TmdbDetails>() ?: return null

        val title = details.title ?: details.name ?: return null
        val poster = "https://image.tmdb.org/t/p/original${details.posterPath}"
        val background = details.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
        val year = details.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            ?: details.firstAirDate?.split("-")?.firstOrNull()?.toIntOrNull()
        
        val actors = details.credits?.cast?.mapNotNull { cast ->
            cast.name?.let { name ->
                ActorData(
                    Actor(
                        name,
                        cast.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                    ),
                    roleString = cast.character
                )
            }
        } ?: emptyList()

        val trailer = details.videos?.results?.firstOrNull { it.type == "Trailer" }?.let {
            "https://www.youtube.com/watch?v=${it.key}"
        }

        if (type == TvType.TvSeries) {
            val seasons = details.seasons?.filter { it.seasonNumber > 0 } ?: emptyList()
            val episodes = mutableListOf<Episode>()

            for (season in seasons) {
                val seasonUrl = "$tmdbAPI/tv/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=es-ES"
                val seasonDetails = app.get(seasonUrl).parsedSafe<TmdbSeason>() ?: continue

                seasonDetails.episodes?.forEach { ep ->
                    episodes.add(
                        newEpisode(
                            LoadData(
                                id = data.id,
                                type = data.type,
                                imdbId = details.externalIds?.imdbId,
                                season = ep.seasonNumber,
                                episode = ep.episodeNumber
                            ).toJson()
                        ) {
                            this.name = ep.name ?: "Episodio ${ep.episodeNumber}"
                            this.season = ep.seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                            this.description = ep.overview
                            this.addDate(ep.airDate)
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = details.overview
                this.tags = details.genres?.map { it.name }
                this.score = details.voteAverage?.let { Score.from10(it) }
                this.actors = actors
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, LoadData(data.id, data.type).toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = details.overview
                this.duration = details.runtime
                this.tags = details.genres?.map { it.name }
                this.score = details.voteAverage?.let { Score.from10(it) }
                this.actors = actors
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        val id = loadData.imdbId ?: return false

        val iframe = if (loadData.season == null) {
            "$mainUrl/f/$id"
        } else {
            val episodeFormatted = loadData.episode?.toString()?.padStart(2, '0') ?: "01"
            "$mainUrl/f/$id-${loadData.season}x$episodeFormatted"
        }

        val doc = app.get(iframe).document
        val script = doc.selectFirst("script:containsData(dataLink)")?.data()
        val jsonString = script?.substringAfter("dataLink = ")?.substringBefore(";")?.trim()

        if (jsonString.isNullOrBlank()) return false

        try {
            val jsonArray = JSONArray(jsonString)

            for (i in 0 until jsonArray.length()) {
                val fileObject = jsonArray.getJSONObject(i)
                val embeds = fileObject.getJSONArray("sortedEmbeds")
                val language = fileObject.getString("video_language")

                val serverLinks = mutableListOf<String>()
                for (j in 0 until embeds.length()) {
                    val embedObj = embeds.getJSONObject(j)
                    embedObj.optString("link").let { link ->
                        if (link.isNotBlank()) serverLinks.add("\"$link\"")
                    }
                }

                if (serverLinks.isNotEmpty()) {
                    val jsonBody = """{"links":$serverLinks}"""
                    val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                    val decrypted = app.post("$mainUrl/api/decrypt", requestBody = requestBody)
                        .parsedSafe<DecryptResponse>()

                    if (decrypted?.success == true) {
                        decrypted.links?.forEach { linkObj ->
                            linkObj.link?.let { videoUrl ->
                                loadExtractor(videoUrl, iframe, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorar errores de parsing
        }

        // Subtítulos
        try {
            val subApiUrl = "https://opensubtitles-v3.strem.io"
            val subUrl = if (loadData.season == null) {
                "$subApiUrl/subtitles/movie/$id.json"
            } else {
                "$subApiUrl/subtitles/series/$id:${loadData.season}:${loadData.episode}.json"
            }

            val headers = mapOf("User-Agent" to USER_AGENT)
            val subtitles = app.get(subUrl, headers = headers, timeout = 100L)
                .parsedSafe<SubtitleResponse>()?.subtitles

            subtitles?.forEach { sub ->
                subtitleCallback(
                    newSubtitleFile(
                        sub.lang,
                        sub.url
                    )
                )
            }
        } catch (e: Exception) {
            // Ignorar errores de subtítulos
        }

        return true
    }
}

// Modelos de datos
data class Data(
    val id: Int,
    val type: String,
)

data class LoadData(
    val id: Int,
    val type: String,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

// Modelos TMDB
data class TmdbResults(
    val results: List<TmdbMedia>?,
)

data class TmdbMedia(
    val id: Int,
    val title: String?,
    val name: String?,
    val posterPath: String?,
    @JsonProperty("vote_average") val voteAverage: Double?,
    @JsonProperty("media_type") val mediaType: String?,
)

data class TmdbDetails(
    val id: Int,
    val title: String?,
    val name: String?,
    val overview: String?,
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("backdrop_path") val backdropPath: String?,
    @JsonProperty("release_date") val releaseDate: String?,
    @JsonProperty("first_air_date") val firstAirDate: String?,
    @JsonProperty("vote_average") val voteAverage: Double?,
    val runtime: Int?,
    val genres: List<TmdbGenre>?,
    val credits: TmdbCredits?,
    val videos: TmdbVideos?,
    val seasons: List<TmdbSeasonInfo>?,
    @JsonProperty("external_ids") val externalIds: TmdbExternalIds?,
)

data class TmdbGenre(
    val id: Int,
    val name: String,
)

data class TmdbCredits(
    val cast: List<TmdbCast>?,
)

data class TmdbCast(
    val name: String?,
    val character: String?,
    @JsonProperty("profile_path") val profilePath: String?,
)

data class TmdbVideos(
    val results: List<TmdbVideo>?,
)

data class TmdbVideo(
    val key: String,
    val type: String?,
)

data class TmdbSeasonInfo(
    @JsonProperty("season_number") val seasonNumber: Int,
)

data class TmdbSeason(
    val episodes: List<TmdbEpisode>?,
)

data class TmdbEpisode(
    val name: String?,
    val overview: String?,
    @JsonProperty("still_path") val stillPath: String?,
    @JsonProperty("air_date") val airDate: String?,
    @JsonProperty("season_number") val seasonNumber: Int,
    @JsonProperty("episode_number") val episodeNumber: Int,
)

data class TmdbExternalIds(
    @JsonProperty("imdb_id") val imdbId: String?,
)

// Modelos para la respuesta de la API
data class DecryptResponse(
    val success: Boolean,
    val links: List<DecryptLink>?,
)

data class DecryptLink(
    val index: Long,
    val link: String?,
)

data class SubtitleResponse(
    val subtitles: List<Subtitle>?,
)

data class Subtitle(
    val url: String,
    val lang: String,
)

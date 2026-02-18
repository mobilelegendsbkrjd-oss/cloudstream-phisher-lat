package com.pelisplushd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

class Pelisplushd : MainAPI() {
    override var name = "Pelisplushd"
    override var mainUrl = "https://pelisplushd.bz"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val apiKey = "1865f43a0549ca50d341dd9ab8b29f49"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.name) {
            "Trending" -> "$tmdbAPI/trending/all/day?api_key=$apiKey&region=US"
            "Popular Movies" -> "$tmdbAPI/trending/movie/week?api_key=$apiKey&region=US"
            "Popular TV Shows" -> "$tmdbAPI/trending/tv/week?api_key=$apiKey&region=US"
            else -> request.data
        }
        
        val response = app.get("$url&language=es-ES&page=$page")
        val results = response.parsedSafe<TmdbResults>()?.results ?: emptyList()
        
        val home = results.mapNotNull { media ->
            media.toSearchResponse()
        }
        
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$tmdbAPI/search/multi?api_key=$apiKey&language=es-ES&query=$query&page=1"
        val response = app.get(url)
        return response.parsedSafe<TmdbResults>()?.results?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: emptyList()
    }

    private fun TmdbMedia.toSearchResponse(): SearchResponse? {
        val title = title ?: name ?: return null
        val type = if (mediaType == "tv") TvType.TvSeries else TvType.Movie
        val poster = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        
        return newMovieSearchResponse(title, Data(id, mediaType ?: "movie").toJson(), type) {
            this.posterUrl = poster
            this.score = voteAverage?.let { Score.from10(it, 10) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = AppUtils.parseJson<Data>(url)
        val type = if (data.type == "tv") TvType.TvSeries else TvType.Movie

        val apiUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&language=es-ES&append_to_response=credits,videos,external_ids"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&language=es-ES&append_to_response=credits,videos,external_ids"
        }

        val details = app.get(apiUrl).parsedSafe<TmdbDetails>() ?: return null

        val title = details.title ?: details.name ?: return null
        val poster = details.posterPath?.let { "https://image.tmdb.org/t/p/original$it" }
        val background = details.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
        val year = details.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            ?: details.firstAirDate?.split("-")?.firstOrNull()?.toIntOrNull()
        
        val trailer = details.videos?.results?.firstOrNull { it.type == "Trailer" }?.let {
            "https://www.youtube.com/watch?v=${it.key}"
        }

        if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            
            details.seasons?.filter { it.seasonNumber > 0 }?.forEach { season ->
                val seasonUrl = "$tmdbAPI/tv/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=es-ES"
                val seasonDetails = app.get(seasonUrl).parsedSafe<TmdbSeason>() ?: return@forEach
                
                seasonDetails.episodes?.forEach { ep ->
                    episodes.add(
                        newEpisode(
                            AppUtils.toJson(
                                LoadData(
                                    imdbId = details.externalIds?.imdbId,
                                    season = ep.seasonNumber,
                                    episode = ep.episodeNumber
                                )
                            )
                        ) {
                            this.name = ep.name ?: "Episodio ${ep.episodeNumber}"
                            this.season = ep.seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                            this.description = ep.overview
                            ep.airDate?.let { this.addDate(it) }
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
                this.rating = details.voteAverage
                trailer?.let { this.addTrailer(it) }
                details.externalIds?.imdbId?.let { this.addImdbId(it) }
                this.addTMDbId(data.id.toString())
            }
        } else {
            return newMovieLoadResponse(
                title, 
                url, 
                TvType.Movie, 
                AppUtils.toJson(LoadData(imdbId = details.externalIds?.imdbId))
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = details.overview
                this.duration = details.runtime
                this.tags = details.genres?.map { it.name }
                this.rating = details.voteAverage
                trailer?.let { this.addTrailer(it) }
                details.externalIds?.imdbId?.let { this.addImdbId(it) }
                this.addTMDbId(data.id.toString())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataObj = AppUtils.parseJson<LoadData>(data)
        val season = dataObj.season
        val episode = dataObj.episode
        val id = dataObj.imdbId ?: return false

        val iframe = if (season == null) {
            "$mainUrl/f/$id"
        } else {
            val episodeFormatted = episode?.toString()?.padStart(2, '0') ?: "01"
            "$mainUrl/f/$id-${season}x$episodeFormatted"
        }

        val doc = app.get(iframe, headers = mapOf("User-Agent" to USER_AGENT)).document
        val script = doc.selectFirst("script:containsData(dataLink)")?.data()
        val jsonString = script?.substringAfter("dataLink = ")?.substringBefore(";")?.trim()

        if (jsonString.isNullOrBlank()) return false

        try {
            val jsonArray = JSONArray(jsonString)

            for (i in 0 until jsonArray.length()) {
                val fileObject = jsonArray.getJSONObject(i)
                val language = fileObject.optString("video_language", "es")
                val embeds = fileObject.getJSONArray("sortedEmbeds")

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

                    val response = app.post("$mainUrl/api/decrypt", requestBody = requestBody)
                    val responseText = response.text

                    if (responseText.contains("success")) {
                        val jsonResponse = org.json.JSONObject(responseText)
                        val linksArray = jsonResponse.optJSONArray("links")
                        
                        if (linksArray != null) {
                            for (k in 0 until linksArray.length()) {
                                val linkObj = linksArray.getJSONObject(k)
                                val videoUrl = linkObj.optString("link")
                                if (videoUrl.isNotBlank()) {
                                    callback.invoke(
                                        newExtractorLink(
                                            this.name,
                                            "${language.uppercase()} ${i + 1}",
                                            videoUrl,
                                            getQualityFromName("HD")
                                        ) {
                                            this.isM3u8 = true
                                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
                                        }
                                    )
                                }
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
            val subUrl = if (season == null) {
                "$subApiUrl/subtitles/movie/$id.json"
            } else {
                "$subApiUrl/subtitles/series/$id:$season:$episode.json"
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
    @JsonProperty("poster_path") val posterPath: String?,
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
    val status: String?,
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

data class SubtitleResponse(
    val subtitles: List<Subtitle>?,
)

data class Subtitle(
    val url: String,
    val lang: String,
)

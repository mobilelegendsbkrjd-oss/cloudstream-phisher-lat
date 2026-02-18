package com.pelisplushd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.APIHolder.capitalize
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class Pelisplushd : TmdbProvider() {
    override var name = "Pelisplushd"
    override var mainUrl = "https://embed69.org" //https://pelisplushd.bz/
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override var lang = "mx"
    override val hasMainPage = true
    override val hasQuickSearch = false

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "1865f43a0549ca50d341dd9ab8b29f49"

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?api_key=$apiKey&region=US" to "Trending",
        "$tmdbAPI/trending/movie/week?api_key=$apiKey&region=US&with_original_language=en" to "Popular Movies",
        "$tmdbAPI/trending/tv/week?api_key=$apiKey&region=US&with_original_language=en" to "Popular TV Shows",
        "$tmdbAPI/tv/airing_today?api_key=$apiKey&region=US&with_original_language=en" to "Airing Today TV Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=213" to "Netflix",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=1024" to "Amazon",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2739" to "Disney+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=453" to "Hulu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2552" to "Apple TV+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=49" to "HBO",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=4330" to "Paramount+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=3353" to "Peacock",
        "$tmdbAPI/discover/movie?api_key=$apiKey&language=en-US&page=1&sort_by=popularity.desc&with_origin_country=IN&release_date.gte=${getDate().lastWeekStart}&release_date.lte=${getDate().today}" to "Trending Indian Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&sort_by=popularity.desc&air_date.lte=${getDate().today}&air_date.gte=${getDate().today}" to "Airing Today Anime",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_keywords=210024|222243&sort_by=popularity.desc&air_date.lte=${getDate().nextWeek}&air_date.gte=${getDate().today}" to "On The Air Anime",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_keywords=210024|222243" to "Anime Movies",
        "$tmdbAPI/movie/top_rated?api_key=$apiKey&region=US" to "Top Rated Movies",
        "$tmdbAPI/tv/top_rated?api_key=$apiKey&region=US" to "Top Rated TV Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko" to "Korean Shows",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_genres=99" to "Documentary",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original$link" else link
    }

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val adultQuery =
            if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669|190370"
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get("${request.data}$adultQuery&language=es-ES&page=$page")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            if (mediaType == "tv" || type == "tv") TvType.TvSeries else TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
            this.score = Score.from10(voteAverage)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return app.get(
            "$tmdbAPI/search/multi?api_key=$apiKey&language=es-ES&query=$query&page=$page&include_adult=${settingsForProvider.enableAdult}"
        ).parsedSafe<Results>()?.results?.mapNotNull { media ->
            media.toSearchResponse()
        }?.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = getType(data.type)
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&language=es-ES&append_to_response=keywords,credits,external_ids,videos,recommendations"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&language=es-ES&append_to_response=keywords,credits,external_ids,videos,recommendations"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }
        val isAnime =
            genres?.contains("Animation") == true && (res.original_language == "zh" || res.original_language == "ja")
        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName ?: return@mapNotNull null,
                    getImageUrl(cast.profilePath)
                ), roleString = cast.character
            )
        }
        val recommendations =
            res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer =
            res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }?.randomOrNull()

        return if (type == TvType.TvSeries) {
            val episodes = res.seasons?.mapNotNull { season ->
                app.get("$tmdbAPI/tv/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=es-ES")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        newEpisode(
                            LoadData(
                                title = res.title,
                                year = year,
                                isAnime = isAnime,
                                imdbId = res.external_ids?.imdb_id,
                                season = eps.seasonNumber,
                                episode = eps.episodeNumber
                            ).toJson()
                        ) {
                            this.name = eps.name + if (isUpcoming(eps.airDate)) " • [PRÓXIMO]" else ""
                            this.season = eps.seasonNumber
                            this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath)
                            this.score = Score.from10(eps.voteAverage)
                            this.description = eps.overview
                            this.addDate(eps.airDate)
                        }
                    }
            }?.flatten() ?: listOf()
            
            newTvSeriesLoadResponse(
                title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(res.vote_average)
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LoadData(
                    title = res.title,
                    year = year,
                    isAnime = isAnime,
                    imdbId = res.external_ids?.imdb_id
                ).toJson()
            ) {
                this.posterUrl = poster
                this.comingSoon = isUpcoming(releaseDate)
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(res.vote_average)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataObj = parseJson<LoadData>(data)
        val season = dataObj.season
        val episode = dataObj.episode
        val id = dataObj.imdbId
        
        // Construir URL del iframe
        val iframe = if (season == null) { 
            "$mainUrl/f/$id" 
        } else { 
            // Formatear episodio con 2 dígitos
            val episodeFormatted = episode?.toString()?.padStart(2, '0') ?: "01"
            "$mainUrl/f/$id-${season}x$episodeFormatted"
        }
        
        val res = app.get(iframe).document
        val jsonString = res.selectFirst("script:containsData(dataLink)")?.data()
            ?.substringAfter("dataLink = ")
            ?.substringBefore(";")
            ?.trim()

        val allLinksByLanguage = mutableMapOf<String, MutableList<String>>()
        
        if (!jsonString.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(jsonString)

                for (i in 0 until jsonArray.length()) {
                    val fileObject = jsonArray.getJSONObject(i)
                    val language = fileObject.getString("video_language")
                    val embeds = fileObject.getJSONArray("sortedEmbeds")

                    val serverLinks = mutableListOf<String>()
                    for (j in 0 until embeds.length()) {
                        val embedObj = embeds.getJSONObject(j)
                        embedObj.optString("link").let { link ->
                            if (link.isNotBlank()) serverLinks.add("\"$link\"")
                        }
                    }

                    if (serverLinks.isNotEmpty()) {
                        val json = JSONObject().apply {
                            put("links", JSONArray(serverLinks))
                        }.toString()
                        
                        val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                        val decrypted = app.post("$mainUrl/api/decrypt", requestBody = requestBody)
                            .parsedSafe<Loadlinks>()

                        if (decrypted?.success == true) {
                            val links = decrypted.links.map { it.link }
                            val listForLang = allLinksByLanguage.getOrPut(language) { mutableListOf() }
                            listForLang.addAll(links)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Pelisplushd", "Error parsing JSON", e)
            }
        } else {
            Log.d("Pelisplushd", "dataLink not found in response")
        }

        // Cargar enlaces
        for ((language, links) in allLinksByLanguage) {
            links.forEach { link ->
                loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
            }
        }

        // Subtítulos
        try {
            val subApiUrl = "https://opensubtitles-v3.strem.io"
            val subUrl = if (season == null) {
                "$subApiUrl/subtitles/movie/$id.json"
            } else {
                "$subApiUrl/subtitles/series/$id:$season:$episode.json"
            }

            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "User-Agent" to USER_AGENT,
            )

            val subtitles = app.get(subUrl, headers = headers, timeout = 100L)
                .parsedSafe<Subtitles>()?.subtitles
                
            subtitles?.amap { sub ->
                val lang = getLanguage(sub.lang) ?: sub.lang
                subtitleCallback(
                    newSubtitleFile(
                        lang,
                        sub.url
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("Pelisplushd", "Error loading subtitles", e)
        }

        return true
    }
}

data class Subtitles(
    val subtitles: List<Subtitle>?,
    val cacheMaxAge: Long?,
)

data class Subtitle(
    val id: String?,
    val url: String,
    @JsonProperty("SubEncoding")
    val subEncoding: String?,
    val lang: String,
    val m: String?,
    val g: String?,
)

data class Loadlinks(
    val success: Boolean,
    val links: List<Link>?,
)

data class Link(
    val index: Long,
    val link: String,
)

data class Data(
    val id: Int,
    val type: String?,
)

data class LoadData(
    val title: String? = null,
    val year: Int? = null,
    val isAnime: Boolean = false,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

// Modelos TMDB
data class Results(
    val results: List<Media>?,
    val page: Int?,
    val total_pages: Int?,
    val total_results: Int?,
)

data class Media(
    val id: Int,
    val title: String?,
    val name: String?,
    val originalTitle: String?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val voteAverage: Double,
    val mediaType: String?,
)

data class MediaDetail(
    val id: Int,
    val title: String?,
    val name: String?,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val firstAirDate: String?,
    val vote_average: Double,
    val runtime: Int?,
    val status: String?,
    val original_language: String?,
    val genres: List<Genre>?,
    val keywords: Keywords?,
    val credits: Credits?,
    val external_ids: ExternalIds?,
    val videos: Videos?,
    val recommendations: Results?,
    val seasons: List<Season>?,
)

data class Genre(
    val id: Int,
    val name: String,
)

data class Keywords(
    val results: List<Keyword>?,
    val keywords: List<Keyword>?,
)

data class Keyword(
    val id: Int,
    val name: String,
)

data class Credits(
    val cast: List<Cast>?,
)

data class Cast(
    val name: String?,
    val originalName: String?,
    val character: String?,
    val profilePath: String?,
)

data class ExternalIds(
    val imdb_id: String?,
)

data class Videos(
    val results: List<Video>?,
)

data class Video(
    val key: String,
)

data class Season(
    val seasonNumber: Int,
)

data class MediaDetailEpisodes(
    val episodes: List<EpisodeDetail>?,
)

data class EpisodeDetail(
    val name: String?,
    val overview: String?,
    val stillPath: String?,
    val airDate: String?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val voteAverage: Double,
)

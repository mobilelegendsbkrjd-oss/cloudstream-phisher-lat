package com.Dailymotion

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse

class Dailymotion : MainAPI() {

    override var mainUrl = "https://api.dailymotion.com"
    override var name = "Dailymotion"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Others)

    // =============================================
    // DATA CLASSES
    // =============================================

    data class VideoSearchResponse(
        @JsonProperty("list") val list: List<VideoItem>
    )

    data class VideoItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("thumbnail_360_url") val thumbnail: String?
    )

    data class VideoDetailResponse(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("description") val description: String?,
        @JsonProperty("thumbnail_720_url") val thumbnail: String?
    )

    // =============================================
    // HOME PAGE
    // =============================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url =
            "$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page&sort=visited"

        val response = app.get(url).parsedSafe<VideoSearchResponse>() ?: return HomePageResponse(emptyList())

        val items = response.list.map {
            newMovieSearchResponse(
                it.title,
                "https://www.dailymotion.com/video/${it.id}",
                TvType.Movie
            ) {
                this.posterUrl = it.thumbnail
            }
        }

        return HomePageResponse(
            listOf(
                HomePageList("Trending Videos", items)
            )
        )
    }

    // =============================================
    // SEARCH
    // =============================================

    override suspend fun search(query: String): List<SearchResponse> {

        val url =
            "$mainUrl/videos?search=${query.encodeUrl()}&fields=id,title,thumbnail_360_url&limit=20"

        val response = app.get(url).parsedSafe<VideoSearchResponse>() ?: return emptyList()

        return response.list.map {
            newMovieSearchResponse(
                it.title,
                "https://www.dailymotion.com/video/${it.id}",
                TvType.Movie
            ) {
                this.posterUrl = it.thumbnail
            }
        }
    }

    // =============================================
    // LOAD
    // =============================================

    override suspend fun load(url: String): LoadResponse? {

        val videoId = url.substringAfterLast("/")

        val apiUrl =
            "$mainUrl/video/$videoId?fields=id,title,description,thumbnail_720_url"

        val response = app.get(apiUrl).parsedSafe<VideoDetailResponse>() ?: return null

        return newMovieLoadResponse(
            response.title,
            url,
            TvType.Movie,
            videoId
        ) {
            this.posterUrl = response.thumbnail
            this.plot = response.description
        }
    }

    // =============================================
    // LOAD LINKS
    // =============================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val embedUrl = "https://www.dailymotion.com/embed/video/$data"

        loadExtractor(embedUrl, subtitleCallback, callback)

        return true
    }

    // =============================================
    // UTILS
    // =============================================

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
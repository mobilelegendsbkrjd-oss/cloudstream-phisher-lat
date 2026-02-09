package com.Dailymotion

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class Dailymotion : MainAPI() {

    override var mainUrl = "https://api.dailymotion.com"
    override var name = "Dailymotion"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true

    data class VideoItem(
        val id: String,
        val title: String,
        val thumbnail_360_url: String?
    )

    data class VideoList(
        val list: List<VideoItem>
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val data = app.get(
            "$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&page=$page&country=mx&language=es"
        ).parsedSafe<VideoList>()?.list ?: emptyList()

        val items = data.map { it.toSearchResponse() }

        return HomePageResponse(
            listOf(
                HomePageList(
                    name = "Dailymotion México",
                    list = items,
                    isHorizontalImages = true
                )
            ),
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

        val data = app.get(
            "$mainUrl/videos?fields=id,title,thumbnail_360_url&limit=20&search=$q&country=mx&language=es"
        ).parsedSafe<VideoList>()?.list ?: emptyList()

        return data.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/")

        val data = app.get(
            "$mainUrl/video/$id?fields=id,title,description,thumbnail_720_url,embed_url&country=mx&language=es"
        ).parsedSafe<Map<String, Any>>() ?: return null

        val title = data["title"] as? String ?: "Dailymotion"
        val desc = data["description"] as? String
        val poster = data["thumbnail_720_url"] as? String
        val embed = data["embed_url"] as? String ?: ""

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Others,
            dataUrl = embed
        ) {
            posterUrl = poster
            plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(
            "https://www.dailymotion.com/embed/video/$data",
            subtitleCallback,
            callback
        )
        return true
    }

    private fun VideoItem.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            name = title,
            url = "https://www.dailymotion.com/video/$id",
            type = TvType.Others
        ) {
            posterUrl = thumbnail_360_url
        }
    }
}

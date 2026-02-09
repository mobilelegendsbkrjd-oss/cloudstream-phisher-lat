package com.newpipe

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.HttpDownloader
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

class newpipeProvider : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "NewPipe"
    override var lang = "es-mx"
    override val hasMainPage = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    init {
        // Downloader que sí existe en tu extractor
        NewPipe.init(HttpDownloader())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val service = ServiceList.YouTube

        // Firma real de v0.23.1:
        // fromQuery(query, contentFilters, sortFilter, timeFilter)
        val qh = service.searchQHFactory.fromQuery(
            query,
            listOf("video"),
            "",
            ""
        )

        val searchInfo = SearchInfo.getInfo(service, qh)

        return searchInfo.getRelatedItems().mapNotNull {
            val info = it as? StreamInfo ?: return@mapNotNull null
            newMovieSearchResponse(info.name, info.url, TvType.Movie) {
                posterUrl = info.thumbnails.firstOrNull()?.url
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)

        return newMovieLoadResponse(info.name, url, TvType.Movie, info.id) {
            plot = info.description?.content ?: ""
            posterUrl = info.thumbnails.firstOrNull()?.url
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = StreamInfo.getInfo(ServiceList.YouTube, data)

        info.videoStreams.forEach {
            val videoUrl = it.url ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = it.quality,
                    url = videoUrl
                ) {
                    quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}

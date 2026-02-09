package com.youtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

class youtubeprovider : MainAPI() {

    // Instancias QUE SÍ FUNCIONAN
    private val mirrors = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://vid.puffyan.us"
    )

    override var mainUrl = mirrors.first()
    override var name = "YouTube"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "es"
    override val hasMainPage = true

    // Clases de datos según API de Invidious
    data class SearchEntry(
        val title: String,
        val videoId: String,
        val videoThumbnails: List<Thumbnail>? = null,
        val author: String? = null
    )

    data class VideoEntry(
        val title: String,
        val description: String,
        val videoId: String,
        val recommendedVideos: List<SearchEntry>? = null,
        val author: String? = null,
        val videoThumbnails: List<Thumbnail>? = null,
        val lengthSeconds: Int? = null,
        val viewCount: Long? = null
    )

    data class Thumbnail(
        val url: String,
        val quality: String? = null
    )

    // Headers para evitar bloqueos
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*"
    )

    private suspend fun safeGet(url: String): String? {
        for (mirror in mirrors) {
            try {
                val fixedUrl = if (url.startsWith("http")) {
                    url.replace(mainUrl, mirror)
                } else {
                    "$mirror$url"
                }

                val response = app.get(fixedUrl, headers = headers).text

                // Verificar que sea JSON válido
                if (response.isNotBlank() && (response.startsWith("[") || response.startsWith("{"))) {
                    mainUrl = mirror
                    return response
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val popularJson = safeGet("/api/v1/popular") ?: return newHomePageResponse(emptyList())
        val trendingJson = safeGet("/api/v1/trending?region=MX") ?: return newHomePageResponse(emptyList())

        val popular = tryParseJson<List<SearchEntry>>(popularJson) ?: emptyList()
        val trending = tryParseJson<List<SearchEntry>>(trendingJson) ?: emptyList()

        return newHomePageResponse(
            listOf(
                HomePageList("🎬 Populares", popular.map { it.toSearchResponse(this) }, true),
                HomePageList("🔥 Trending MX", trending.map { it.toSearchResponse(this) }, true)
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val json = safeGet("/api/v1/search?q=$encodedQuery&type=video") ?: return emptyList()
        val results = tryParseJson<List<SearchEntry>>(json) ?: return emptyList()

        return results.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = extractVideoId(url) ?: return null

        val json = safeGet("/api/v1/videos/$videoId") ?: return null
        val video = tryParseJson<VideoEntry>(json) ?: return null

        return video.toLoadResponse(this)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor("https://youtube.com/watch?v=$data", subtitleCallback, callback)
        return true
    }

    // Funciones de extensión
    private fun SearchEntry.toSearchResponse(provider: youtubeprovider): SearchResponse {
        val thumbnailUrl = this.videoThumbnails?.firstOrNull()?.url
            ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

        return provider.newMovieSearchResponse(
            this.title,
            "https://www.youtube.com/watch?v=$videoId",
            TvType.Movie
        ) {
            this.posterUrl = thumbnailUrl
        }
    }

    // CORRECCIÓN: Agregar 'suspend' a esta función
    private suspend fun VideoEntry.toLoadResponse(provider: youtubeprovider): LoadResponse {
        val thumbnailUrl = this.videoThumbnails?.firstOrNull { it.quality == "maxresdefault" }?.url
            ?: this.videoThumbnails?.firstOrNull()?.url
            ?: "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

        val description = this.description.ifEmpty { "Video de YouTube" }
        val author = this.author ?: "Desconocido"
        val duration = this.lengthSeconds ?: 0
        val viewCount = this.viewCount ?: 0

        return provider.newMovieLoadResponse(
            this.title,
            "https://www.youtube.com/watch?v=$videoId",
            TvType.Movie,
            this.videoId
        ) {
            this.plot = "📺 Canal: $author\n👁️ Vistas: ${formatNumber(viewCount)}\n⏱️ Duración: ${formatDuration(duration)}\n\n$description"
            this.posterUrl = thumbnailUrl
            this.duration = duration

            // Recomendaciones
            this.recommendations = this@toLoadResponse.recommendedVideos?.map {
                it.toSearchResponse(provider)
            } ?: emptyList()
        }
    }

    // Función auxiliar para extraer ID del video
    private fun extractVideoId(url: String): String? {
        return when {
            url.contains("youtube.com/watch?v=") -> {
                Regex("v=([a-zA-Z0-9_-]+)").find(url)?.groups?.get(1)?.value
            }
            url.contains("youtu.be/") -> {
                Regex("youtu.be/([a-zA-Z0-9_-]+)").find(url)?.groups?.get(1)?.value
            }
            else -> {
                url.substringAfterLast("v=").substringBefore("&").takeIf {
                    it.isNotBlank() && it.length == 11
                }
            }
        }
    }

    private fun formatNumber(number: Long): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
            number >= 1_000 -> String.format("%.1fK", number / 1_000.0)
            else -> number.toString()
        }
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, secs)
            else -> String.format("%02d:%02d", minutes, secs)
        }
    }
}
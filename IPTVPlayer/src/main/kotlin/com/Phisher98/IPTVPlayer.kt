package com.phisher98

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.io.InputStream
import java.util.UUID

class IPTVPlayer : MainAPI() {
    override var lang = "es-la"
    override var mainUrl = "multi"
    override var name = "IPTV Español Latino"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val listasPorPais = mapOf(
        "Dibujos Animados" to "https://mametchikitty.github.io/Listas-IPTV/dibujos-animados.m3u",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val secciones = mutableListOf<HomePageList>()

        for ((pais, urlLista) in listasPorPais) {
            try {
                val data = IptvPlaylistParser().parseM3U(app.get(urlLista).text)

                val shows = data.items.map { channel ->
                    val streamurl = channel.url.toString()
                    val channelname = channel.title.toString()
                    val posterurl = channel.attributes["tvg-logo"].toString()
                    val nation = pais
                    val key = channel.attributes["key"].toString()
                    val keyid = channel.attributes["keyid"].toString()

                    newLiveSearchResponse(
                        channelname,
                        LoadData(streamurl, channelname, posterurl, nation, key, keyid).toJson(),
                        TvType.Live
                    ) {
                        this.posterUrl = posterurl
                        this.lang = "es-la"
                    }
                }

                secciones.add(
                    HomePageList(
                        pais,
                        shows,
                        isHorizontalImages = true
                    )
                )
            } catch (_: Exception) {}
        }

        return newHomePageResponse(secciones)
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(data.title, data.url, url) {
            this.posterUrl = data.poster
            this.plot = data.nation
        }
    }

    data class LoadData(
        val url: String,
        val title: String,
        val poster: String,
        val nation: String,
        val key: String,
        val keyid: String,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        if (loadData.url.contains("mpd")) {
            callback.invoke(
                newDrmExtractorLink(
                    this.name,
                    this.name,
                    loadData.url,
                    INFER_TYPE,
                    UUID.randomUUID()
                ) {
                    this.quality = Qualities.Unknown.value
                    this.key = loadData.key.trim()
                    this.kid = loadData.keyid.trim()
                }
            )
        } else if (loadData.url.contains("&e=.m3u")) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    url = loadData.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    loadData.title,
                    url = loadData.url,
                    INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}

data class Playlist(
    val items: List<PlaylistItem> = emptyList(),
)

data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null,
    val key: String? = null,
    val keyid: String? = null,
)

class IptvPlaylistParser {

    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    @Throws(PlaylistParserException::class)
    fun parseM3U(input: InputStream): Playlist {
        val reader = input.bufferedReader()
        if (!reader.readLine().startsWith("#EXTM3U")) {
            throw PlaylistParserException.InvalidHeader()
        }

        val playlistItems = mutableListOf<PlaylistItem>()
        var currentTitle: String? = null
        var currentAttributes: Map<String, String> = emptyMap()

        reader.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEachLine
            when {
                t.startsWith("#EXTINF") -> {
                    currentTitle = t.split(",").lastOrNull()?.trim()
                    currentAttributes = t.substringAfter("#EXTINF:").substringBefore(",")
                        .split(" ")
                        .mapNotNull {
                            val p = it.split("=")
                            if (p.size == 2) p[0] to p[1].replace("\"", "") else null
                        }.toMap()
                }
                !t.startsWith("#") -> {
                    playlistItems.add(
                        PlaylistItem(
                            title = currentTitle,
                            attributes = currentAttributes,
                            url = t
                        )
                    )
                    currentTitle = null
                    currentAttributes = emptyMap()
                }
            }
        }
        return Playlist(playlistItems)
    }
}

sealed class PlaylistParserException(message: String) : Exception(message) {
    class InvalidHeader :
        PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")
}

package com.phisher98

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
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

    // RESTAURADO: Tu lista completa de canales
    private val baseUrl = "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/builds/iptv/"
    private val listasPorPais = mapOf(
        "24/7" to "${baseUrl}247.m3u",
        "Cine" to "${baseUrl}Cine.m3u",
        "Eventos" to "${baseUrl}Eventos.m3u",
        "Mexico HD" to "${baseUrl}android(hd).m3u",
        "Mexico SD" to "${baseUrl}android(sd).m3u",
        "Deportes" to "${baseUrl}deportes.m3u",
        "Documentales" to "${baseUrl}documentales.m3u",
        "Entretenimiento" to "${baseUrl}entretenimiento.m3u",
        "Infantil" to "${baseUrl}infantil.m3u",
        "Música" to "${baseUrl}musica.m3u",
        "Noticias" to "${baseUrl}noticias.m3u",
        "Novelas" to "${baseUrl}novelas.m3u",
        "Dibujos Animados" to "https://mametchikitty.github.io/Listas-IPTV/dibujos-animados.m3u"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val secciones = mutableListOf<HomePageList>()

        for ((pais, urlLista) in listasPorPais) {
            try {
                val data = IptvPlaylistParser().parseM3U(app.get(urlLista).text)
                val shows = data.items.map { channel ->
                    val streamurl = channel.url.toString()
                    val channelname = channel.title.toString()
                    val posterurl = channel.attributes["tvg-logo"] ?: ""
                    val key = channel.attributes["key"] ?: ""
                    val keyid = channel.attributes["keyid"] ?: ""

                    newLiveSearchResponse(
                        channelname,
                        LoadData(streamurl, channelname, posterurl, pais, key, keyid).toJson(),
                        TvType.Live
                    ) {
                        this.posterUrl = posterurl
                    }
                }
                if (shows.isNotEmpty()) {
                    secciones.add(HomePageList(pais, shows, isHorizontalImages = true))
                }
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
        val url: String, val title: String, val poster: String, 
        val nation: String, val key: String, val keyid: String
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
                    this.name, this.name, loadData.url, null, UUID.randomUUID()
                ) {
                    this.quality = Qualities.Unknown.value
                    this.key = loadData.key.trim()
                    this.kid = loadData.keyid.trim()
                }
            )
        } else {
            // Ajustado para que compile: el cuarto parámetro es el 'referer' (String)
            callback.invoke(
                newExtractorLink(
                    this.name, loadData.title, loadData.url, "" 
                ) {
                    this.quality = Qualities.Unknown.value
                    // Forzamos isM3u8 para que no salte el canal
                    this.isM3u8 = true 
                }
            )
        }
        return true
    }
}

// Clases del Parser (Mantenidas como en tu original)
data class Playlist(val items: List<PlaylistItem> = emptyList())
data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val url: String? = null
)

class IptvPlaylistParser {
    fun parseM3U(content: String): Playlist = parseM3U(content.byteInputStream())
    fun parseM3U(input: InputStream): Playlist {
        val reader = input.bufferedReader()
        if (!reader.readLine().startsWith("#EXTM3U")) throw Exception("Invalid Header")
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
                    playlistItems.add(PlaylistItem(currentTitle, currentAttributes, t))
                    currentTitle = null
                    currentAttributes = emptyMap()
                }
            }
        }
        return Playlist(playlistItems)
    }
}

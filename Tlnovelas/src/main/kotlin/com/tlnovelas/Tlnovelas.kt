package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.Vtbe  // Asumiendo que Vtbe está disponible; si no, impleméntala o usa base
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Clase base para extractores que devuelven Video (adaptada de tus códigos)
data class Video(
    val source: String,
    val subtitles: List<Subtitle>? = null,
    val headers: Map<String, String> = emptyMap()
)

data class Subtitle(
    val label: String,
    val file: String
)

// UniversalExtractor que soporta todos los embeds
open class UniversalEmbedExtractor : ExtractorApi() {
    override val name = "Universal Tlnovelas Embed"
    override val mainUrl = "https://ww2.tlnovelas.net"  // No usado realmente
    override val requiresReferer = true

    private val gson = Gson()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("bysejikuar.com") || url.contains("bf0skv.org") || url.contains("filemoon.site") -> {
                extractBysejikuar(url, referer, subtitleCallback, callback)
            }
            url.contains("dooodster.com") -> {
                DooodLaExtractor().getUrl(url, referer, subtitleCallback, callback)
            }
            url.contains("iplayerhls.com") -> {
                Vtbe().getUrl(url, referer, subtitleCallback, callback)  // Asumiendo Vtbe es ExtractorApi
            }
            url.contains("luluvdo.com") || url.contains("luluvdoo.com") || url.contains("luluvid.com") -> {
                extractLuluvdo(url, referer, subtitleCallback, callback)
            }
            else -> {
                // Fallback genérico si no coincide ninguno
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        }
    }

    // Lógica adaptada de FilemoonExtractor para bysejikuar
    private suspend fun extractBysejikuar(
        link: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(link)
            ?: return  // No se puede extraer ID

        val linkType = matcher.groupValues[1]
        val videoId = matcher.groupValues[2]
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1) ?: return

        val detailsUrl = "$currentDomain/api/videos/$videoId/embed/details"
        val detailsHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "X-Embed-Origin" to "ww2.tlnovelas.net",
            "X-Embed-Referer" to referer ?: "https://ww2.tlnovelas.net/"
        )
        val detailsResponse = app.get(detailsUrl, headers = detailsHeaders).text
        val details = parseJson<DetailsResponse>(detailsResponse)

        val embedFrameUrl = details.embed_frame_url ?: return

        val headers = mutableMapOf<String, String>(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json"
        )

        val playbackDomain: String = if (linkType == "d") {
            headers["Referer"] = link
            currentDomain
        } else {
            val domain = Regex("""(https?://[^/]+)""").find(embedFrameUrl)?.groupValues?.get(1) ?: return
            headers["Referer"] = embedFrameUrl
            headers["X-Embed-Parent"] = link
            headers["X-Embed-Origin"] = "ww2.tlnovelas.net"
            headers["X-Embed-Referer"] = referer ?: "https://ww2.tlnovelas.net/"
            domain
        }

        val playbackUrl = "$playbackDomain/api/videos/$videoId/embed/playback"
        val playbackResponse = app.get(playbackUrl, headers = headers).text
        val playback = parseJson<PlaybackResponse>(playbackResponse).playback ?: return

        val decryptedJson = decryptPlayback(playback)
        val decrypted = gson.fromJson(decryptedJson, DecryptedPlayback::class.java) ?: return

        val sources = decrypted.sources ?: return
        if (sources.isEmpty()) return

        val sourceUrl = sources[0].url ?: return
        callback(
            ExtractorLink(
                this.name,
                "Bysejikuar",
                sourceUrl,
                "$playbackDomain/",
                Qualities.Unknown.value,
                sourceUrl.contains(".m3u8")
            )
        )

        // Si hay tracks/subs (adaptar si existen en decrypted)
        decrypted.tracks?.forEach { track ->
            // Asumir tracks son subs; ajustar según modelo
            subtitleCallback(SubtitleFile("Unknown", track.toString()))
        }
    }

    private fun padBase64(s: String): String {
        val m = s.length % 4
        return if (m == 0) s else s + "=".repeat(4 - m)
    }

    private fun decryptPlayback(data: PlaybackData): String {
        val decoder = Base64.getUrlDecoder()
        val iv = decoder.decode(padBase64(data.iv))
        val payload = decoder.decode(padBase64(data.payload))
        val p1 = decoder.decode(padBase64(data.key_parts[0]))
        val p2 = decoder.decode(padBase64(data.key_parts[1]))
        val key = ByteArray(p1.size + p2.size)
        System.arraycopy(p1, 0, key, 0, p1.size)
        System.arraycopy(p2, 0, key, p1.size, p2.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decryptedBytes = cipher.doFinal(payload)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    // Lógica adaptada de LuluVdoExtractor
    private suspend fun extractLuluvdo(
        link: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(link, referer = referer).document
        val scriptData = doc.selectFirst("script:containsData(sources:)")?.data() ?: return

        val source = Regex("sources: \\[\\{file:\"(.*?)\"\\}]").find(scriptData)?.groupValues?.get(1) ?: return
        callback(
            ExtractorLink(
                this.name,
                "LuluVdo",
                source,
                referer ?: "",
                Qualities.Unknown.value,
                source.contains(".m3u8")
            )
        )

        val tracksData = Regex("tracks: \\[(.*?)]").find(scriptData)?.groupValues?.get(1) ?: ""
        Regex("file: \"(.*?)\", label: \"(.*?)\"").findAll(tracksData).forEach {
            val subFile = it.groupValues[1]
            val subLabel = it.groupValues[2]
            if (subLabel != "Upload captions") {
                subtitleCallback(SubtitleFile(subLabel, subFile))
            }
        }
    }

    // Modelos para Bysejikuar
    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>
    )
    data class DecryptedPlayback(
        val sources: List<DecryptedSource>?,
        val tracks: List<JsonElement>? = null,
        @SerializedName("poster_url") val posterUrl: String? = null,
        @SerializedName("generated_at") val generatedAt: String? = null,
        @SerializedName("expires_at") val expiresAt: String? = null
    )
    data class DecryptedSource(
        val quality: String? = null,
        val label: String? = null,
        @SerializedName("mime_type") val mimeType: String? = null,
        val url: String? = null,
        @SerializedName("bitrate_kbps") val bitrateKbps: Int? = null,
        val height: Int? = null,
        @SerializedName("size_bytes") val sizeBytes: Long? = null
    )
}

// Dooodster como extensión (de tu código)
class Dooodster : DoodLaExtractor() {
    override var mainUrl = "https://dooodster.com"
}

// Iplayerhls como extensión (asumiendo Vtbe existe; si no, implementa Vtbe base si es necesario)
class Iplayerhls : Vtbe() {
    override var name = "Iplayerhls"
    override var mainUrl = "https://iplayerhls.com"
}

// Clase principal Tlnovelas (tu código original actualizado)
class Tlnovelas : MainAPI() {
    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)
    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select(".vk-poster, .ani-card, .p-content, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home, true)
    }
    private fun Element.toSearchResult(): SearchResponse {
        val title = selectFirst(".ani-txt, .p-title, .vk-info p")?.text()
                ?: selectFirst("a")?.attr("title") ?: ""
        var href = selectFirst("a")?.attr("href") ?: ""
        val poster = selectFirst("img")?.attr("src")
        if (href.contains("/ver/")) {
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+|-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }
        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            posterUrl = poster
        }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar/?q=$query"
        return app.get(url).document.select(".vk-poster, .ani-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")
        val finalDoc = if (url.contains("/ver/") && novelaLink != null) app.get(novelaLink).document else document
        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")?.text()
            ?.replace(Regex("(?i)Capitulos de|Ver"), "")?.trim() ?: "Telenovela"
        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: finalDoc.selectFirst(".ani-img img")?.attr("src")
        val episodes = finalDoc.select("a[href*='/ver/']").map {
            val epUrl = it.attr("href")
            val epName = it.text().replace(title, "", true)
                .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "").trim()
            newEpisode(epUrl) { name = if (epName.isEmpty()) "Capítulo" else "Capítulo $epName" }
        }.distinctBy { it.data }.reversed()
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = finalDoc.selectFirst(".card-text, .ani-description")?.text()
        }
    }
    private fun decodeVideoUrl(encoded: String): String {
        return try {
            val parts = encoded.split("|")
            if (parts.size == 2) {
                val encodedStr = parts[0]
                val key = parts[1].toInt()
                val decodedChars = mutableListOf<Char>()
                for (i in encodedStr.indices) {
                    val charCode = encodedStr[i].code - key - i
                    decodedChars.add(charCode.toChar())
                }
                val decodedString = decodedChars.joinToString("")
                URLDecoder.decode(decodedString, "UTF-8")
            } else {
                encoded
            }
        } catch (e: Exception) {
            encoded
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        val videoLinks = mutableSetOf<String>()

        // 1. Método original: extraer directo del HTML (prioridad)
        // BUSCAR Y DECODIFICAR FORMATO OFUSCADO JS (e[0] = '...')
        val regexJsArray = Regex("""e\[\d+\]\s*=\s*['"]([^'"]+)['"]""")
        regexJsArray.findAll(response).forEach { match ->
            val encodedUrl = match.groupValues[1]
            val decodedUrl = decodeVideoUrl(encodedUrl)
            if (decodedUrl.startsWith("http")) {
                videoLinks.add(decodedUrl)
            }
        }
        // BUSCAR EN LA FUNCIÓN v_ideo()
        val regexVideFunc = Regex("""v_ideo\(([^)]+)\)""")
        regexVideFunc.findAll(response).forEach { match ->
            val param = match.groupValues[1]
            val arrayIndex = Regex("""e\[(\d+)\]""").find(param)?.groupValues?.get(1)?.toIntOrNull()
            if (arrayIndex != null) {
                val arrayRegex = Regex("""e\[$arrayIndex\]\s*=\s*['"]([^'"]+)['"]""")
                arrayRegex.find(response)?.let { arrayMatch ->
                    val encodedUrl = arrayMatch.groupValues[1]
                    val decodedUrl = decodeVideoUrl(encodedUrl)
                    if (decodedUrl.startsWith("http")) {
                        videoLinks.add(decodedUrl)
                    }
                }
            }
        }
        // FORMATO VIEJO (var e = ['...', '...'])
        Regex("""var\s+e\s*=\s*\[([^\]]+)\]""").findAll(response).forEach { match ->
            match.groupValues[1].split(",")
                .map { it.trim().trim('\'', '"') }
                .filter { it.isNotEmpty() }
                .forEach { encodedUrl ->
                    val decodedUrl = decodeVideoUrl(encodedUrl)
                    if (decodedUrl.startsWith("http")) {
                        videoLinks.add(decodedUrl)
                    }
                }
        }
        // BUSCAR DIRECTAMENTE PATRONES DECODIFICADOS
        val decodedPatterns = listOf(
            Regex("""https?://[^"'\s<>]+\.(mp4|m3u8|mkv|avi|mov|flv|wmv|webm)[^"'\s<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/video/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/embed/[^"'\s<>]+""", RegexOption.IGNORE_CASE)
        )
        decodedPatterns.forEach { pattern ->
            pattern.findAll(response).forEach { match ->
                val url = match.value
                if (!url.contains("google") && !url.contains("adskeeper") && !url.contains("googletagmanager")) {
                    videoLinks.add(url)
                }
            }
        }
        // IFRAMES como respaldo
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val link = it.groupValues[1]
            if (!link.contains("google") && !link.contains("adskeeper")) {
                videoLinks.add(link)
            }
        }

        var success = false
        videoLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    success = true
                }
            } catch (e: Exception) {
                // Ignorar
            }
        }

        // 2. Fallback: si el método original no encuentra nada, usar embeds con UniversalExtractor
        if (!success) {
            val embeds = mutableListOf<String>()
            Regex("""e\[\d+\]\s*=\s*['"](https?://[^'"]+)['"]""").findAll(response).forEach {
                embeds.add(it.groupValues[1])
            }
            if (embeds.isEmpty()) {
                Regex("""['"](https?://[^'"]+/(?:e|d)/[a-zA-Z0-9]+)['"]""").findAll(response).forEach {
                    embeds.add(it.groupValues[1])
                }
            }

            val universal = UniversalEmbedExtractor()
            embeds.distinct().forEach { embedUrl ->
                try {
                    universal.getUrl(embedUrl, data, subtitleCallback, callback)
                    success = true
                } catch (e: Exception) {
                    // Ignorar y continuar
                }
            }
        }

        return success
    }
}

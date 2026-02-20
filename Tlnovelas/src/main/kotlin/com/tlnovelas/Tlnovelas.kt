package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.*
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

// -----------------------------------------------------
// Universal Embed Extractor (soporta bysejikuar, luluvdo, etc.)
// -----------------------------------------------------
open class UniversalEmbedExtractor : ExtractorApi() {
    override val name = "Universal Tlnovelas Embed"
    override val mainUrl = "https://ww2.tlnovelas.net"
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
            url.contains("dooodster.com") || url.contains("dood") -> {
                loadExtractor(url, referer ?: "", subtitleCallback, callback)
            }
            url.contains("iplayerhls.com") -> {
                loadExtractor(url, referer ?: "", subtitleCallback, callback)
            }
            url.contains("luluvdo.com") || url.contains("luluvdoo.com") || url.contains("luluvid.com") -> {
                extractLuluvdo(url, referer, subtitleCallback, callback)
            }
            else -> {
                loadExtractor(url, referer ?: "", subtitleCallback, callback)
            }
        }
    }

    private suspend fun extractBysejikuar(
        link: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(link) ?: return
        val linkType = matcher.groupValues[1]
        val videoId = matcher.groupValues[2]
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1) ?: return

        val detailsUrl = "$currentDomain/api/videos/$videoId/embed/details"
        val detailsHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "X-Embed-Origin" to "ww2.tlnovelas.net",
            "X-Embed-Referer" to (referer ?: "https://ww2.tlnovelas.net/")
        )
        val detailsResponse = app.get(detailsUrl, headers = detailsHeaders).text
        val details = gson.fromJson(detailsResponse, DetailsResponse::class.java)

        val embedFrameUrl = details.embed_frame_url ?: return

        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json"
        )

        val playbackDomain = if (linkType == "d") {
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
        val playback = gson.fromJson(playbackResponse, PlaybackResponse::class.java).playback ?: return

        val decryptedJson = decryptPlayback(playback)
        val decrypted = gson.fromJson(decryptedJson, DecryptedPlayback::class.java) ?: return

        val sources = decrypted.sources ?: return
        if (sources.isEmpty()) return

        val sourceUrl = sources[0].url ?: return

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Bysejikuar HLS",
                url = sourceUrl,
                referer = "$playbackDomain/",
                quality = Qualities.Unknown.value,
                isM3u8 = sourceUrl.endsWith(".m3u8") || sourceUrl.contains(".m3u8?")
            )
        )

        decrypted.tracks?.forEach { trackElem ->
            val track = trackElem.asJsonObject
            val subUrl = track.get("file")?.asString ?: return@forEach
            val subLabel = track.get("label")?.asString ?: "Unknown"
            subtitleCallback(SubtitleFile(subUrl, subLabel))
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
        if (data.key_parts.size < 2) return ""
        val p1 = decoder.decode(padBase64(data.key_parts[0]))
        val p2 = decoder.decode(padBase64(data.key_parts[1]))
        val key = p1 + p2
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decryptedBytes = cipher.doFinal(payload)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private suspend fun extractLuluvdo(
        link: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(link, referer = referer ?: "").document
        val scriptData = doc.selectFirst("script:containsData(sources:)")?.data() ?: return

        val sourceMatch = Regex("sources: \\[\\{file:\"(.*?)\"\\}]").find(scriptData)
        val source = sourceMatch?.groupValues?.get(1) ?: return

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "LuluVdo",
                url = source,
                referer = referer ?: link,
                quality = Qualities.Unknown.value,
                isM3u8 = source.endsWith(".m3u8") || source.contains(".m3u8?")
            )
        )

        val tracksData = Regex("tracks: \\[(.*?)]").find(scriptData)?.groupValues?.get(1) ?: ""
        Regex("file: \"(.*?)\", label: \"(.*?)\"").findAll(tracksData).forEach {
            val subFile = it.groupValues[1]
            val subLabel = it.groupValues[2]
            if (subLabel != "Upload captions") {
                subtitleCallback(SubtitleFile(subFile, subLabel))
            }
        }
    }

    // Modelos para bysejikuar
    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>
    )
    data class DecryptedPlayback(
        val sources: List<DecryptedSource>?,
        val tracks: List<JsonElement>? = null
    )
    data class DecryptedSource(
        val url: String?
    )
}

// -----------------------------------------------------
// CLASE PRINCIPAL
// -----------------------------------------------------
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select(".vk-poster, .ani-card, .p-content, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return HomePageResponse(home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".ani-txt, .p-title, .vk-info p")?.text()
            ?: selectFirst("a")?.attr("title") ?: return null
        var href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")

        if (href.contains("/ver/")) {
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+|-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = poster
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

            newEpisode(epUrl) {
                name = if (epName.isEmpty()) "Capítulo" else "Capítulo $epName"
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = finalDoc.selectFirst(".card-text, .ani-description")?.text()
        }
    }

    private fun decodeVideoUrl(encoded: String): String {
        return try {
            val parts = encoded.split("|")
            if (parts.size == 2) {
                val encodedStr = parts[0]
                val key = parts[1].toIntOrNull() ?: 0
                val decodedChars = StringBuilder()
                for (i in encodedStr.indices) {
                    val charCode = encodedStr[i].code - key - i
                    decodedChars.append(charCode.toChar())
                }
                URLDecoder.decode(decodedChars.toString(), "UTF-8")
            } else encoded
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

        // Método original (prioridad alta)
        Regex("""e\[\d+\]\s*=\s*['"]([^'"]+)['"]""").findAll(response).forEach { match ->
            val encodedUrl = match.groupValues[1]
            val decodedUrl = decodeVideoUrl(encodedUrl)
            if (decodedUrl.startsWith("http")) videoLinks.add(decodedUrl)
        }

        Regex("""v_ideo\(([^)]+)\)""").findAll(response).forEach { match ->
            val param = match.groupValues[1]
            val arrayIndex = Regex("""e\[(\d+)\]""").find(param)?.groupValues?.get(1)?.toIntOrNull()
            if (arrayIndex != null) {
                Regex("""e\[$arrayIndex\]\s*=\s*['"]([^'"]+)['"]""").find(response)?.let { arrayMatch ->
                    val encodedUrl = arrayMatch.groupValues[1]
                    val decodedUrl = decodeVideoUrl(encodedUrl)
                    if (decodedUrl.startsWith("http")) videoLinks.add(decodedUrl)
                }
            }
        }

        Regex("""var\s+e\s*=\s*\[([^\]]+)\]""").findAll(response).forEach { match ->
            match.groupValues[1].split(",").map { it.trim().trim('\'', '"') }
                .filter { it.isNotEmpty() }
                .forEach { encodedUrl ->
                    val decodedUrl = decodeVideoUrl(encodedUrl)
                    if (decodedUrl.startsWith("http")) videoLinks.add(decodedUrl)
                }
        }

        listOf(
            Regex("""https?://[^"'\s<>]+\.(mp4|m3u8|mkv|avi|mov|flv|wmv|webm)[^"'\s<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/video/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/embed/[^"'\s<>]+""", RegexOption.IGNORE_CASE)
        ).forEach { pattern ->
            pattern.findAll(response).forEach { match ->
                val url = match.value
                if (!url.contains("google") && !url.contains("adskeeper") && !url.contains("googletagmanager")) {
                    videoLinks.add(url)
                }
            }
        }

        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val link = it.groupValues[1]
            if (!link.contains("google") && !link.contains("adskeeper")) videoLinks.add(link)
        }

        var success = false
        videoLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) success = true
            } catch (_: Exception) {}
        }

        // Fallback: embeds con universal
        if (!success || videoLinks.isEmpty()) {
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
                } catch (_: Exception) {}
            }
        }

        return success
    }
}

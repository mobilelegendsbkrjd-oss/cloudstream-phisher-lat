package com.sololatino

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object Embed69Extractor {

    private const val DECRYPT_URL = "https://embed69.org/api/decrypt"

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0",
        "Referer" to "https://embed69.org/",
        "Origin" to "https://embed69.org"
    )

    private val hgDomains = listOf("hglink.to", "hgcloud.to")
    private val hgRedirects = listOf(
        "vibuxer.com",
        "audinifer.com",
        "masukestin.com",
        "hanerix.com"
    )

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val html = app.get(url, headers = HEADERS).text

        // Subs
        Regex("""<track[^>]*src="([^"]*\.vtt)"[^>]*label="([^"]*)"""")
            .findAll(html)
            .forEach {
                subtitleCallback(SubtitleFile(it.groupValues[2], it.groupValues[1]))
            }

        // Xupalace detect
        Regex("""go_to_playerVast\('([^']+)""")
            .findAll(html)
            .forEach {
                extractXupalace(it.groupValues[1], url, callback)
            }

        // API decrypt
        val dataLink = Regex("""dataLink\s*=\s*(\[.*?\]);""")
            .find(html)?.groupValues?.getOrNull(1) ?: return

        val parsed = AppUtils.tryParseJson<List<ServersByLang>>(dataLink) ?: return

        parsed.forEach { lang ->

            val linksJson = lang.sortedEmbeds
                .mapNotNull { it.link }
                .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

            val body = """{"links":$linksJson}"""
                .toRequestBody("application/json".toMediaTypeOrNull())

            val decrypted = app.post(
                DECRYPT_URL,
                requestBody = body,
                headers = HEADERS
            ).parsedSafe<Loadlinks>() ?: return@forEach

            decrypted.links.forEach {
                handleServer(it.link, url, callback)
            }
        }
    }

    // =========================
    // ROUTER
    // =========================
    private suspend fun handleServer(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {

        val fixed = fixHostsLinks(url)

        when {

            fixed.contains("xupalace") -> {
                extractXupalace(fixed, referer, callback)
            }

            hgDomains.any { fixed.contains(it) } ||
                    hgRedirects.any { fixed.contains(it) } -> {
                extractHGLink(fixed, referer, callback)
            }

            fixed.contains("vidhide") -> {
                extractVidHide(fixed, referer, callback)
            }

            else -> {
                loadExtractor(fixed, referer, { }, callback)
            }
        }
    }

    // =========================
    // XUPALACE
    // =========================
    private suspend fun extractXupalace(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer).text

            val next = Regex("""https?://[^\s"'<>]+""")
                .findAll(html)
                .map { it.value }
                .firstOrNull {
                    hgDomains.any { d -> it.contains(d) } ||
                            hgRedirects.any { d -> it.contains(d) }
                } ?: return

            extractHGLink(next, url, callback)

        } catch (_: Exception) {}
    }

    // =========================
    // HG FLOW (REDIRECT FIX)
    // =========================
    private suspend fun extractHGLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer)

            val finalUrl = res.url?.toString() ?: url

            // si ya redirigió
            if (hgRedirects.any { finalUrl.contains(it) }) {
                extractHGPlayer(finalUrl, callback)
                return
            }

            val html = res.text

            val next = Regex("""https?://[^\s"'<>]+""")
                .findAll(html)
                .map { it.value }
                .firstOrNull {
                    hgRedirects.any { d -> it.contains(d) }
                } ?: return

            extractHGPlayer(next, callback)

        } catch (_: Exception) {}
    }

    // =========================
    // PLAYER (VIBUXER / AUDINIFER)
    // =========================
    private suspend fun extractHGPlayer(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url).text

            val packed = Regex(
                """eval\(function\(p,a,c,k,e,d.*?\)\)""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)?.value ?: return

            val unpacked = JsUnpacker(packed).unpack() ?: return

            val obj = Regex("""var\s+o\s*=\s*\{(.*?)\}""",
                RegexOption.DOT_MATCHES_ALL)
                .find(unpacked)
                ?.groupValues?.getOrNull(1) ?: return

            val map = Regex(""""(.*?)"\s*:\s*"(.*?)"""")
                .findAll(obj)
                .associate {
                    it.groupValues[1] to it.groupValues[2]
                }

            var video = map["1c"] ?: map["1m"] ?: map["1f"] ?: return

            val base = url.substringBefore("/e/")
            if (video.startsWith("/")) {
                video = base + video
            }

            callback.invoke(
                newExtractorLink(
                    "HGStream",
                    "HGStream",
                    video
                ) {
                    this.type = ExtractorLinkType.M3U8
                    this.referer = url
                }
            )

        } catch (_: Exception) {}
    }

    // =========================
    // VIDHIDE
    // =========================
    private suspend fun extractVidHide(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer).text

            val packed = Regex(
                """eval\(function\(p,a,c,k,e,d.*?\)\)""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)?.value ?: return

            val unpacked = JsUnpacker(packed).unpack() ?: return

            Regex("""https?://[^\s"']+\.m3u8""")
                .find(unpacked)
                ?.value?.let {
                    callback.invoke(
                        newExtractorLink("VidHide", "HLS", it) {
                            this.type = ExtractorLinkType.M3U8
                            this.referer = url
                        }
                    )
                }

        } catch (_: Exception) {}
    }

    private fun fixHostsLinks(url: String): String {
        return url
            .replace("filemoon.link", "filemoon.sx")
            .replace("do7go.com", "dood.la")
    }
}

// =========================
// DATA
// =========================

data class Server(
    @JsonProperty("servername") val servername: String? = null,
    @JsonProperty("link") val link: String? = null,
)

data class ServersByLang(
    @JsonProperty("file_id") val fileId: String? = null,
    @JsonProperty("video_language") val videoLanguage: String? = null,
    @JsonProperty("sortedEmbeds") val sortedEmbeds: List<Server> = emptyList(),
)

data class Loadlinks(
    val success: Boolean,
    val links: List<Link>,
)

data class Link(
    val index: Long,
    val link: String,
)
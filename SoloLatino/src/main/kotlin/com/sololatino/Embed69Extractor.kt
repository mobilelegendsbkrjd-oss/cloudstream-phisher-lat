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
        "Origin" to "https://embed69.org",
        "Accept" to "*/*"
    )

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val res = app.get(url, headers = HEADERS)
        val html = res.text

        // =========================
        // SUBTÍTULOS
        // =========================
        Regex("""<track[^>]*src="([^"]*\.vtt)"[^>]*label="([^"]*)"""")
            .findAll(html)
            .forEach {
                subtitleCallback(
                    SubtitleFile(
                        it.groupValues[2],
                        it.groupValues[1]
                    )
                )
            }

        // =========================
        // DATA LINK
        // =========================
        val dataLink = Regex("""dataLink\s*=\s*(\[.*?\]);""")
            .find(html)
            ?.groupValues?.getOrNull(1)
            ?: return

        val parsed = AppUtils.tryParseJson<List<ServersByLang>>(dataLink) ?: return

        val sortedLangs = parsed.sortedBy {
            val name = it.videoLanguage?.uppercase() ?: ""
            when {
                name.contains("LAT") -> 1
                name.contains("SUB") -> 2
                else -> 3
            }
        }

        sortedLangs.forEach { lang ->

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

            if (!decrypted.success) return@forEach

            decrypted.links.forEach { linkItem ->

                val fixed = fixHostsLinks(linkItem.link)

                when {

                    // 🔥 MINOCHINOS
                    fixed.contains("minochinos") -> {
                        extractMinochinos(fixed, referer, callback)
                    }

                    // 🔥 VIDHIDE
                    fixed.contains("vidhide") ||
                    fixed.contains("mivalyo") ||
                    fixed.contains("dhtpre") -> {
                        extractVidHide(fixed, referer, callback)
                    }

                    // 🔥 DEFAULT (IMPORTANTE: SIN COROUTINES)
                    else -> {
                        loadExtractor(fixed, referer, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    // =========================
    // MINOCHINOS
    // =========================
    private suspend fun extractMinochinos(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url).text

            Regex("""https?:\/\/[^\s"']+master\.m3u8""")
                .find(html)
                ?.value?.let { master ->

                    callback.invoke(
                        newExtractorLink(
                            "Minochinos",
                            "Minochinos HLS",
                            master
                        ) {
                            this.type = ExtractorLinkType.M3U8
                            this.referer = "https://minochinos.com/"
                            this.quality = 720
                        }
                    )
                }

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
            val html = app.get(url).text

            val packed = Regex("""eval\(function\(p,a,c,k,e,d.*?\)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.value ?: return

            val unpacked = JsUnpacker(packed).unpack() ?: return

            Regex("""https?:\/\/[^\s"']+\.m3u8""")
                .find(unpacked)
                ?.value?.let { m3u8 ->

                    callback.invoke(
                        newExtractorLink(
                            "VidHide",
                            "VidHide HLS",
                            m3u8
                        ) {
                            this.type = ExtractorLinkType.M3U8
                            this.referer = url
                            this.quality = 720
                        }
                    )
                }

        } catch (_: Exception) {}
    }

    // =========================
    // FIX HOSTS
    // =========================
    private fun fixHostsLinks(url: String): String {
        return url
            .replace("hglink.to", "streamwish.to")
            .replace("filemoon.link", "filemoon.sx")
            .replace("do7go.com", "dood.la")
    }
}

// =========================
// DATA CLASSES
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

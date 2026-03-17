package com.sololatino

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                        it.groupValues[1],
                        it.groupValues[2]
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
                name.contains("ESP") -> 3
                else -> 4
            }
        }

        sortedLangs.forEach { lang ->

            val linksJson = lang.sortedEmbeds
                .mapNotNull { it.link }
                .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

            val json = """{"links":$linksJson}"""

            val body = json.toRequestBody("application/json".toMediaTypeOrNull())

            val decrypted = app.post(
                DECRYPT_URL,
                requestBody = body,
                headers = HEADERS
            ).parsedSafe<Loadlinks>()

            if (decrypted?.success != true) return@forEach

            val sortedLinks = decrypted.links.sortedBy {
                val u = it.link.lowercase()
                when {
                    u.contains("voe") && u.contains("mp4") -> 1
                    u.contains("voe") -> 2
                    u.contains("filemoon") -> 3
                    u.contains("streamwish") -> 4
                    else -> 5
                }
            }

            sortedLinks.forEach { linkItem ->

                val fixed = fixHostsLinks(linkItem.link)

                when {

                    // 🔥 MINOCHINOS DIRECTO
                    fixed.contains("minochinos") -> {
                        extractMinochinos(
                            fixed,
                            referer,
                            callback
                        )
                    }

                    // 🔥 VIDHIDE
                    fixed.contains("vidhide") ||
                            fixed.contains("mivalyo") ||
                            fixed.contains("dhtpre") -> {

                        extractVidHide(
                            fixed,
                            referer,
                            callback
                        )
                    }

                    else -> {
                        loadSourceNameExtractor(
                            lang.videoLanguage ?: "Unknown",
                            fixed,
                            referer,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }
    }

    // =========================
    // 🔥 MINOCHINOS
    // =========================
    private suspend fun extractMinochinos(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to referer,
                    "Origin" to "https://minochinos.com"
                )
            )

            val html = res.text

            Regex("""https?:\/\/[^\s"']+master\.txt""")
                .find(html)
                ?.value?.let { master ->

                    callback(
                        newExtractorLink(
                            "Minochinos",
                            "Minochinos",
                            master
                        ) {
                            this.type = ExtractorLinkType.M3U8
                            this.referer = "https://minochinos.com/"
                            this.quality = 720
                        }
                    )
                }

        } catch (_: Exception) {
        }
    }

    // =========================
    // 🔥 VIDHIDE
    // =========================
    private suspend fun extractVidHide(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to referer
                )
            )

            val html = res.text

            val packed = Regex("""eval\(function\(p,a,c,k,e,d.*?\)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.value ?: return

            val unpacked = JsUnpacker(packed).unpack() ?: return

            Regex("""https?:\/\/[^\s"']+\.m3u8""")
                .find(unpacked)?.value?.let { m3u8 ->

                    callback(
                        newExtractorLink(
                            "VidHide HLS",
                            "VidHide HLS",
                            m3u8
                        ) {
                            this.type = ExtractorLinkType.M3U8
                            this.referer = url
                            this.quality = 720
                        }
                    )
                }

        } catch (_: Exception) {
        }
    }

    // =========================
    // DEFAULT EXTRACTOR
    // =========================
    private suspend fun loadSourceNameExtractor(
        source: String,
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(
                    newExtractorLink(
                        "$source[${link.source}]",
                        "$source[${link.source}]",
                        link.url,
                    ) {
                        this.quality = link.quality
                        this.type = link.type
                        this.referer = link.referer
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
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

data class LinksRequest(val links: List<String>)

data class Loadlinks(
    val success: Boolean,
    val links: List<Link>,
)

data class Link(
    val index: Long,
    val link: String,
)
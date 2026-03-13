package com.sololatino

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object Embed69Extractor {
    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).document.select("script")
            .firstOrNull { it.html().contains("dataLink = [") }?.html()
            ?.substringAfter("dataLink = ")
            ?.substringBefore(";")?.let {
                val parsed = AppUtils.tryParseJson<List<ServersByLang>>(it)

                // 1. ORDENAR POR IDIOMA (LAT > SUB > ESP > CAS > OTROS)
                val sortedLanguages = parsed?.sortedBy { lang ->
                    val name = lang.videoLanguage?.uppercase() ?: ""
                    when {
                        name.contains("LAT") -> 1
                        name.contains("SUB") -> 2
                        name.contains("ESP") -> 3
                        name.contains("CAS") -> 4
                        else -> 5
                    }
                }

                sortedLanguages?.forEach { lang ->
                    val jsonData = LinksRequest(lang.sortedEmbeds.amap { it.link!! })
                    val body = jsonData.toJson()
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                    val decrypted = app.post("https://embed69.org/api/decrypt", requestBody = body)
                        .parsedSafe<Loadlinks>()

                    if (decrypted?.success == true) {
                        // 2. ORDENAR POR SERVIDOR (VOE MP4 PRIMERO)
                        val sortedLinks = decrypted.links.sortedBy { linkItem ->
                            val linkUrl = linkItem.link.lowercase()
                            val isVoe = linkUrl.contains("voe")
                            val isMp4 = linkUrl.contains("mp4")
                            when {
                                isVoe && isMp4 -> 1
                                isVoe -> 2
                                linkUrl.contains("filemoon") -> 3
                                linkUrl.contains("streamwish") -> 4
                                else -> 5
                            }
                        }

                        sortedLinks.forEach { linkItem ->
                            loadSourceNameExtractor(
                                lang.videoLanguage!!,
                                fixHostsLinks(linkItem.link),
                                referer,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }
    }
}

data class Server(
    @JsonProperty("servername") val servername: String? = null,
    @JsonProperty("link") val link: String? = null,
)

data class ServersByLang(
    @JsonProperty("file_id") val fileId: String? = null,
    @JsonProperty("video_language") val videoLanguage: String? = null,
    @JsonProperty("sortedEmbeds") val sortedEmbeds: List<Server> = emptyList<Server>(),
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

suspend fun loadSourceNameExtractor(
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

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}
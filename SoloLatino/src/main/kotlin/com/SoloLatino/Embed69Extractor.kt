package com.sololatino

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object Embed69Extractor {

    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val scriptContent = doc.select("script")
            .firstOrNull { it.html().contains("dataLink = [") }
            ?.html() ?: return

        val jsonStr = scriptContent
            .substringAfter("dataLink = ")
            .substringBefore(";")
            .trim()

        val serversByLang = AppUtils.tryParseJson<List<ServersByLang>>(jsonStr) ?: return

        val allLinks = mutableListOf<ExtractorLink>()

        serversByLang.amap { lang ->
            val jsonData = LinksRequest(lang.sortedEmbeds.mapNotNull { it.link })
            val body = jsonData.toJson()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val decryptedResponse = app.post(
                "https://embed69.org/api/decrypt",
                requestBody = body
            )

            val decrypted = decryptedResponse.parsedSafe<Loadlinks>() ?: return@amap

            if (decrypted.success) {
                decrypted.links.amap { linkData ->
                    loadExtractor(
                        fixHostsLinks(linkData.link),
                        referer,
                        subtitleCallback
                    ) { baseLink ->
                        val langPrefix = lang.videoLanguage?.uppercase() ?: "??"
                        val processedLink = newExtractorLink(
                            "\( langPrefix[ \){baseLink.source}]",
                            "$langPrefix - ${baseLink.source}",
                            baseLink.url
                        ) {
                            quality = baseLink.quality
                            type = baseLink.type
                            referer = baseLink.referer
                            headers = baseLink.headers
                            extractorData = baseLink.extractorData
                        }
                        allLinks.add(processedLink)
                    }
                }
            }
        }

        // Orden forzado: LAT > SUB > CAS > resto
        val priorityMap = mapOf(
            "LAT" to 0,
            "LATINO" to 0,
            "SUB" to 1,
            "SUBTITULADO" to 1,
            "CAS" to 2,
            "CAST" to 2,
            "CASTELLANO" to 2,
            "ESP" to 2,          // por si usan ESP para castellano
            "ES" to 2
        )

        val sortedLinks = allLinks.sortedBy { link ->
            val upperName = link.name.uppercase()
            priorityMap.entries
                .firstOrNull { it.key in upperName }
                ?.value ?: 999
        }

        sortedLinks.forEach { callback(it) }
    }
}

data class Server(
    @JsonProperty("servername") val servername: String? = null,
    @JsonProperty("link") val link: String? = null,
)

data class ServersByLang(
    @JsonProperty("file_id") val fileId: String? = null,
    @JsonProperty("video_language") val videoLanguage: String? = null,
    @JsonProperty("sortedEmbeds") val sortedEmbeds: List<Server> = emptyList(),
)

data class LinksRequest(
    val links: List<String>,
)

data class Loadlinks(
    val success: Boolean,
    val links: List<Link>,
)

data class Link(
    val index: Long,
    val link: String,
)

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

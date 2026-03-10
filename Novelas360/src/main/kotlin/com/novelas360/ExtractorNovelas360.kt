package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360 Player"
    override val mainUrl = "https://novelas360.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val fixedReferer = referer ?: mainUrl

        println("Extrayendo de: $url (referer: $fixedReferer)")

        val doc = app.get(url, referer = fixedReferer).document

        val iframeSrc =
            doc.selectFirst("iframe[src*='novelas360.cyou/e/']")?.attr("abs:src")
                ?: doc.selectFirst("div.player iframe")?.attr("abs:src")
                ?: doc.selectFirst(".embed-responsive iframe")?.attr("abs:src")
                ?: run {
                    println("No encontré iframe con /e/")
                    return null
                }

        println("Iframe encontrado: $iframeSrc")

        val videoKey =
            iframeSrc.substringAfterLast("/e/")
                .takeIf { it.isNotBlank() }
                ?: return null

        // Generar cookies
        app.get(
            "https://novelas360.cyou/cdn-cgi/trace",
            referer = url
        )

        app.get(
            iframeSrc,
            referer = url,
            headers = mapOf(
                "Referer" to url,
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest"
            )
        )

        val postHeaders = mapOf(
            "Origin" to "https://novelas360.cyou",
            "Referer" to iframeSrc,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "User-Agent" to "Mozilla/5.0"
        )

        val postBody = mapOf(
            "v" to videoKey,
            "secure" to "0",
            "ver" to "4",
            "adb" to "0",
            "wasmcheck" to "0",
            "embed_from" to "0",
            "token" to "",
            "htoken" to "",
            "gt" to "",
            "adscore" to "",
            "click_hash" to ""
        )

        println("POST get_md5.php con key=$videoKey")

        val res = app.post(
            "https://novelas360.cyou/player/get_md5.php",
            data = postBody,
            headers = postHeaders,
            allowRedirects = true
        )

        println("Respuesta status ${res.code}")

        val json = res.parsedSafe<Map<String, String>>() ?: return null

        val file =
            json["file"]
                ?.takeIf { it.isNotBlank() && it.startsWith("http") }
                ?: return null

        println("Stream encontrado: $file")

        return listOf(
            newExtractorLink(
                name,
                "Novelas360 Stream",
                file
            ) {
                this.referer = iframeSrc
                this.quality = Qualities.Unknown.value

                this.type =
                    if (file.contains(".m3u8") || file.contains("master"))
                        ExtractorLinkType.M3U8
                    else
                        ExtractorLinkType.VIDEO

                this.headers = mapOf(
                    "Referer" to iframeSrc,
                    "Origin" to "https://novelas360.cyou",
                    "User-Agent" to "Mozilla/5.0"
                )
            }
        )
    }
}

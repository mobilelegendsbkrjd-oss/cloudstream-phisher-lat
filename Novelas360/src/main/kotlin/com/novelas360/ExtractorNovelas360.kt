package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360 Player"
    override val mainUrl = "https://novelas360.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {

        val iframeUrl = url
        val videoKey = iframeUrl.substringAfterLast("/e/")

        if (videoKey.isBlank())
            return null

        // Generar cookies
        app.get(
            iframeUrl,
            referer = referer ?: "https://novelas360.com"
        )

        val headers = mapOf(
            "Origin" to "https://novelas360.cyou",
            "Referer" to iframeUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to "Mozilla/5.0"
        )

        val data = mapOf(
            "v" to videoKey,
            "secure" to "0",
            "ver" to "4",
            "adb" to "0",
            "wasmcheck" to "0"
        )

        val res = app.post(
            "https://novelas360.cyou/player/get_md5.php",
            data = data,
            headers = headers
        )

        val json = res.parsedSafe<Map<String, String>>() ?: return null

        val file = json["file"] ?: return null

        return listOf(
            newExtractorLink(
                name,
                "Novelas360 Stream",
                file
            ) {
                this.referer = iframeUrl
                this.quality = Qualities.Unknown.value
                this.type =
                    if (file.contains(".m3u8"))
                        ExtractorLinkType.M3U8
                    else
                        ExtractorLinkType.VIDEO
            }
        )
    }
}

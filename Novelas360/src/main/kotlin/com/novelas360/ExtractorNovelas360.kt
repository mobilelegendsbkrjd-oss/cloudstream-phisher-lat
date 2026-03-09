package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360"
    override val mainUrl = "https://novelas360.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val doc = app.get(url).document

        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return

        if (!iframe.contains("novelas360.cyou")) return

        val key = iframe.substringAfter("/e/")

        app.get(iframe)

        val headers = mapOf(
            "Origin" to mainUrl,
            "Referer" to iframe,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json"
        )

        val body = """
        {
            "v":"$key",
            "secure":"0",
            "ver":"4",
            "adb":"0",
            "wasmcheck":0
        }
        """

        val response = app.post(
            "$mainUrl/player/get_md5.php",
            data = body,
            headers = headers
        )

        val file = response.jsonObject["file"]?.asString ?: return

        callback.invoke(
            ExtractorLink(
                name,
                name,
                file,
                iframe,
                Qualities.Unknown.value,
                isM3u8 = true
            )
        )
    }
}
package com.verpeliculasonline

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class UniversalXExtractor : ExtractorApi() {
    override val name = "Universal X"
    override val mainUrl = "https://opuxa.lat"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = url.replace("/f/", "/e/").trim()

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Referer" to "https://opuxa.lat/",
            "Origin" to "https://opuxa.lat",
            "Accept" to "*/*"
        )

        try {
            val response = app.get(fixedUrl, headers = headers)
            val html = response.text

            // Buscamos el packed script o el link directo
            val videoRegex = Regex("""(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
            videoRegex.findAll(html).forEach { match ->
                val videoLink = match.groupValues[1]

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoLink,
                        type = if (videoLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = fixedUrl
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) { }
    }
}
package com.utelevision

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

object UTelevisionExtractors {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private fun absoluteUrl(base: String, url: String): String {
        return if (url.startsWith("http")) url else base + url
    }

    // =========================
    // MAIN ENTRY
    // =========================
    suspend fun extract(
        baseUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val html = app.get(pageUrl).text
        var found = false

        // 1️⃣ Buscar iframes directos
        val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
        val iframes = iframeRegex.findAll(html)

        for (iframe in iframes) {
            val iframeUrl = absoluteUrl(baseUrl, iframe.groupValues[1])
            if (extractFromIframe(baseUrl, iframeUrl, callback)) {
                found = true
            }
        }

        // 2️⃣ Fallback: buscar m3u8 directos en la página
        val m3u8Regex = Regex("""https?://[^"' ]+\.m3u8[^"' ]*""")
        val m3u8s = m3u8Regex.findAll(html)

        for (m3u8 in m3u8s) {
            callback(
                newExtractorLink(
                    "uTelevision",
                    "Direct HLS",
                    m3u8.value,
                    ExtractorLinkType.M3U8
                ) {
                    referer = baseUrl
                    headers = mapOf("User-Agent" to USER_AGENT)
                    quality = getQualityFromUrl(m3u8.value)
                }
            )
            found = true
        }

        return found
    }

    // =========================
    // IFRAME EXTRACTOR
    // =========================
    private suspend fun extractFromIframe(
        baseUrl: String,
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val html = app.get(
            iframeUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to baseUrl
            )
        ).text

        var found = false

        // Buscar HLS
        val m3u8Regex = Regex("""https?://[^"' ]+\.m3u8[^"' ]*""")
        val m3u8s = m3u8Regex.findAll(html)

        for (m3u8 in m3u8s) {
            callback(
                newExtractorLink(
                    "uTelevision",
                    "Iframe HLS",
                    m3u8.value,
                    ExtractorLinkType.M3U8
                ) {
                    referer = baseUrl
                    headers = mapOf("User-Agent" to USER_AGENT)
                    quality = getQualityFromUrl(m3u8.value)
                }
            )
            found = true
        }

        return found
    }

    // =========================
    // QUALITY DETECTOR
    // =========================
    private fun getQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080") -> Qualities.P1080.value
            url.contains("720") -> Qualities.P720.value
            url.contains("480") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.P720.value
        }
    }
}

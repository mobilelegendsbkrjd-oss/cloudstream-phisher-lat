package com.novelas360

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.util.Qualities

class ExtractorNovelas360 : ExtractorApi() {
    override val name = "Novelas360 / Cyou"
    override val mainUrl = "https://novelas360.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {
        val fixedReferer = referer ?: mainUrl

        // 1. Visita el iframe para setear cookies y confirmar
        app.get(
            url,
            referer = fixedReferer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Origin" to mainUrl
            )
        )

        // 2. Extrae la key del /e/
        val key = url.substringAfter("/e/") ?: return null

        // 3. Headers exactos del POST que funcionaba
        val headers = mapOf(
            "Origin" to mainUrl,
            "Referer" to url,
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )

        val postData = mapOf(
            "v" to key,
            "secure" to "0",
            "ver" to "4",
            "adb" to "0",
            "wasmcheck" to "0"
        )

        // 4. POST al endpoint que devuelve el file
        val res = app.post(
            "$mainUrl/player/get_md5.php",
            data = postData,
            headers = headers,
            timeout = 30
        )

        val json = res.parsedSafe<Map<String, String>>() ?: return null
        val file = json["file"] ?: return null

        // 5. Retorna el link directo con headers correctos
        return listOf(
            ExtractorLink(
                source = name,
                name = "Directo (m3u8/mp4)",
                url = file,
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8 = file.contains(".m3u8")
            ).apply {
                this.headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                )
            }
        )
    }
}

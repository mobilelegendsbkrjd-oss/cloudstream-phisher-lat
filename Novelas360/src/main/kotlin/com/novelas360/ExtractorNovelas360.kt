package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ExtractorNovelas360 : ExtractorApi() {

    override val name = "Novelas360 Player"
    override val mainUrl = "https://novelas360.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, // url del episodio ej: https://novelas360.com/video/el-amor-no-tiene-receta-capitulo-49/
        referer: String?
    ): List<ExtractorLink>? {

        val fixedReferer = referer ?: mainUrl

        // 1. Obtener el HTML del episodio
        val doc = app.get(url, referer = fixedReferer).document

        // 2. Buscar el iframe del player (debe tener novelas360.cyou/e/)
        val iframeElement = doc.selectFirst("div.player iframe[src*='novelas360.cyou/e/']")
            ?: doc.selectFirst("iframe[src*='novelas360.cyou/e/']")
            ?: doc.selectFirst(".embed-responsive iframe")
            ?: return null

        val iframeUrl = iframeElement.attr("abs:src")
        if (iframeUrl.isEmpty() || !iframeUrl.contains("/e/")) return null

        val videoKey = iframeUrl.substringAfterLast("/e/")

        // 3. Visita previa al iframe para intentar generar cookies uid / trace (CloudStream maneja cookies por extractor)
        app.get(iframeUrl, referer = url, headers = mapOf(
            "Referer" to url,
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "*/*"
        ))

        // 4. Preparar POST a get_md5.php
        val postHeaders = mapOf(
            "Origin" to "https://novelas360.cyou",
            "Referer" to iframeUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // Body basado en tus curls (agregué extras comunes; ajusta si ves en logs que falta algo)
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
            "adscore" to ""
            // Si falla, prueba agregar: "click_hash" to "algunvalor", pero mejor capturar del browser real
        )

        val response = app.post(
            "https://novelas360.cyou/player/get_md5.php",
            data = postBody,
            headers = postHeaders,
            allowRedirects = true
        )

        val jsonResponse = response.parsedSafe<Map<String, Any?>>() ?: return null

        val fileUrl = jsonResponse["file"]?.toString() ?: return null

        if (fileUrl.isBlank() || fileUrl == "null") return null

        // 5. Devolver el link funcional
        return listOf(
            newExtractorLink(
                this.name,
                "Novelas360 HLS",
                fileUrl,
                referer = iframeUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = fileUrl.contains(".m3u8") || fileUrl.contains("master") || fileUrl.contains("playlist")
            ).apply {
                this.headers["Referer"] = iframeUrl
                this.headers["Origin"] = "https://novelas360.cyou"
            }
        )
    }
}

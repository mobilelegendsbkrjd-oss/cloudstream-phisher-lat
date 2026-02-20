package com.ennovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.util.Base64

class PoiwExtractor : ExtractorApi() {
    override val name = "Poiw Proxy"
    override val mainUrl = "https://a.poiw.online"
    override val requiresReferer = true

    private val vkHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "Referer" to "https://vk.com/",
        "sec-ch-ua" to "\"Google Chrome\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "Accept-Language" to "es-419,es;q=0.9",
        "Content-Type" to "application/x-www-form-urlencoded"
    )

    override suspend fun getUrl(
        url: String,  // url = proxyUrl (enn.php?post=...)
        referer: String?,  // referer = episodio
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Cargar proxy con headers de navegador
        val proxyResponse = app.get(url, referer = referer, headers = commonHeaders)
        if (!proxyResponse.isSuccessful) return

        // Extraer base64 y decodificar JSON
        val base64Part = url.substringAfter("post=").trim()
        val servers = try {
            val decoded = String(Base64.getDecoder().decode(base64Part))
            Json.decodeFromString<Map<String, String>>(decoded)
        } catch (e: Exception) { return }

        if (servers.isEmpty()) return

        fun fixEmbed(raw: String): String = raw.replace("\\/", "/")
            .replace("uqload.net", "uqload.to")
            .replace("vidspeeds.com", "vidsspeeds.com")
            .replace("vidhide.com", "vidhidepro.com")

        // Prioridad VK > Uqload > Vidsspeeds
        listOf("vk", "uqload", "vidsspeeds", "vidspeeds").forEach { key ->
            servers[key]?.let { raw ->
                val embedUrl = fixEmbed(raw)

                // Para VK, usar headers/cookies de tus curls
                val headers = if (embedUrl.contains("vk.com")) vkHeaders else commonHeaders

                val resolved = loadExtractor(
                    url = embedUrl,
                    referer = url,  // referer = proxy
                    headers = headers,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (!resolved) {
                    callback(
                        newExtractorLink(
                            source = key.uppercase(),
                            name = "$key (fallback)",
                            url = embedUrl
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
    }
}

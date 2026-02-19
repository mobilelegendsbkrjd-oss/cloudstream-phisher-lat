package com.ennovelas

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import android.util.Base64

class PoiwExtractor : ExtractorApi() {
    override val name = "Poiw Proxy"
    override val mainUrl = "https://a.poiw.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base64Data = url.substringAfter("post=").substringBefore("&")
        val jsonStr = try {
            String(Base64.decode(base64Data, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) { return }

        val servers = try {
            Json.decodeFromString<Map<String, String>>(jsonStr)
        } catch (e: Exception) { null }

        servers?.forEach { (serverName, rawEmbed) ->
            val cleanUrl = fixEmbed(rawEmbed)
            
            // Intentamos con extractores conocidos
            val success = loadExtractor(cleanUrl, url, subtitleCallback, callback)

            if (!success) {
                // Usamos la función recomendada newExtractorLink para evitar el error de compilación
                callback(
                    newExtractorLink(
                        source = "ESP - $serverName",
                        name = serverName,
                        url = cleanUrl,
                        referer = url,
                        quality = Qualities.Unknown.value
                    )
                )
            }
        }
    }

    private fun fixEmbed(url: String): String {
        return url.replace("\\/", "/")
            .replace("uqload.net", "uqload.to")
            .replace("uqload.com", "uqload.to")
            .replace("vidspeeds.com", "vidsspeeds.com")
            .replace("vidhide.com", "vidhidepro.com")
            .replace("vidhidepremium.com", "vidhidepro.com")
    }
}
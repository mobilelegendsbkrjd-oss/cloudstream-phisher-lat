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
            
            // 1. Intentamos con extractores nativos
            val success = loadExtractor(cleanUrl, url, subtitleCallback, callback)

            if (!success) {
                // 2. Fallback usando la firma correcta de tu versión:
                // source, name, url, type (null), y el bloque de inicialización
                callback(
                    newExtractorLink(
                        "ESP - $serverName", 
                        serverName,
                        cleanUrl,
                        null 
                    ) {
                        // Aquí dentro se asignan las variables que daban error
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
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

package com.ennovelas

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
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
        // 1. Extraer el Base64 del parámetro 'post'
        val base64Data = url.substringAfter("post=").substringBefore("&")
        
        // 2. Decodificar el JSON de servidores
        val jsonStr = try {
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
            String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return 
        }

        val servers = try {
            Json.decodeFromString<Map<String, String>>(jsonStr)
        } catch (e: Exception) {
            null
        }

        // 3. Procesar cada servidor encontrado en el JSON
        servers?.forEach { (serverName, rawEmbed) ->
            val cleanUrl = fixEmbed(rawEmbed)
            
            // Intentamos cargar el extractor nativo de CloudStream para ese servidor
            // Usamos la URL del proxy como referer para engañar al servidor de video
            val success = loadExtractor(
                url = cleanUrl,
                referer = url, 
                subtitleCallback = subtitleCallback,
                callback = callback
            )

            // Fallback: Si no hay extractor nativo, enviamos el link directo
            if (!success) {
                callback(
                    ExtractorLink(
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
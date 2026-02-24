package com.EntrePeliculasYSeries

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class XupalaceExtractor : ExtractorApi() {
    override val name = "Xupalace"
    override val mainUrl = "https://xupalace.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[XupalaceExtractor] Procesando URL: $url")
        
        try {
            // Obtener la página
            val response = app.get(url, referer = referer ?: mainUrl)
            val html = response.text
            
            println("[XupalaceExtractor] HTML obtenido (${html.length} chars)")
            
            // Buscar URLs en go_to_playerVast
            val pattern = """go_to_playerVast\s*\(\s*['"]([^'"]+)['"]""".toRegex()
            val matches = pattern.findAll(html)
            
            val serverUrls = mutableListOf<String>()
            
            matches.forEach { match ->
                val foundUrl = match.groupValues[1]
                serverUrls.add(foundUrl)
                println("[XupalaceExtractor] Encontrado en go_to_playerVast: $foundUrl")
            }
            
            // Si no encontramos, buscar otros patrones
            if (serverUrls.isEmpty()) {
                // Buscar iframes
                val iframePattern = """<iframe[^>]*src=["']([^"']+)["']""".toRegex()
                val iframeMatches = iframePattern.findAll(html)
                
                iframeMatches.forEach { match ->
                    val foundUrl = match.groupValues[1]
                    serverUrls.add(foundUrl)
                    println("[XupalaceExtractor] Encontrado en iframe: $foundUrl")
                }
                
                // Buscar en onclick
                val onclickPattern = """onclick=["'].*?go_to_player.*?\(['"]([^'"]+)['"]""".toRegex()
                val onclickMatches = onclickPattern.findAll(html)
                
                onclickMatches.forEach { match ->
                    val foundUrl = match.groupValues[1]
                    serverUrls.add(foundUrl)
                    println("[XupalaceExtractor] Encontrado en onclick: $foundUrl")
                }
            }
            
            // Procesar cada servidor encontrado
            if (serverUrls.isNotEmpty()) {
                println("[XupalaceExtractor] Procesando ${serverUrls.size} servidores")
                
                // Eliminar duplicados
                serverUrls.distinct().forEach { serverUrl ->
                    try {
                        println("[XupalaceExtractor] Intentando con: $serverUrl")
                        // loadExtractor se encargará de encontrar el extractor adecuado
                        loadExtractor(serverUrl, url, subtitleCallback, callback)
                        return // Salir si funciona
                    } catch (e: Exception) {
                        println("[XupalaceExtractor] Error con $serverUrl: ${e.message}")
                        // Continuar con el siguiente
                    }
                }
            }
            
            // Si llegamos aquí, nada funcionó
            println("[XupalaceExtractor] Ningún servidor funcionó, intentando con URL original")
            loadExtractor(url, referer, subtitleCallback, callback)
            
        } catch (e: Exception) {
            println("[XupalaceExtractor] ERROR: ${e.message}")
            // Último intento
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
}
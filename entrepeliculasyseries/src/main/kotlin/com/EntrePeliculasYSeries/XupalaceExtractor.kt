package com.EntrePeliculasYSeries

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI

class UniversalXExtractor : ExtractorApi() {
    override val name = "Universal X Extractor"
    override val mainUrl = "https://xupalace.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1. Petición inicial (seguimos redirecciones automáticamente)
            val response = app.get(url, referer = referer ?: url)
            val html = response.text
            val finalUrl = response.url // Por si hubo redirección por JS/AJAX
            val uri = URI(finalUrl)
            val baseHost = "${uri.scheme}://${uri.host}"

            val serverUrls = mutableListOf<String>()

            // 2. Patrones mejorados para encontrar enlaces "escondidos"
            val patterns = listOf(
                """go_to_playerVast\s*\(\s*['"]([^'"]+)['"]""".toRegex(),
                """<iframe[^>]*src=["']([^"']+)["']""".toRegex(),
                """<meta property="og:video" content="([^"]+)"""".toRegex(),
                // Este busca IDs de video en scripts de sitios como Opuxa/Netu
                """window\.location\.replace\(['"]([^'"]+)['"]\)""".toRegex(),
                // Busca URLs directas en bloques de texto/JS
                """https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/(?:e|embed|f)/[a-zA-Z0-9]+""".toRegex()
            )

            patterns.forEach { pattern ->
                pattern.findAll(html).forEach { match ->
                    var foundUrl = match.groupValues.getOrNull(1) ?: match.value

                    // Limpieza y reparación de rutas
                    if (foundUrl.startsWith("//")) {
                        foundUrl = "${uri.scheme}:$foundUrl"
                    } else if (foundUrl.startsWith("/")) {
                        foundUrl = "$baseHost$foundUrl"
                    }

                    serverUrls.add(foundUrl)
                }
            }

            // 3. Procesamiento con limpieza de dominios (Embed69 style)
            serverUrls.distinct().forEach { serverUrl ->
                val fixedUrl = fixHostsLinks(serverUrl)

                // Si el servidor es el mismo sitio (como opuxa llamando a opuxa),
                // evitamos bucles infinitos y cargamos el extractor específico.
                loadExtractor(fixedUrl, url, subtitleCallback, callback)
            }

        } catch (e: Exception) {
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private fun fixHostsLinks(url: String): String {
        return url
            .replace("dintezuvio.com", "vidhidepro.com")
            .replace("mivalyo.com", "vidhidepro.com")
            .replace("dinisglows.com", "vidhidepro.com")
            .replace("dhtpre.com", "vidhidepro.com")
            .replace("hglink.to", "streamwish.to")
            .replace("swdyu.com", "streamwish.to")
            .replace("cybervynx.com", "streamwish.to")
            .replace("dumbalag.com", "streamwish.to")
            .replace("filemoon.link", "filemoon.sx")
            .replace("bysedikamoum.com", "filemoon.sx")
            .replace("sblona.com", "watchsb.com")
            .replace("uqload.io", "uqload.com")
            .replace("do7go.com", "dood.la")
    }
}
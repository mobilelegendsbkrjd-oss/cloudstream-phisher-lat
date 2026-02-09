package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class Tlnovelas : MainAPI() {

    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select(".vk-poster, .ani-card, .p-content, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = selectFirst(".ani-txt, .p-title, .vk-info p")?.text()
                ?: selectFirst("a")?.attr("title") ?: ""
        var href = selectFirst("a")?.attr("href") ?: ""
        val poster = selectFirst("img")?.attr("src")

        if (href.contains("/ver/")) {
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+|-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { 
            posterUrl = poster 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar/?q=$query"
        return app.get(url).document.select(".vk-poster, .ani-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")
        val finalDoc = if (url.contains("/ver/") && novelaLink != null) app.get(novelaLink).document else document

        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")?.text()
            ?.replace(Regex("(?i)Capitulos de|Ver"), "")?.trim() ?: "Telenovela"

        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: finalDoc.selectFirst(".ani-img img")?.attr("src")

        val episodes = finalDoc.select("a[href*='/ver/']").map {
            val epUrl = it.attr("href")
            val epName = it.text().replace(title, "", true)
                .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "").trim()
            newEpisode(epUrl) { name = if (epName.isEmpty()) "Capítulo" else "Capítulo $epName" }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = finalDoc.selectFirst(".card-text, .ani-description")?.text()
        }
    }

    private fun decodeVideolUrl(encoded: String): String {
        return try {
            // Para patrones como 'oEiMaglJZklh|1'
            if (encoded.contains('|')) {
                val parts = encoded.split('|')
                val data = parts[0]
                val key = parts.getOrNull(1)?.toIntOrNull() ?: 0
                
                val result = StringBuilder()
                for (i in data.indices) {
                    val charCode = data[i].code - key - i
                    result.append(charCode.toChar())
                }
                return result.toString()
            }
            encoded
        } catch (e: Exception) {
            encoded
        }
    }

    private suspend fun extractVideoFromScript(response: String, data: String): List<String> {
        val videoLinks = mutableSetOf<String>()
        
        // Patrón 1: Buscar array e[] con valores ofuscados
        val arrayPattern = Regex("""e\[\s*(\d+)\s*\]\s*=\s*['"]([^'"]+)['"]""")
        val arrayMatches = arrayPattern.findAll(response)
        
        // Buscar función v_ideo para saber qué índice usar
        val videoFuncPattern = Regex("""v_ideo\(([^)]+)\)""")
        val videoFuncMatch = videoFuncPattern.find(response)
        
        // Si encontramos la función v_ideo, buscar el índice que usa
        var targetIndex = 0
        videoFuncMatch?.let { match ->
            val param = match.groupValues[1]
            val indexPattern = Regex("""e\[(\d+)\]""")
            val indexMatch = indexPattern.find(param)
            indexMatch?.let {
                targetIndex = it.groupValues[1].toIntOrNull() ?: 0
            }
        }
        
        // Decodificar el enlace del índice objetivo
        arrayMatches.forEach { match ->
            val index = match.groupValues[1].toIntOrNull() ?: 0
            val encoded = match.groupValues[2]
            
            if (index == targetIndex) {
                val decoded = decodeVideolUrl(encoded)
                if (decoded.startsWith("http")) {
                    videoLinks.add(decoded)
                }
            }
        }
        
        // Patrón 2: Buscar directamente enlaces en el HTML después de que se ejecuta JS
        // A veces los enlaces están en comentarios o en atributos data
        val directPatterns = listOf(
            Regex("""data-(?:src|file)\s*=\s*['"](https?://[^'"]+)['"]"""),
            Regex("""//video\.php\?h\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""atob\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        )
        
        directPatterns.forEach { pattern ->
            pattern.findAll(response).forEach { match ->
                val found = match.groupValues[1]
                if (found.startsWith("http")) {
                    videoLinks.add(found)
                } else if (found.contains("base64")) {
                    try {
                        val decoded = String(Base64.getDecoder().decode(found))
                        if (decoded.startsWith("http")) {
                            videoLinks.add(decoded)
                        }
                    } catch (e: Exception) {
                        // Ignorar errores de base64
                    }
                }
            }
        }
        
        // Patrón 3: Buscar en la inicialización de jQuery/JavaScript
        val jqueryPattern = Regex("""\\$\s*\([^)]+\)\.click\s*\([^)]+\)\s*{[^}]*v_ideo\s*\([^)]+\)[^}]*}""", RegexOption.DOT_MATCHES_ALL)
        jqueryPattern.find(response)?.let {
            // Dentro de este bloque, buscar el array e[]
            val block = it.value
            arrayPattern.findAll(block).forEach { arrayMatch ->
                val encoded = arrayMatch.groupValues[2]
                val decoded = decodeVideolUrl(encoded)
                if (decoded.startsWith("http")) {
                    videoLinks.add(decoded)
                }
            }
        }
        
        return videoLinks.toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        var success = false
        
        // Método 1: Extraer de scripts JavaScript
        val scriptLinks = extractVideoFromScript(response, data)
        scriptLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    success = true
                }
            } catch (e: Exception) {
                // Ignorar errores
            }
        }
        
        // Método 2: Buscar iframes directamente (como respaldo)
        if (!success) {
            val iframePattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            iframePattern.findAll(response).forEach { match ->
                val link = match.groupValues[1]
                if (!link.contains("google") && !link.contains("adskeeper")) {
                    try {
                        if (loadExtractor(link, data, subtitleCallback, callback)) {
                            success = true
                        }
                    } catch (e: Exception) {
                        // Ignorar errores
                    }
                }
            }
        }
        
        // Método 3: Buscar enlaces de video directos (mp4, m3u8, etc.)
        if (!success) {
            val videoPatterns = listOf(
                Regex("""https?://[^"'\s]+\.(mp4|m3u8|mkv|avi)[^"'\s]*""", RegexOption.IGNORE_CASE),
                Regex("""(https?://[^"'\s]+/v/[/\w]+)"""),
                Regex("""(https?://[^"'\s]+/embed/\d+)""")
            )
            
            videoPatterns.forEach { pattern ->
                pattern.findAll(response).forEach { match ->
                    val link = match.value
                    try {
                        if (loadExtractor(link, data, subtitleCallback, callback)) {
                            success = true
                        }
                    } catch (e: Exception) {
                        // Ignorar errores
                    }
                }
            }
        }
        
        return success
    }
}

package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder

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

    private fun decodeVideoUrl(encoded: String): String {
        return try {
            // Decodificar la cadena ofuscada
            val parts = encoded.split("|")
            if (parts.size == 2) {
                val encodedStr = parts[0]
                val key = parts[1].toInt()
                
                // Descifrado simple (basado en el patrón común de estos sitios)
                val decodedChars = mutableListOf<Char>()
                for (i in encodedStr.indices) {
                    val charCode = encodedStr[i].code - key - i
                    decodedChars.add(charCode.toChar())
                }
                val decodedString = decodedChars.joinToString("")
                
                // URL decode si es necesario
                URLDecoder.decode(decodedString, "UTF-8")
            } else {
                encoded
            }
        } catch (e: Exception) {
            encoded
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        val videoLinks = mutableSetOf<String>()

        // 1. Extraer todos los e[N] = 'ID|numero'
        val regexJs = Regex("""e\[\d+\]\s*=\s*['"]([^'"]+)['"]""")
        regexJs.findAll(response).forEach { match ->
            val full = match.groupValues[1].trim()

            if (full.contains("|")) {
                val parts = full.split("|")
                if (parts.size >= 2) {
                    val id = parts[0].trim()
                    val option = parts[1].trim()

                    val base = when (option) {
                        "1" -> "https://hqq.to/e/"
                        "2" -> "https://dood.yt/e/"
                        "3" -> "https://player.ojearanim.com/e/"
                        "4" -> "https://player.vernovelastv.net/e/"
                        else -> null
                    }

                    if (base != null && id.isNotEmpty()) {
                        val embed = base + id
                        videoLinks.add(embed)
                    }
                }
            }
        }

        // 2. Formato viejo var e = [...] (por compatibilidad)
        Regex("""var\s+e\s*=\s*\[([^\]]+)\]""").findAll(response).forEach { match ->
            match.groupValues[1].split(",")
                .map { it.trim().trim('\'', '"') }
                .filter { it.isNotEmpty() && it.contains("|") }
                .forEach { full ->
                    // Reutilizamos la misma lógica de arriba
                    val parts = full.split("|")
                    if (parts.size >= 2) {
                        val id = parts[0].trim()
                        val option = parts[1].trim()
                        val base = when (option) {
                            "1" -> "https://hqq.to/e/"
                            "2" -> "https://dood.yt/e/"
                            "3" -> "https://player.ojearanim.com/e/"
                            "4" -> "https://player.vernovelastv.net/e/"
                            else -> null
                        }
                        if (base != null && id.isNotEmpty()) {
                            videoLinks.add(base + id)
                        }
                    }
                }
        }

        // 3. Iframes directos (respaldo, filtra los útiles)
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val src = it.groupValues[1]
            if (src.contains("/e/") && !src.contains("google") && !src.contains("ads")) {
                videoLinks.add(src)
            }
        }

        // 4. Intentar cargar extractores con cada link encontrado
        var success = false
        videoLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    success = true
                }
            } catch (_: Throwable) {
                // Ignorar fallos individuales
            }
        }

        return success || videoLinks.isNotEmpty()
    }
}

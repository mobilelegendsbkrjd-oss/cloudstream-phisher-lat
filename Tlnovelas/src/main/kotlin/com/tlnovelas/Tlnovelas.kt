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
            val parts = encoded.split("|")
            if (parts.size == 2) {
                val encodedStr = parts[0]
                val key = parts[1].toInt()
                val decodedChars = mutableListOf<Char>()
                for (i in encodedStr.indices) {
                    val charCode = encodedStr[i].code - key - i
                    decodedChars.add(charCode.toChar())
                }
                val decodedString = decodedChars.joinToString("")
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

        // --------------------
        // TU CÓDIGO ORIGINAL (100% intacto)
        // --------------------
        val regexJsArray = Regex("""e\[\d+\]\s*=\s*['"]([^'"]+)['"]""")
        regexJsArray.findAll(response).forEach { match ->
            val encodedUrl = match.groupValues[1]
            val decodedUrl = decodeVideoUrl(encodedUrl)
            if (decodedUrl.startsWith("http")) {
                videoLinks.add(decodedUrl)
            }
        }

        val regexVideFunc = Regex("""v_ideo\(([^)]+)\)""")
        regexVideFunc.findAll(response).forEach { match ->
            val param = match.groupValues[1]
            val arrayIndex = Regex("""e\[(\d+)\]""").find(param)?.groupValues?.get(1)?.toIntOrNull()
            if (arrayIndex != null) {
                val arrayRegex = Regex("""e\[$arrayIndex\]\s*=\s*['"]([^'"]+)['"]""")
                arrayRegex.find(response)?.let { arrayMatch ->
                    val encodedUrl = arrayMatch.groupValues[1]
                    val decodedUrl = decodeVideoUrl(encodedUrl)
                    if (decodedUrl.startsWith("http")) {
                        videoLinks.add(decodedUrl)
                    }
                }
            }
        }

        Regex("""var\s+e\s*=\s*\[([^\]]+)\]""").findAll(response).forEach { match ->
            match.groupValues[1].split(",")
                .map { it.trim().trim('\'', '"') }
                .filter { it.isNotEmpty() }
                .forEach { encodedUrl ->
                    val decodedUrl = decodeVideoUrl(encodedUrl)
                    if (decodedUrl.startsWith("http")) {
                        videoLinks.add(decodedUrl)
                    }
                }
        }

        val decodedPatterns = listOf(
            Regex("""https?://[^"'\s<>]+\.(mp4|m3u8|mkv|avi|mov|flv|wmv|webm)[^"'\s<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/video/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/embed/[^"'\s<>]+""", RegexOption.IGNORE_CASE)
        )

        decodedPatterns.forEach { pattern ->
            pattern.findAll(response).forEach { match ->
                val url = match.value
                if (!url.contains("google") && !url.contains("adskeeper") && !url.contains("googletagmanager")) {
                    videoLinks.add(url)
                }
            }
        }

        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val link = it.groupValues[1]
            if (!link.contains("google") && !link.contains("adskeeper")) {
                videoLinks.add(link)
            }
        }

        var success = false
        videoLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    success = true
                }
            } catch (e: Exception) {
                // Ignorar
            }
        }

        // --------------------
        // FALLBACK NUEVO (solo si no encontró nada con tu método original)
        // --------------------
        if (!success || videoLinks.isEmpty()) {
            val embeds = mutableListOf<String>()

            // Busca los embeds del array e[] (como en tu HTML: e[0]='https://bysejikuar.com/e/...')
            Regex("""e\[\d+\]\s*=\s*['"](https?://[^'"]+)['"]""").findAll(response).forEach { match ->
                embeds.add(match.groupValues[1])
            }

            // Regex más amplia por si hay variaciones
            if (embeds.isEmpty()) {
                Regex("""['"](https?://[^'"]+/(e|d|l0i|ptz)/[a-z0-9]+)['"]""").findAll(response).forEach {
                    embeds.add(it.groupValues[1])
                }
            }

            embeds.distinct().forEach { embed ->
                try {
                    // Delegamos TODO a loadExtractor (compatible con dood, luluvdo, etc.)
                    // CloudStream intentará extraer el video del embed automáticamente
                    if (loadExtractor(embed, data, subtitleCallback, callback)) {
                        success = true
                    }
                } catch (e: Exception) {
                    // Ignorar y probar el siguiente embed
                }
            }
        }

        return success || videoLinks.isNotEmpty()
    }
}

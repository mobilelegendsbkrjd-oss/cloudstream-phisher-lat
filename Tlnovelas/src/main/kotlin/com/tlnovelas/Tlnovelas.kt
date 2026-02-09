package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

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
        val url = if (page <= 1) "\( mainUrl/ \){request.data}" else "\( mainUrl/ \){request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select(".vk-poster, .ani-card, .p-content, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".ani-txt, .p-title, .vk-info p")?.text()
            ?: selectFirst("a")?.attr("title") ?: return null
        var href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("abs:src") ?: ""

        if (href.contains("/ver/")) {
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("""(?i)-capitulo-\d+|-capítulo-\d+"""), "")
            href = "$mainUrl/novela/$slug/"
        }

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = poster
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
        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("abs:href")
        val finalDoc = if (url.contains("/ver/") && novelaLink != null) {
            app.get(novelaLink).document
        } else {
            document
        }

        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")?.text()
            ?.replace(Regex("(?i)Capitulos de|Ver"), "")?.trim() ?: "Telenovela"

        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("abs:content")
            ?: finalDoc.selectFirst(".ani-img img")?.attr("abs:src")

        val episodes = finalDoc.select("a[href*='/ver/']").mapNotNull {
            val epUrl = it.attr("abs:href")
            val epName = it.text().replace(title, "", ignoreCase = true)
                .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "").trim()
            newEpisode(epUrl) {
                name = if (epName.isEmpty()) "Capítulo" else "Capítulo $epName"
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = finalDoc.selectFirst(".card-text, .ani-description")?.text()
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

        // Formatos antiguos / directos
        Regex("""e\[\d+\]\s*=\s*['"](https?://[^'"]+)['"]""").findAll(response).forEach {
            videoLinks.add(it.groupValues[1].replace("\\/", "/"))
        }

        Regex("""var\s+e\s*=\s*\[([^\]]+)\]""").findAll(response).forEach { match ->
            match.groupValues[1].split(",")
                .map { it.trim().trim('\'', '"') }
                .filter { it.startsWith("http") }
                .forEach { videoLinks.add(it.replace("\\/", "/")) }
        }

        // Patrón actual principal: e[0] = 'ID|1'  → genera iframe
        Regex("""e\[\d+\]\s*=\s*['"]([^'"]+)['"]""").findAll(response).forEach { match ->
            val fullCode = match.groupValues[1]   // ej: abc123|1

            if (fullCode.contains("|")) {
                val parts = fullCode.split("|")
                if (parts.size >= 2) {
                    val id = parts[0].trim()
                    val option = parts[1].trim()

                    val baseUrl = when (option) {
                        "1" -> "https://hqq.to/e/"
                        "2" -> "https://dood.yt/e/"
                        "3" -> "https://player.ojearanime.com/e/"
                        "4" -> "https://player.vernovelastv.net/e/"
                        else -> ""
                    }

                    if (baseUrl.isNotEmpty() && id.isNotBlank()) {
                        videoLinks.add(baseUrl + id)
                    }
                }
            }
        }

        // Iframes que ya aparezcan en el HTML
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", setOf(RegexOption.IGNORE_CASE)).findAll(response)
            .forEach {
                val link = it.groupValues[1]
                if (link.contains("/e/") && !link.contains("google") && !link.contains("ads")) {
                    videoLinks.add(link)
                }
            }

        var foundAny = false
        videoLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    foundAny = true
                }
            } catch (_: Throwable) {}
        }

        return foundAny
    }
}

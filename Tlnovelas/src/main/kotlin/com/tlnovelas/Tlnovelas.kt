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
        val url =
            if (page <= 1) "$mainUrl/${request.data}"
            else "$mainUrl/${request.data}/page/$page"

        val document = app.get(url).document
        val home = document
            .select(".vk-poster, .p-content, .ani-card, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        var title =
            selectFirst(".vk-info p, .p-title, .ani-txt")?.text()
                ?: selectFirst("a")?.attr("title")
                ?: ""

        var href = selectFirst("a")?.attr("href") ?: ""
        val poster = selectFirst("img")?.attr("src")

        if (href.contains("/ver/")) {
            title = title.split(Regex("(?i)Capitulo|Capítulo"))[0].trim()

            val slug = href
                .removeSuffix("/")
                .substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+"), "")
                .replace(Regex("(?i)-capítulo-\\d+"), "")

            href = "$mainUrl/novela/$slug/"
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar/?q=$query"
        val document = app.get(url).document
        return document
            .select(".vk-poster, .ani-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")

        val finalDoc = if (url.contains("/ver/") && novelaLink != null)
            app.get(novelaLink).document
        else document

        val title =
            finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")
                ?.text()
                ?.replace(Regex("(?i)Capitulos de|Ver"), "")
                ?.trim()
                ?: "Telenovela"

        val poster =
            finalDoc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: finalDoc.selectFirst(".ani-img img")?.attr("src")
                ?: document.selectFirst(".vk-poster img")?.attr("src") // fallback portada exterior

        val description =
            finalDoc.selectFirst(".card-text, .ani-description")?.text()

        val episodes =
            finalDoc.select("a[href*='/ver/']")
                .map {
                    val epUrl = it.attr("href")
                    val epName = it.text()
                        .replace(title, "", true)
                        .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "")
                        .trim()

                    newEpisode(epUrl) {
                        name = if (epName.isEmpty()) "Capítulo" else "Capítulo $epName"
                    }
                }
                .distinctBy { it.data }
                .reversed()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        val videoLinks = mutableSetOf<String>() // Usamos Set para evitar duplicados

        // 1. Capturar asignaciones indexadas tipo: e[0]='https://...'
        // Esta es la que requiere el nuevo HTML que proporcionaste
        Regex("""e\[\d+\]\s*=\s*['"](https?://[^'"]+)['"]""").findAll(html).forEach {
            videoLinks.add(it.groupValues[1].replace("\\/", "/"))
        }

        // 2. Capturar arrays de JS completos tipo: var e = ['link1', 'link2']
        Regex("""var\s+e\s*=\s*\[([^\]]+)\]""").findAll(html).forEach { match ->
            match.groupValues[1].split(",")
                .map { it.trim().trim('\'', '"') }
                .filter { it.startsWith("http") }
                .forEach { videoLinks.add(it.replace("\\/", "/")) }
        }

        // 3. Capturar Iframes estándar en el HTML
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { videoLinks.add(it.groupValues[1]) }

        // Procesar todos los links encontrados
        for (link in videoLinks) {
            try {
                loadExtractor(link, data, subtitleCallback, callback)
            } catch (_: Exception) {
                // Si un extractor falla, el bucle continúa con el siguiente
            }
        }

        return videoLinks.isNotEmpty()
    }

}

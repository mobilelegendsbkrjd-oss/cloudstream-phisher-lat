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

    override suspend fun loadLinks(
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val response = app.get(data).text
    val videoLinks = mutableSetOf<String>()

    // 1. Tus extractores actuales (manténlos por si acaso)
    // ... tus regex de e[] = '...', var e = [...], iframes ...

    // 2. Extracción específica para este sitio (lo que faltaba)
    Regex("""e\[\d+\]\s*=\s*['"]([^'"]+)['"]""").findAll(response).forEach { match ->
        val fullCode = match.groupValues[1]  // ej: oEiMaglJZklh|1

        if (fullCode.contains("|")) {
            val parts = fullCode.split("|")
            if (parts.size >= 2) {
                val id = parts[0].trim()
                val option = parts[1].trim()

                val baseUrl = when (option) {
                    "1" -> "https://hqq.to/e/"
                    "2" -> "https://dood.yt/e/"
                    "3" -> "https://player.ojearanim.com/e/"
                    "4" -> "https://player.vernovelastv.net/e/"
                    else -> ""  // o puedes fallback a fullCode
                }

                if (baseUrl.isNotEmpty()) {
                    val embedUrl = baseUrl + id
                    videoLinks.add(embedUrl)
                    // Opcional: log para debug
                    // println("Encontrado embed: $embedUrl")
                }
            }
        }
    }

    // 3. Procesar los links encontrados (los iframes de arriba suelen ser reproductores conocidos)
    videoLinks.forEach { embedUrl ->
        // Estos son embeds clásicos → CloudStream los maneja bien con loadExtractor
        loadExtractor(embedUrl, data, subtitleCallback, callback)
    }

    // 4. Si quieres ser más agresivo, busca cualquier iframe que aparezca en la página
    Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
        val src = it.groupValues[1].fixUrl(mainUrl)
        if (src.contains("/e/") || src.contains("hqq.to") || src.contains("dood.yt") || src.contains("player.")) {
            loadExtractor(src, data, subtitleCallback, callback)
        }
    }

    return videoLinks.isNotEmpty() || /* tus otros checks */
    }
}

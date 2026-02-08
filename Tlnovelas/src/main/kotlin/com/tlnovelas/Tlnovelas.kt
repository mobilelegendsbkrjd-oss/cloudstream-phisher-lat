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
        
        // Selectores actualizados para capturar las sugerencias/carteles
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

        // Lógica para convertir link de capítulo a link de novela (para ver todos los caps)
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
        
        // Si el usuario entra por un link de "ver capítulo", intentamos ir a la página principal de la novela
        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")
        val finalDoc = if (url.contains("/ver/") && novelaLink != null) app.get(novelaLink).document else document

        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")?.text()
            ?.replace(Regex("(?i)Capitulos de|Ver"), "")?.trim() ?: "Telenovela"

        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: finalDoc.selectFirst(".ani-img img")?.attr("src")

        // Extraer lista de episodios
        val episodes = finalDoc.select("a[href*='/ver/']").map {
            val epUrl = it.attr("href")
            val epName = it.text().replace(title, "", true)
                .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "").trim()
            newEpisode(epUrl) { 
                name = if (epName.isEmpty()) "Capítulo" else "Capítulo $epName"
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = finalDoc.selectFirst(".card-text, .ani-description")?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        val foundLinks = mutableListOf<String>()

        // 1. Extraer directamente del JS (Patrón e[0], e[1]...)
        Regex("""e\[\d+\]\s*=\s*['"](https?://[^'"]+)['"]""").findAll(response).forEach {
            foundLinks.add(it.groupValues[1].replace("\\/", "/"))
        }

        // 2. Extraer de iframes (evitando anuncios)
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val link = it.groupValues[1]
            if (!link.contains("google") && !link.contains("adskeeper")) {
                foundLinks.add(link)
            }
        }

        // 3. Procesar todos los links con el Referer correcto
        var linksSent = 0
        foundLinks.distinct().forEach { link ->
            try {
                // Forzamos el referer como la URL de la página para saltar protecciones
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    linksSent++
                }
            } catch (e: Exception) {
                // Ignorar fallos individuales de extractores
            }
        }

        return linksSent > 0 || foundLinks.isNotEmpty()
    }
}

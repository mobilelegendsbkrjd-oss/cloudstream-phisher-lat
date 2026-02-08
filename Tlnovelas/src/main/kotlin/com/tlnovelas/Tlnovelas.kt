package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Tlnovelas : MainAPI() {
    override var mainUrl              = "https://ww2.tlnovelas.net"
    override var name                 = "Tlnovelas"
    override val hasMainPage          = true
    override var lang                 = "es"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "novelas" to "Novelas",
        "telenovelas-finalizadas" to "Finalizadas",
        "proximos-estrenos" to "Próximos Estrenos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        // Usamos documentLarge igual que en tu base de Latanime
        val document = app.get(url).documentLarge
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.selectFirst(".title, h3")?.text() ?: ""
        val href      = this.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = this.selectFirst("img")?.attr("src")
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").documentLarge
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document    = app.get(url).documentLarge
        val title       = document.selectFirst("h1")?.text() ?: "Desconocido"
        val poster      = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst(".entry-content p")?.text()
        
        // Selector específico para los episodios en este sitio
        val episodes = document.select("ul.episodios li").map {
            val a = it.selectFirst("a")
            val epHref = a?.attr("href") ?: ""
            val epName = it.text().trim()
            newEpisode(epHref) {
                this.name = epName
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).documentLarge
        
        // Primero intentamos con iframes directos
        document.select("iframe").forEach {
            val src = it.attr("src")
            if (src.isNotBlank() && !src.contains("facebook")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // Si usa el sistema de opciones de Dooplay (muy común en novelas)
        document.select(".dooplay_player_option").forEach {
            val type = it.attr("data-type")
            val post = it.attr("data-post")
            val nume = it.attr("data-nume")
            
            if (post.isNotBlank()) {
                val res = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
                
                val embedUrl = res.substringAfter("src='").substringBefore("'")
                    .ifBlank { res.substringAfter("src=\"").substringBefore("\"") }
                
                if (embedUrl.startsWith("http")) {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}

package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class Tlnovelas : MainAPI() {
    override var mainUrl              = "https://ww2.tlnovelas.net"
    override var name                 = "Tlnovelas"
    override val hasMainPage          = true
    override var lang                 = "es"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select(".vk-poster, .p-content, .ani-card, .ani-txt").mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        // CORRECCIÓN DE NOMBRES EN HOME: Priorizamos .ani-txt que es el más común
        var title = this.selectFirst(".ani-txt, .p-title, .vk-info p")?.text() 
            ?: this.selectFirst("a")?.attr("title") ?: ""
        
        var href = this.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        if (href.contains("/ver/")) {
            // Limpieza: "Amanecer Capítulo 69" -> "Amanecer"
            title = title.split(Regex("(?i)Capitulo|Capítulo"))[0].trim()
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+"), "")
                .replace(Regex("(?i)-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Si entramos por error a un link de "ver", saltamos a la página de la novela
        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")
        val finalDoc = if (novelaLink != null && url.contains("/ver/")) {
            app.get(novelaLink).document
        } else document

        // CORRECCIÓN TÍTULO "SIN NOMBRE": Buscamos el H1 real de la novela
        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")?.text()
            ?.replace(Regex("(?i)Capitulos de|Ver"), "")?.trim() ?: "Telenovela"
            
        // IMAGEN INTERNA: Usamos meta de la novela o el poster de la ficha
        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content") 
            ?: finalDoc.selectFirst(".ani-img img")?.attr("src")

        // EPISODIOS: Nombres cortos (Capítulo XX)
        val episodes = finalDoc.select("a[href*='/ver/']").mapNotNull {
            val epHref = it.attr("href")
            val epNumber = epHref.removeSuffix("/").substringAfterLast("-")
                
            newEpisode(epHref) {
                this.name = "Capítulo $epNumber"
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = finalDoc.selectFirst(".card-text")?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        
        // Capturamos el array e[0], e[1]...
        val videoUrlRegex = Regex("""e\[\d+\]\s*=\s*['"](https?://[^'"]+)['"]""")
        
        videoUrlRegex.findAll(response).forEach { match ->
            val link = match.groupValues[1].replace("\\/", "/")
            
            // 1. Intentamos con extractores nativos
            val found = loadExtractor(link, data, subtitleCallback, callback)
            
            // 2. EXTRACTOR MANUAL (Para LuluStream, Byse y otros basados en JWPlayer)
            if (!found) {
                try {
                    val serverHtml = app.get(link).text
                    
                    // Buscamos links .m3u8 o .mp4 dentro del código del servidor
                    val fileRegex = Regex("""file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                    val fileMatch = fileRegex.find(serverHtml)
                    
                    if (fileMatch != null) {
                        val videoUrl = fileMatch.groupValues[1]
                        val name = if (link.contains("lulu")) "LuluStream" else "Mirror Alterno"
                        
                        callback.invoke(
                            ExtractorLink(
                                name,
                                name,
                                videoUrl,
                                link,
                                Qualities.Unknown.value,
                                isM3u8 = videoUrl.contains("m3u8")
                            )
                        )
                    } else if (link.contains("byse")) {
                        // Caso especial para Byse si no tiene link directo: mostrar como externo
                        callback.invoke(
                            ExtractorLink("Byse Server", "Byse Server", link, data, Qualities.Unknown.value)
                        )
                    }
                } catch (e: Exception) {
                    // Si falla la petición al servidor, ignoramos y seguimos
                }
            }
        }
        return true
    }
}

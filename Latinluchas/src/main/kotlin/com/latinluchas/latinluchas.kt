package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class LatinLuchas : MainAPI() {

    override var mainUrl = "https://tv.latinluchas.com/tv"
    override var name = "TV LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        if (page > 1) return newHomePageResponse(emptyList())

        val document = app.get(mainUrl).document

        // Agregamos ".replay-show" al selector para detectar el nuevo HTML
        val items = document
            .select("article, .elementor-post, .post, .replay-show, a[href*='/tv/coli']")
            .mapNotNull { element ->
                
                var title: String
                var href: String
                var poster: String

                // LÓGICA NUEVA: Para las tarjetas de "Repetición"
                if (element.hasClass("replay-show")) {
                    title = element.selectFirst("h3")?.text()?.trim() ?: "Repetición"
                    
                    // IMPORTANTE: Tu nuevo HTML usa 'data-src' para las imágenes
                    poster = element.selectFirst("img")?.attr("abs:data-src")
                        ?.ifBlank { element.selectFirst("img")?.attr("abs:src") }
                        ?: defaultPoster

                    // Buscamos el primer botón de "OPCIÓN" que sea para ver (ignoramos descargas si es posible, o tomamos el primero)
                    // Priorizamos enlaces que contengan "/tv/"
                    href = element.select("a.watch-button")
                        .firstOrNull { it.attr("href").contains("/tv/") }
                        ?.attr("abs:href") 
                        ?: return@mapNotNull null

                } else {
                    // LÓGICA ANTIGUA: Para los posts normales o eventos en vivo anteriores
                    val linkElement = if (element.tagName() == "a") element else element.selectFirst("a")
                    href = linkElement?.attr("abs:href") ?: return@mapNotNull null
                    
                    // Filtro de seguridad antiguo
                    if (!href.contains("/tv/")) return@mapNotNull null

                    title = element.selectFirst("h2, h3, .entry-title, a")?.text()?.trim() ?: "Evento"
                    
                    poster = element.selectFirst("img")?.attr("abs:src") 
                        ?: element.selectFirst("img")?.attr("abs:data-src")
                        ?: defaultPoster
                }

                newLiveSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(
                HomePageList("Eventos y Repeticiones", items)
            )
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.title()
            .replace("Ver ", "", true)
            .substringBefore(" En Vivo")
            .substringBefore(" - LATINLUCHAS")
            .trim()
            .ifBlank { "Evento en vivo" }

        val plot = document.selectFirst("meta[property='og:description']")
                ?.attr("content")
                ?: document.selectFirst("meta[name='description']")?.attr("content")
                ?: "Transmisión en vivo y repeticiones - TV LatinLuchas"

        val poster = document.selectFirst("meta[property='og:image']")
                ?.attr("content") 
                ?: defaultPoster

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            this.plot = plot
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        var foundLinks = false

        // 1. Búsqueda de IFrames (Estándar)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("abs:data-src") }
            if (src.isNotBlank()) {
                val fixedSrc = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fixedSrc, data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        // 2. Búsqueda de Scripts (Acordeones/Live)
        if (!foundLinks) {
            val scriptContent = document.select("script").joinToString { it.data() }
            val regex = """const\s+\w+HTML\s*=\s*`([\s\S]+?)`;""".toRegex()
            
            regex.findAll(scriptContent).forEach { matchResult ->
                val htmlInsideScript = matchResult.groupValues[1]
                val scriptDoc = Jsoup.parse(htmlInsideScript)

                scriptDoc.select("a[href]").forEach { link ->
                    val channelName = link.text().trim()
                    val channelUrl = link.attr("href")

                    if (channelUrl.contains("latinluchas.com/")) {
                        try {
                            // Visitamos la sub-página (ej: canal-1-esp)
                            val subDoc = app.get(channelUrl).document
                            subDoc.select("iframe").forEach { iframe ->
                                val src = iframe.attr("abs:src").ifBlank { iframe.attr("abs:data-src") }
                                if (src.isNotBlank()) {
                                    val fixedSrc = if (src.startsWith("//")) "https:$src" else src
                                    
                                    loadExtractor(fixedSrc, channelUrl, subtitleCallback) { link ->
                                        callback(link.copy(name = "$channelName - ${link.name}"))
                                    }
                                    foundLinks = true
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
            }
        }
        
        return foundLinks
    }
}

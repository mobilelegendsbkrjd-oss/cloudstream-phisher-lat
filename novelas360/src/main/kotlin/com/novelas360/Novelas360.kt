package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ==============================
    // HTTP SAFE
    // ==============================
    private suspend fun getDoc(url: String): Document? =
        runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
                    "Referer" to mainUrl
                )
            ).document
        }.getOrNull()

    private fun fixUrl(url: String?): String? =
        if (url.isNullOrBlank()) null
        else if (url.startsWith("//")) "https:$url" else url

    // ==============================
    // MAIN PAGE (CATEGORÍAS)
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val categories = listOf(
            "Telenovelas México" to "/telenovelas/mexico/",
            "Telenovelas Turcas" to "/telenovelas/turcas/",
            "Telenovelas Colombianas" to "/telenovelas/colombianas/",
            "Telenovelas Brasileñas" to "/telenovelas/brasilenas/",
            "Telenovelas Argentinas" to "/telenovelas/argentinas/"
        )

        val home = categories.mapNotNull { (title, path) ->
            val doc = getDoc("$mainUrl$path") ?: return@mapNotNull null
            
            // INTENTO 2: Combinar tu selector original con el estándar del tema
            // 'div.tabcontent#Todos > a' -> Tu selector original (funciona para listas tipo texto/imagen)
            // '.video-item' -> Selector estándar del tema Videotube para rejillas
            val items = doc.select("div.tabcontent#Todos > a, .video-item, article.post")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (items.isEmpty()) null else HomePageList(title, items)
        }

        return newHomePageResponse(home, false)
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query") ?: return emptyList()

        // Para búsqueda, el tema suele usar .video-item
        return document.select(".video-item, article, .post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ==============================
    // PARSER (Híbrido)
    // ==============================
    private fun Element.toSearchResult(): SearchResponse? {
        // DETECTAR TIPO DE ELEMENTO:
        // Si el selector agarró un <a> directo (tu código original), úsalo.
        // Si agarró un <div> (mi código nuevo), busca el <a> adentro.
        val link = if (this.tagName() == "a") this else this.selectFirst("a")
        
        val href = link?.attr("href") ?: return null
        if (href.isBlank()) return null

        // Título: Busca en h3, h2, span (original) o atributo title
        val title = this.selectFirst("h3, h2, span.tabcontentnom")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")
            ?: return null

        // Imagen
        val img = this.selectFirst("img")
        val poster = fixUrl(
            img?.attr("data-src")?.ifBlank { img.attr("src") }
            ?: img?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ==============================
    // LOAD SERIE
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url) ?: throw ErrorLoadingException()

        val title = document.selectFirst("h1, h2.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("–")?.trim()
            ?: "Novela"

        val plot = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val poster = fixUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        // Selectores de episodios:
        // 1. div.item h3 a -> Tu selector original
        // 2. .entry-content a -> Enlaces dentro del texto del post (común en este sitio)
        val episodesAsc = document.select("div.item h3 a, .entry-content a")
            .filter { 
                val href = it.attr("href")
                // Filtramos basura: debe tener /video/ o capitulo en la url
                href.contains("/video/") || href.contains("capitulo", true)
            }
            .distinctBy { it.attr("href") }
            .mapIndexedNotNull { index, el ->
                newEpisode(el.attr("href")) {
                    name = el.text().trim().ifBlank { "Capítulo ${index + 1}" }
                    episode = index + 1
                }
            }

        // Ordenamos descendente
        val episodes = episodesAsc.reversed()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.plot = plot
            this.posterUrl = poster
        }
    }

    // ==============================
    // LOAD LINKS (Sin cambios, esto ya funciona)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = getDoc(data) ?: return false

        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src")) ?: return@forEach
            // Carga el extractor nativo de Cloudstream (Maneja Dailymotion, OkRu, etc)
            loadExtractor(src, data, subtitleCallback, callback)
        }
        
        return true
    }
}

package com.series24

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Series24 : MainAPI() {

    override var mainUrl = "https://cc5w.series24.cc"
    override var name = "Series24"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/serie-completa/" to "🎬 Series Completas",
        "$mainUrl/series-genero/accion/" to "🔥 Acción",
        "$mainUrl/series-genero/drama/" to "🎭 Drama",
        "$mainUrl/series-genero/comedia/" to "😂 Comedia",
        "$mainUrl/series-genero/ciencia-ficcion/" to "🚀 Ciencia Ficción",
        "$mainUrl/series-genero/terror/" to "👻 Terror",
        "$mainUrl/series-genero/animacion/" to "🐱 Animación",
        "$mainUrl/series-genero/novelas/" to "📖 Novelas",
        "$mainUrl/series-genero/anime/" to "🇯🇵 Anime"
    )

    /* =======================
       Helpers
       ======================= */

    private fun getPoster(el: Element?): String? {
        if (el == null) return null
        return el.attr("data-src")
            .ifBlank { el.attr("src") }
            .takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
    }

    /* =======================
       Main Page
       ======================= */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val doc = app.get(url).document

        val items = doc.select("article.item").mapNotNull { article ->
            val link = article.selectFirst("a") ?: return@mapNotNull null
            val title = article.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = getPoster(article.selectFirst("img"))

            newTvSeriesSearchResponse(
                title,
                fixUrl(link.attr("href")),
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, items, false),
            hasNext = doc.select(".pagination a.next, a:contains(Siguiente)").isNotEmpty()
        )
    }

    /* =======================
       Search
       ======================= */

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document

        return doc.select("article.item").mapNotNull { article ->
            val link = article.selectFirst("a") ?: return@mapNotNull null
            val title = article.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = getPoster(article.selectFirst("img"))

            newTvSeriesSearchResponse(
                title,
                fixUrl(link.attr("href")),
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }

    /* =======================
       Load Serie (Página de temporada completa)
       ======================= */

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // Verificar si es página de serie o episodio individual
        val isEpisodePage = url.contains("/ver-serie-online/")
        
        if (isEpisodePage) {
            return loadEpisodePage(doc, url)
        } else {
            return loadSeriesPage(doc, url)
        }
    }

    private suspend fun loadSeriesPage(doc: Element, url: String): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("No title")

        val poster = getPoster(doc.selectFirst(".poster img"))
        val plot = doc.selectFirst(".wp-content p")?.text()

        val episodes = mutableListOf<Episode>()

        // Intentar obtener episodios de la sección de temporadas
        doc.select("div.se-c").forEach { season ->
            val seasonNumber = season.attr("data-season").toIntOrNull()

            season.select("li").forEach { ep ->
                val link = ep.selectFirst("a") ?: return@forEach
                val epUrl = fixUrl(link.attr("href"))

                // Extraer número de episodio correctamente
                val epNumText = ep.selectFirst(".numerando")?.text() ?: ""
                val epNum = extractEpisodeNumber(epNumText)

                val epTitle = ep.selectFirst(".episodiotitle")?.text()?.trim()

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = seasonNumber
                        this.episode = epNum
                    }
                )
            }
        }

        // Si no hay episodios en div.se-c, buscar en otros lugares
        if (episodes.isEmpty()) {
            doc.select("article.w_item_c").forEach { article ->
                val link = article.selectFirst("a") ?: return@forEach
                val epUrl = fixUrl(link.attr("href"))
                val epTitle = article.selectFirst("h3")?.text()?.trim() ?: return@forEach
                
                // Intentar extraer temporada y episodio del título
                val seasonEpisode = extractSeasonEpisodeFromTitle(epTitle)
                
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = seasonEpisode?.first
                        this.episode = seasonEpisode?.second
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    private suspend fun loadEpisodePage(doc: Element, url: String): LoadResponse {
        val title = doc.selectFirst("h1.epih1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("No title")

        val poster = getPoster(doc.selectFirst(".poster img"))
        val plot = doc.selectFirst("#info .wp-content")?.text()
            ?: doc.selectFirst("h3.epih3")?.text()?.trim()

        // Extraer información de temporada/episodio del título o URL
        val seasonEpisode = extractSeasonEpisodeFromTitle(title)
        
        // Crear un solo episodio usando newEpisode
        val episode = newEpisode(url) {
            this.name = title
            this.season = seasonEpisode?.first
            this.episode = seasonEpisode?.second
            this.posterUrl = poster
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            listOf(episode)
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    /* =======================
       Helper para extraer número de episodio
       ======================= */

    private fun extractEpisodeNumber(text: String): Int? {
        // Ejemplo: "1 - 1" o "1x1" o "S01E01"
        return when {
            text.contains("-") -> {
                text.split("-").lastOrNull()?.filter { it.isDigit() }?.toIntOrNull()
            }
            text.contains("x") -> {
                text.split("x").lastOrNull()?.filter { it.isDigit() }?.toIntOrNull()
            }
            else -> {
                text.filter { it.isDigit() }.toIntOrNull()
            }
        }
    }

    private fun extractSeasonEpisodeFromTitle(title: String): Pair<Int?, Int?> {
        // Patrones comunes: "1x1", "S01E01", "Temporada 1 Episodio 1", "1x01"
        val patterns = listOf(
            """(\d+)[x×](\d+)""".toRegex(RegexOption.IGNORE_CASE), // 1x1, 1x01
            """S(\d+)\s*E(\d+)""".toRegex(RegexOption.IGNORE_CASE), // S01E01
            """Temporada\s+(\d+).*?Episodio\s+(\d+)""".toRegex(RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null && match.groupValues.size >= 3) {
                val season = match.groupValues[1].toIntOrNull()
                val episode = match.groupValues[2].toIntOrNull()
                return Pair(season, episode)
            }
        }
        
        // Intentar extraer de la URL
        val urlPattern = """(\d+)x(\d+)""".toRegex()
        val urlMatch = urlPattern.find(title)
        if (urlMatch != null && urlMatch.groupValues.size >= 3) {
            val season = urlMatch.groupValues[1].toIntOrNull()
            val episode = urlMatch.groupValues[2].toIntOrNull()
            return Pair(season, episode)
        }

        return Pair(null, null)
    }

    /* =======================
       Links - MEJORADO para manejar múltiples servidores
       ======================= */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        var found = false

        // PRIMERO: Probar los enlaces de player option (AJAX)
        doc.select(".dooplay_player_option").forEach { opt ->
            val post = opt.attr("data-post")
            val nume = opt.attr("data-nume")
            val type = opt.attr("data-type")

            if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach

            try {
                val res = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<Map<String, String>>() ?: return@forEach

                val embed = res["embed_url"] ?: return@forEach
                loadExtractor(embed, data, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                // Continuar con otros métodos si este falla
                e.printStackTrace()
            }
        }

        // SEGUNDO: Si no se encontraron enlaces AJAX, buscar en las tablas de enlaces
        if (!found) {
            // Buscar en la tabla de "Ver en línea"
            doc.select("#videos table tbody tr").forEach { row ->
                val linkElement = row.selectFirst("td a[href]") ?: return@forEach
                val link = linkElement.attr("href")
                
                if (link.isNotBlank() && !link.startsWith("javascript:")) {
                    loadExtractor(link, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // TERCERO: También revisar enlaces de descarga como respaldo
        if (!found) {
            doc.select("#download table tbody tr").forEach { row ->
                val linkElement = row.selectFirst("td a[href]") ?: return@forEach
                val link = linkElement.attr("href")
                
                if (link.isNotBlank() && !link.startsWith("javascript:")) {
                    loadExtractor(link, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }
}
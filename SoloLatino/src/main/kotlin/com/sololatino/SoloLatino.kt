package com.sololatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SoloLatino : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series" to "Series",
        "$mainUrl/animes" to "Animes",
        "$mainUrl/genre_series/toons" to "Cartoons",
        "$mainUrl/listas/" to "Listas de la comunidad"   // ← Nueva sección
    )

    private fun getImage(el: Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src", "data-srcset", "data-lazy-src", "src", "srcset")
        for (attr in attrs) {
            val v = el.attr(attr).trim()
            if (v.isNotBlank() && !v.startsWith("data:image")) {
                return v.split(",").first().trim().split(" ").firstOrNull { it.startsWith("http") }
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = listOf(
            "Películas" to "$mainUrl/peliculas" + if (page > 1) "/page/$page" else "",
            "Series" to "$mainUrl/series" + if (page > 1) "/page/$page" else "",
            "Animes" to "$mainUrl/animes" + if (page > 1) "/page/$page" else "",
            "Cartoons" to "$mainUrl/genre_series/toons" + if (page > 1) "/page/$page" else "",
            "Listas de la comunidad" to "$mainUrl/listas/" + if (page > 1) "page/$page/" else ""
        )

        val homeLists = sections.map { (name, url) ->
            val doc = app.get(url, timeout = 35).document
            val tvType = when (name) {
                "Películas" -> TvType.Movie
                else -> TvType.TvSeries
            }

            val items = if (name == "Listas de la comunidad") {
                // En /listas/ principal: cada lista es un bloque con h2 + texto + favorite + imgs
                // Pero para simplicidad y consistencia, usamos selector amplio (ajusta si falla)
                doc.select("h2:has(a), article.post-type-listas, .items article").mapNotNull { el ->
                    val titleEl = el.selectFirst("h2 a, h2, h3") ?: return@mapNotNull null
                    val title = titleEl.text().trim()
                    val link = titleEl.attrAbsUrl("href").takeIf { it.isNotBlank() }
                        ?: el.selectFirst("a")?.attrAbsUrl("href") ?: return@mapNotNull null

                    val img = getImage(el.selectFirst("img")) 
                        ?: "https://sololatino.net/wp-content/uploads/2022/11/logo-final.png"

                    newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                        posterUrl = img
                        description = "Lista curada por la comunidad"
                    }
                }
            } else {
                // Scraping estándar para pelis/series/animes
                doc.select("div.items article.item").mapNotNull {
                    val title = it.selectFirst("a div.data h3")?.text() ?: return@mapNotNull null
                    val link = it.selectFirst("a")?.attrAbsUrl("href") ?: return@mapNotNull null
                    val img = getImage(it.selectFirst("div.poster img"))

                    newTvSeriesSearchResponse(title, link, tvType) {
                        posterUrl = img
                    }
                }
            }

            HomePageList(name, items)
        }.filter { it.list.isNotEmpty() }

        return HomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.items article.item").mapNotNull {
            val title = it.selectFirst("a div.data h3")?.text() ?: return@mapNotNull null
            val link = it.selectFirst("a")?.attrAbsUrl("href") ?: return@mapNotNull null
            val img = getImage(it.selectFirst("div.poster img"))

            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                posterUrl = img
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 40).document

        val isUserList = url.contains("/listas/") && doc.selectFirst("div.infoCard") != null

        if (isUserList) {
            val listTitle = doc.selectFirst("div.infoCard h1")?.text()?.trim() ?: "Lista de la Comunidad"
            val description = doc.selectFirst("div.infoCard article p")?.text()?.trim() ?: ""
            val author = doc.selectFirst("div.infoCard a.createdbyT span")?.text()?.trim() ?: "Usuario desconocido"
            val likes = doc.selectFirst("div.infoCard div.createdbyT span")?.text()?.trim() ?: "0"

            // Extraer items y REVERTIR para orden cronológico (1 → última)
            val rawEpisodes = doc.select("#archive-content article.item").mapIndexedNotNull { index, article ->
                val a = article.selectFirst("a") ?: return@mapIndexedNotNull null
                val epUrl = a.attrAbsUrl("href")
                val epName = article.selectFirst("h3")?.text()?.trim() ?: "Parte ${index + 1}"
                val epYear = article.selectFirst(".data p")?.text()?.trim()
                val epImg = getImage(article.selectFirst("img.lazyload, img"))

                newEpisode(epUrl) {
                    name = epName + if (epYear != null) " ($epYear)" else ""
                    season = 1
                    episode = index + 1  // temporal, se ajustará después
                    posterUrl = epImg
                }
            }.reversed()  // ← INVERSIÓN para cronológico

            // Re-asignar episode numbers después de revertir
            val episodes = rawEpisodes.mapIndexed { newIndex, ep ->
                ep.apply { episode = newIndex + 1 }
            }

            // Poster = portada de la PRIMERA película (ahora episodio 1)
            val listPoster = episodes.firstOrNull()?.posterUrl
                ?: doc.selectFirst("div.infoCard .uAvatar img")?.attrAbsUrl("data-src")
                ?: "https://sololatino.net/wp-content/uploads/2022/11/logo-final.png"

            return newTvSeriesLoadResponse(
                listTitle,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = listPoster
                backgroundPosterUrl = listPoster
                plot = buildString {
                    append(description.ifBlank { "Saga o colección curada por la comunidad." })
                    append("\n\n")
                    append("Creada por: $author\n")
                    append("Favoritos: $likes\n")
                    append("Lista de SoloLatino • Ideal para maratón en orden cronológico")
                }
                tags = listOf("Comunidad", "Saga", "Recomendación", "Maratón")
            }
        }

        // Código original para películas y series normales (lo mantengo igual, solo resumo)
        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
        val poster = doc.selectFirst("div.poster img")?.attr("data-src")
        val backimage = doc.selectFirst(".wallpaper")?.attr("style")?.substringAfter("url(")?.substringBefore(");")
        val description = doc.selectFirst("div.wp-content")?.text()
        val tags = doc.select("div.sgeneros a").map { it.text() }

        val episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { season ->
                season.select("ul.episodios li").map {
                    val epurl = it.selectFirst("a")?.attrAbsUrl("href") ?: ""
                    val epTitle = it.selectFirst("div.episodiotitle div.epst")?.text() ?: ""
                    val nums = it.selectFirst("div.numerando")?.text()?.split("-")?.map { n -> n.trim().toIntOrNull() }
                    val realimg = it.selectFirst("div.imagen img")?.attr("data-src")

                    newEpisode(epurl) {
                        name = epTitle
                        season = nums?.getOrNull(0) ?: 1
                        episode = nums?.getOrNull(1) ?: 1
                        posterUrl = realimg
                    }
                }
            }
        } else emptyList()

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesLoadResponse(title, url, tvType, episodes) {
                posterUrl = poster
                backgroundPosterUrl = backimage ?: poster
                plot = description
                this.tags = tags
            }
            TvType.Movie -> newMovieLoadResponse(title, url, tvType, url) {
                posterUrl = poster
                backgroundPosterUrl = backimage ?: poster
                plot = description
                this.tags = tags
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Tu código original de loadLinks (lo dejo igual, puedes mejorarlo después)
        val doc = app.get(data).document
        var found = false

        doc.selectFirst("iframe")?.attr("src")?.let { src ->
            val finalSrc = if (src.startsWith("//")) "https:$src" else src
            if (finalSrc.startsWith("https://embed69.org/")) {
                Embed69Extractor.load(finalSrc, data, subtitleCallback, callback)
            } else if (finalSrc.startsWith("https://xupalace.org/video")) {
                val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                regex.findAll(app.get(finalSrc).document.html()).map { it.groupValues[2] }
                    .toList().forEach {
                        loadExtractor(fixHostsLinks(it), data, subtitleCallback, callback)
                    }
            } else {
                app.get(finalSrc).document.selectFirst("iframe")?.attr("src")?.let {
                    loadExtractor(fixHostsLinks(it), data, subtitleCallback, callback)
                }
            }
            found = true
        }
        return found
    }
}

package com.dramafun

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject

class DramaFun : MainAPI() {

    override var mainUrl = "https://ww6.dramafuntv.com"
    override var name = "DramaFun"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    // ================= HOME PAGE =================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val home = mutableListOf<HomePageList>()

        val sections = listOf(
            "Últimos Videos" to "$mainUrl/newvideos.php",
            "Top Videos" to "$mainUrl/topvideos.php",
            "Doramas Sub Español" to "$mainUrl/category.php?cat=Doramas-Sub-Espanol",
            "Novelas Turcas Sub" to "$mainUrl/category.php?cat=novelas-turcas-subtituladas",
            "Películas Latino" to "$mainUrl/category.php?cat=peliculas-audio-espanol-latino"
        )

        sections.forEach { (title, url) ->
            val items = parseListPage(app.get(url).document)
            if (items.isNotEmpty()) {
                home.add(HomePageList(title, items))
            }
        }

        return HomePageResponse(home)
    }

    // ================= SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/search.php?keywords=$query").document
        return parseListPage(doc)
    }

    // ================= PARSE LISTAS (newvideos, top, category, search) =================
    private fun parseListPage(doc: Document): List<SearchResponse> {
        return doc.select("a[href*=watch.php?vid=]").mapNotNull { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl/$it" }
            val titleRaw = a.ownText().trim().ifEmpty { a.attr("title") }.trim()
                .removeSurrounding("[", "]")

            val cleanTitle = titleRaw.replace(Regex("(?i)\\(en\\s*Español\\)|Sub\\s*Español|HD|online|completo"), "").trim()

            newMovieSearchResponse(
                name = cleanTitle,
                url = href,
                apiName = name,
                type = TvType.Movie  // tratamos como movie en listas, pero load detecta si es serie
            ) {
                posterUrl = null  // no hay posters en estas listas
            }
        }.distinctBy { it.url }
    }

    // ================= LOAD (aquí detectamos si es serie o movie) =================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        // Título principal (del episodio o serie)
        val titleRaw = doc.selectFirst("h1[itemprop=name], h1, .pm-series-brief h1")?.text()?.trim()
            ?: "Sin título"
        val cleanTitle = titleRaw.replace(Regex("(?i)Capitulo.*|online sub español HD|en Español"), "").trim()

        // Poster (del episodio o de la serie)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".pm-series-brief img, .pm-video-thumb img, img[src*=uploads/thumbs]")?.attr("abs:src")

        // Descripción (de la serie si existe)
        val plot = doc.selectFirst(".pm-series-description p, .pm-video-description p, meta[name=description]")?.text()?.trim()

        // === Detectar si es serie con episodios ===
        val episodeSelectors = "div.tabcontent ul.s a[href*=watch.php?vid=], select.episodeoption option[value*=watch.php?vid=]"
        val episodeElements = doc.select(episodeSelectors)

        if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapIndexedNotNull { index, el ->
                val epHrefRaw = el.attr("href") ?: el.attr("value") ?: return@mapIndexedNotNull null
                val epUrl = if (epHrefRaw.startsWith("http")) epHrefRaw else "$mainUrl/$epHrefRaw"

                val epText = el.text().trim().ifEmpty { el.attr("title") ?: "" }
                val epNum = Regex("(?i)capitulo\\s*(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)

                newEpisode(epUrl) {
                    name = "Capítulo $epNum"
                    episode = epNum
                    season = 1  // por ahora solo temporada 1, si ves más tabs puedes extender
                }
            }.sortedBy { it.episode }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    name = cleanTitle,
                    url = url,
                    type = TvType.AsianDrama,
                    episodes = episodes
                ) {
                    posterUrl = poster
                    plot = plot
                    // Puedes parsear más si quieres (year, tags, etc.)
                }
            }
        }

        // Fallback: episodio suelto o película
        return newMovieLoadResponse(
            name = cleanTitle,
            url = url,
            apiName = name,
            type = TvType.Movie,
            data = url
        ) {
            posterUrl = poster
            plot = plot
        }
    }

    // ================= LOAD LINKS =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        // Buscar todos los enlaces a enfun.php con post=base64
        val enfunUrls = doc.select("a.xtgo[href*=enfun.php?post=], a[href*=enfun.php?post=]")
            .map { it.attr("abs:href") }
            .distinct()

        enfunUrls.forEach { enfunUrl ->
            val postBase64 = enfunUrl.substringAfter("post=").substringBefore("&") // por si hay params extra
            if (postBase64.isNotBlank()) {
                try {
                    val decoded = String(Base64.decode(postBase64, Base64.URL_SAFE or Base64.NO_WRAP))
                    val json = JSONObject(decoded)

                    if (json.has("servers")) {
                        val servers = json.getJSONObject("servers")
                        val keys = servers.keys()
                        while (keys.hasNext()) {
                            val serverName = keys.next()
                            val embedUrl = servers.getString(serverName)
                            loadExtractor(embedUrl, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // Si falla el base64, ignoramos ese enlace
                }
            }
        }

        // Fallback: cualquier iframe directo en la página o en enfun
        doc.select("iframe[src*='embed'], video source[src]").forEach { el ->
            val src = el.attr("abs:src")
            if (src.isNotBlank()) {
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "Embed directo",
                        url = src,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8 = src.endsWith(".m3u8")
                    )
                )
            }
        }

        return true
    }
}

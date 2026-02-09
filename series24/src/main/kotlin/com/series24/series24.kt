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
       Load Serie
       ======================= */

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("No title")

        val poster = getPoster(doc.selectFirst(".poster img"))
        val plot = doc.selectFirst(".wp-content p")?.text()

        val episodes = mutableListOf<Episode>()

        doc.select("div.se-c").forEach { season ->
            val seasonNumber = season.attr("data-season").toIntOrNull()

            season.select("li").forEach { ep ->
                val link = ep.selectFirst("a") ?: return@forEach
                val epUrl = fixUrl(link.attr("href"))

                val epNum = ep.selectFirst(".numerando")?.text()
                    ?.filter { it.isDigit() }
                    ?.toIntOrNull()

                val epTitle = ep.selectFirst(".episodiotitle")?.text()

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
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

    /* =======================
       Links
       ======================= */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        var found = false

        // Hosts conocidos con captcha / bloqueo
        val blockedHosts = listOf(
            "stre4mpay",
            "recaptcha",
            "google.com/recaptcha",
            "captcha"
        )

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

                // ⛔ saltar embeds con captcha
                if (blockedHosts.any { embed.contains(it, ignoreCase = true) }) {
                    return@forEach
                }

                var extracted = false

                loadExtractor(embed, data, subtitleCallback) { link ->
                    callback(link)
                    extracted = true
                }

                // ✅ solo marcar como encontrado si salió video real
                if (extracted) {
                    found = true
                    return@forEach
                }

            } catch (_: Exception) {
                // seguir con el siguiente server
            }
        }

        return found
    }
}

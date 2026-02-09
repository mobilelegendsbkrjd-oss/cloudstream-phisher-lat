package com.verpeliculasonline

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class VerPeliculasOnline : MainAPI() {
    override var mainUrl = "https://verpeliculasonline.org"
    override var name = "VerPeliculasOnline"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/categoria/peliculas/" to "Películas",
        "/categoria/series/" to "Series",
        "/categoria/estrenos/" to "Estrenos",
        "/categoria/accion/" to "Acción",
        "/categoria/terror/" to "Terror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(fixUrl(url)).document
        val items = doc.select("article").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val img = it.selectFirst("img")
            val poster = img?.attr("data-src") ?: img?.attr("data-lazy-src") ?: img?.attr("src")
            newMovieSearchResponse(title.trim(), fixUrl(a.attr("href")), TvType.Movie) {
                posterUrl = fixUrlNull(poster)
            }
        }
        return newHomePageResponse(listOf(HomePageList(request.name, items)), items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val img = it.selectFirst("img")
            val poster = img?.attr("data-src") ?: img?.attr("src")
            newMovieSearchResponse(title.trim(), fixUrl(a.attr("href")), TvType.Movie) {
                posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst(".sinopsis, .descripcion, #info")?.text()

        val episodes = doc.select("ul.episodios li, .episodes-list li").mapIndexed { idx, el ->
            val a = el.selectFirst("a")
            newEpisode(fixUrl(a?.attr("href") ?: "")) {
                name = a?.text()?.replace("Capitulo ", "Episodio ")
                episode = idx + 1
            }
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = fixUrlNull(poster)
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = fixUrlNull(poster)
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val postId = doc.selectFirst("link[rel=shortlink]")?.attr("href")?.substringAfter("?p=")
            ?: doc.selectFirst("input[name=post]")?.attr("value") ?: ""

        doc.select("ul#playeroptionsul li").forEach { li ->
            val nume = li.attr("data-nume")
            val type = li.attr("data-type")

            if (nume.isNotEmpty()) {
                try {
                    val response = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf("action" to "doo_player_ajax", "post" to postId, "nume" to nume, "type" to type),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
                        )
                    )

                    val body = response.text

                    if (body.contains("loadermain")) {
                        val hexId = Regex("""id="([0-9a-fA-F]{30,})"""").find(body)?.groupValues?.get(1)
                        if (hexId != null) {
                            val decoded = decodeHex(hexId)
                            // LIMPIEZA CRÍTICA: Quitamos las barras invertidas que rompen la URL
                            val realUrl = Regex("""https?://[^\s"']+""").find(decoded)?.value?.replace("\\", "")
                            if (realUrl != null) {
                                loadExtractor(realUrl, data, subtitleCallback, callback)
                            }
                        }
                    } else {
                        val res = response.parsedSafe<AjaxResponse>()
                        res?.embedUrl?.let { embed ->
                            val finalUrl = embed.replace("\\/", "/")
                            if (finalUrl.startsWith("http")) {
                                loadExtractor(finalUrl, data, subtitleCallback, callback)
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        }
        return true
    }

    private fun decodeHex(hex: String): String {
        val output = StringBuilder()
        try {
            var i = 0
            while (i < hex.length) {
                val str = hex.substring(i, i + 2)
                output.append(str.toInt(16).toChar())
                i += 2
            }
        } catch (e: Exception) { }
        return output.toString()
    }

    data class AjaxResponse(@JsonProperty("embed_url") val embedUrl: String?)
}
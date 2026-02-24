package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack

// --- Extractor específico Canal 2 ---
class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Upns"
    override var mainUrl = "https://latinlucha.upns.online"
}

class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    // =========================
    // MAIN PAGE
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val categories = listOf(
            Pair("WWE", "$mainUrl/category/eventos/wwe/"),
            Pair("UFC", "$mainUrl/category/eventos/ufc/"),
            Pair("AEW", "$mainUrl/category/eventos/aew/"),
            Pair("Lucha Libre Mexicana", "$mainUrl/category/eventos/lucha-libre-mexicana/"),
            Pair("Indies", "$mainUrl/category/eventos/indies/")
        )

        val homePages = categories.map { (catName, url) ->
            val doc = app.get(url).document
            val items = doc.select("article, .post, .elementor-post").mapNotNull { element ->
                val title = element.selectFirst("h2, h3, .entry-title")
                    ?.text()?.trim() ?: return@mapNotNull null

                val href = element.selectFirst("a")
                    ?.attr("abs:href") ?: return@mapNotNull null

                val poster = element.selectFirst("img")
                    ?.attr("abs:src")
                    ?.ifBlank {
                        element.selectFirst("img")?.attr("abs:data-src")
                    } ?: defaultPoster

                newAnimeSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }

            HomePageList(catName, items)
        }

        return newHomePageResponse(homePages)
    }

    // =========================
    // LOAD (FICHA BONITA)
    // =========================
    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()?.trim() ?: "Evento"

        val poster = document.selectFirst("meta[property='og:image']")
            ?.attr("content") ?: defaultPoster

        val rawPlot = document.selectFirst("meta[property='og:description']")
            ?.attr("content")
            ?: document.selectFirst(".entry-content p")
                ?.text()
            ?: "Evento de lucha libre"

        val plot = buildString {
            append(rawPlot.trim())
            append("\n\nGénero: Lucha Libre, Wrestling, Deportes")
        }

        // Crear episodios (opciones)
        val episodes = document.select(
            "a.watch-button, .replay-options a, .accordion-content a"
        ).mapIndexedNotNull { index, anchor ->

            val name = anchor.text().trim()
            val link = anchor.attr("abs:href")

            val isVideoLink = name.contains(
                Regex("CANAL|OPCIÓ|ENGLISH|PELEA|MAIN|PRELIM",
                    RegexOption.IGNORE_CASE)
            )

            if (!isVideoLink ||
                link.contains("descargar", true) ||
                link.isBlank()
            ) return@mapIndexedNotNull null

            newEpisode(link) {
                this.name = name.ifBlank { "OPCIÓN ${index + 1}" }
                this.season = 1
                this.episode = index + 1
            }
        }.distinctBy { it.data }

        // Recomendaciones simples (de la misma categoría)
        val recommendations = document.select(
            "article .entry-title a"
        ).mapNotNull { rec ->
            val recTitle = rec.text().trim()
            val recHref = rec.attr("abs:href")

            if (recHref == url || recHref.isBlank()) return@mapNotNull null

            newAnimeSearchResponse(recTitle, recHref, TvType.TvSeries) {
                this.posterUrl = defaultPoster
            }
        }.take(12)

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = listOf("Lucha Libre", "Wrestling", "Deportes")
            if (recommendations.isNotEmpty()) {
                this.recommendations = recommendations
            }
        }
    }

    // =========================
    // LOAD LINKS (NO TOCAMOS)
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->

            var src = iframe.attr("abs:src")
                .ifBlank { iframe.attr("abs:data-src") }
                .ifBlank { iframe.attr("src") }

            if (src.isBlank()) return@forEach

            if (src.startsWith("//")) src = "https:$src"

            when {
                src.contains("upns.online") -> {
                    LatinLuchaUpns().getUrl(
                        src,
                        data,
                        subtitleCallback,
                        callback
                    )
                }

                else -> loadExtractor(
                    src,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}

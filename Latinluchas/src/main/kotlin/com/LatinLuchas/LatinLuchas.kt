package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack

// ===== EXTRACTOR PARA UPNS =====
class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Upns"
    override var mainUrl = "https://latinlucha.upns.online"
}

// ===== API PRINCIPAL =====
class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    // ================= HOME =================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val categories = listOf(
            "WWE" to "$mainUrl/category/eventos/wwe/",
            "UFC" to "$mainUrl/category/eventos/ufc/",
            "AEW" to "$mainUrl/category/eventos/aew/",
            "Lucha Libre Mexicana" to "$mainUrl/category/eventos/lucha-libre-mexicana/",
            "Indies" to "$mainUrl/category/eventos/indies/"
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
                    ?: element.selectFirst("img")
                        ?.attr("abs:data-src")
                    ?: defaultPoster

                newAnimeSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }

            HomePageList(catName, items)
        }

        return newHomePageResponse(homePages)
    }

    // ================= LOAD (FILTRADO REAL DEL ACORDEÓN) =================
    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()?.trim() ?: return null

        val poster = document.selectFirst("meta[property='og:image']")
            ?.attr("content") ?: defaultPoster

        val plot = document.selectFirst("meta[property='og:description']")
            ?.attr("content")

        // ---- NORMALIZAMOS EL TÍTULO PARA COMPARAR ----
        val normalizedTitle = title
            .lowercase()
            .replace(Regex("\\d{1,2} de .*? \\d{4}"), "")
            .replace("en vivo", "")
            .replace("y repetición", "")
            .replace("repeticion", "")
            .trim()

        // Tomamos solo la palabra clave principal (RAW, NXT, UFC, etc.)
        val keyword = normalizedTitle
            .split(" ")
            .firstOrNull()
            ?.trim() ?: ""

        // ---- FILTRAMOS SOLO EL ACORDEÓN CORRECTO ----
        val episodes = document
            .select(".accordion")
            .mapNotNull { accordion ->

                val headerText = accordion
                    .selectFirst(".accordion-header h3")
                    ?.text()
                    ?.lowercase()
                    ?: return@mapNotNull null

                if (!headerText.contains(keyword)) return@mapNotNull null

                accordion
                    .select(".accordion-content a")
                    .mapNotNull { anchor ->

                        val link = anchor.attr("abs:href")
                        val name = anchor.text().trim()

                        if (link.isBlank()) null
                        else newEpisode(link) {
                            this.name = name
                        }
                    }
            }
            .flatten()
            .distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = listOf("Lucha Libre", "Wrestling", "Deportes")
        }
    }

    // ================= LOAD LINKS =================
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

            if (src.startsWith("//")) {
                src = "https:$src"
            }

            when {
                src.contains("upns.online") -> {
                    LatinLuchaUpns().getUrl(src, data, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
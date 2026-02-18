package com.doramasmp4

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.jsoup.nodes.Element

// =============================
// MODELOS NEXTJS
// =============================

@Serializable
data class NextData(
    val props: Props? = null
)

@Serializable
data class Props(
    val pageProps: PageProps? = null
)

@Serializable
data class PageProps(
    val trendsDoramas: List<DoramaItem>? = null,
    val carrouselDoramas: List<DoramaItem>? = null,
    val carrouselMovies: List<DoramaItem>? = null
)

@Serializable
data class DoramaItem(
    val name: String? = null,
    val name_es: String? = null,
    val slug: String? = null,
    val poster: String? = null,
    val poster_path: String? = null
)

// =============================
// MAIN API
// =============================

class DoramasMP4 : MainAPI() {

    override var mainUrl = "https://doramasmp4.io"
    override var name = "DoramasMP4"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        mainUrl to "Inicio"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(mainUrl, headers = headers).document
        val jsonRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return newHomePageResponse("Inicio", emptyList())

        val parsed = parseJson<Map<String, Any>>(jsonRaw)

        val items = mutableListOf<SearchResponse>()

        fun extractFromMap(map: Map<String, Any>?) {
            map?.values?.forEach { value ->
                when (value) {
                    is List<*> -> {
                        value.forEach { item ->
                            if (item is Map<*, *>) {
                                val slug = item["slug"] as? String
                                val name = item["name_es"] as? String
                                    ?: item["name"] as? String
                                val poster = item["poster"] as? String
                                    ?: item["poster_path"] as? String

                                if (!slug.isNullOrBlank() && !name.isNullOrBlank()) {
                                    items.add(
                                        newTvSeriesSearchResponse(
                                            name,
                                            "$mainUrl/temporadas/$slug-1",
                                            TvType.AsianDrama
                                        ) {
                                            this.posterUrl = fixPoster(poster)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    is Map<*, *> -> {
                        extractFromMap(value as? Map<String, Any>)
                    }
                }
            }
        }

        extractFromMap(parsed)

        return newHomePageResponse(
            "Inicio",
            items.distinctBy { it.url }
        )
    }
    private fun fixPoster(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return when {
            path.startsWith("http") -> path
            path.startsWith("/") -> "https://image.tmdb.org/t/p/w500$path"
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val doc = app.get("$mainUrl/?s=$query", headers = headers).document

        return doc.select("a[href*='/temporadas/']")
            .mapNotNull { element ->
                val title = element.text().trim()
                val href = element.attr("href")
                if (title.isBlank() || href.isBlank()) return@mapNotNull null

                newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama)
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Sin título"
        val poster = doc.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        val episodes = doc.select("a[href*='/capitulo/']")
            .mapIndexed { index, element ->
                val epUrl = element.attr("href")
                newEpisode(fixUrl(epUrl)) {
                    this.name = element.text()
                    this.episode = index + 1
                }
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.AsianDrama,
            episodes
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = headers).document

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}

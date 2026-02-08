package com.telelibre

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets

class TeleLibre : MainAPI() {

    override var mainUrl = "https://tele-libre.fans"
    override var name = "Tele-Libre"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    // Cambiado a TvSeries para asegurar compatibilidad total con la librería base
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Canales de Televisión"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = document.select("img.w-28.h-28.object-contain.rounded")
            .mapNotNull { it.parent()?.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, false)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = select("img").attr("alt").ifEmpty { this.text() }.trim()
        val href = attr("href")
        val poster = select("img").attr("src")

        // Usamos newTvSeriesSearchResponse que es el método más estable
        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { 
            this.posterUrl = fixUrl(poster) 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(mainUrl).document
        return document.select("img.w-28.h-28.object-contain.rounded")
            .mapNotNull { it.parent()?.toSearchResult() }
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h3.mb-3")?.text()
            ?.replace("Estás viendo el Canal:", "", true)?.trim() ?: "Canal TV"
        
        val poster = document.selectFirst(".logoCanal img")?.attr("src")
        val description = document.selectFirst(".card-text")?.text()

        val episodes = listOf(
            newEpisode(url) { name = "Señal en Vivo" }
        )

        // Usamos la respuesta estándar de TvSeries para evitar errores de referencia
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrl(poster ?: "")
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        val videoLinks = mutableSetOf<String>()

        val regex = Regex("""(?:embed|r)\s*=\s*['"]([^'"]+)['"]""")
        regex.findAll(response).forEach { match ->
            val encodedData = match.groupValues[1]
            try {
                val decodedUrl = String(Base64.decode(encodedData, Base64.DEFAULT), StandardCharsets.UTF_8)
                if (decodedUrl.startsWith("http")) {
                    videoLinks.add(decodedUrl)
                }
            } catch (_: Exception) {}
        }

        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val link = it.groupValues[1]
            if (!link.contains("google") && !link.contains("adskeeper")) {
                videoLinks.add(link)
            }
        }

        videoLinks.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return videoLinks.isNotEmpty()
    }
}

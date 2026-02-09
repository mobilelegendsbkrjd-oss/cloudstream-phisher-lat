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
        // fixUrl para asegurar que la imagen cargue correctamente
        val poster = fixUrl(select("img").attr("src"))

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { 
            this.posterUrl = poster 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h3.mb-3")?.text()
            ?.replace("Estás viendo el Canal:", "", true)?.trim() ?: "Canal TV"
        
        val poster = fixUrl(document.selectFirst(".logoCanal img")?.attr("src") ?: "")
        val description = document.selectFirst(".card-text")?.text()

        // Agregamos todas las opciones de "embed" que aparezcan en el HTML
        val episodes = document.select("a.btn.btn-md[href*='embed']").map {
            newEpisode(fixUrl(it.attr("href"))) {
                this.name = it.text() // "Opción 1", "Opción 2", etc.
            }
        }.toMutableList()

        // Si no hay botones de opción, intentamos con el iframe principal
        if (episodes.isEmpty()) {
            document.selectFirst("iframe#iframe")?.attr("src")?.let {
                episodes.add(newEpisode(fixUrl(it)) { this.name = "Señal Principal" })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' ahora es la URL de un embed.php o similar
        val response = app.get(data).text
        val videoLinks = mutableSetOf<String>()

        // 1. Buscar el Base64 que vimos antes (por si acaso)
        val regex = Regex("""(?:embed|r)\s*=\s*['"]([^'"]+)['"]""")
        regex.findAll(response).forEach { match ->
            try {
                val decodedUrl = String(Base64.decode(match.groupValues[1], Base64.DEFAULT), StandardCharsets.UTF_8)
                if (decodedUrl.startsWith("http")) videoLinks.add(decodedUrl)
            } catch (_: Exception) {}
        }

        // 2. Buscar enlaces directos a reproductores conocidos o .m3u8 en el código del iframe
        Regex("""https?://[^\s'"]+\.(?:m3u8|mp4)""").findAll(response).forEach {
            videoLinks.add(it.value)
        }

        // 3. Buscar otros iframes dentro del iframe (anidación)
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val link = it.groupValues[1]
            if (!link.contains("google") && !link.contains("adskeeper")) {
                videoLinks.add(fixUrl(link))
            }
        }

        videoLinks.forEach { link ->
            // Cargamos el extractor enviando el 'mainUrl' como Referer para saltar bloqueos
            loadExtractor(link, mainUrl, subtitleCallback, callback)
        }

        return videoLinks.isNotEmpty()
    }
}

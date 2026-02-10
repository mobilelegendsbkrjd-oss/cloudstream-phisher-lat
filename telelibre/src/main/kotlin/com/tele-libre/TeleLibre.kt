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
        val episodes = mutableListOf<Episode>()

        // Primero, el iframe principal
        document.selectFirst("iframe#iframe")?.attr("src")?.let {
            episodes.add(newEpisode(fixUrl(it)) {
                this.name = "Señal Principal"
            })
        }

        // Luego, los botones de opciones
        document.select("a.btn.btn-md[href*='embed']").forEachIndexed { index, element ->
            val href = element.attr("href")
            val name = element.text().ifEmpty { "Opción ${index + 1}" }
            episodes.add(newEpisode(fixUrl(href)) {
                this.name = name
            })
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
        // 'data' es la URL de un embed.php o similar
        return try {
            extractEmbedChain(data, callback, subtitleCallback)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun extractEmbedChain(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        depth: Int = 0
    ): Boolean {
        // Prevenir recursión infinita
        if (depth > 5) return false

        val response = app.get(url, referer = "$mainUrl/").text

        // Buscar iframe en el embed actual
        val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val iframeMatch = iframeRegex.find(response)

        if (iframeMatch != null) {
            var iframeUrl = iframeMatch.groupValues[1]

            // Si la URL es relativa, hacerla absoluta
            if (!iframeUrl.startsWith("http")) {
                iframeUrl = fixUrl(iframeUrl)
            }

            // Verificar si el iframe tiene parámetros codificados en Base64
            if (iframeUrl.contains("get=")) {
                return extractFromEncodedIframe(iframeUrl, callback, subtitleCallback, depth)
            }

            // Si no tiene codificación, seguir la cadena
            return extractEmbedChain(iframeUrl, callback, subtitleCallback, depth + 1)
        }

        // Si no hay iframe, buscar video directamente
        return searchForVideo(response, url, callback, subtitleCallback)
    }

    private suspend fun extractFromEncodedIframe(
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        depth: Int
    ): Boolean {
        // Extraer el parámetro codificado
        val encodedParam = iframeUrl.substringAfter("get=").substringBefore("&")

        try {
            // Decodificar Base64
            val decoded = String(Base64.decode(encodedParam, Base64.DEFAULT), StandardCharsets.UTF_8)

            // Ahora necesitamos entender qué hacer con el texto decodificado
            // Podría ser un nombre de canal, una URL parcial, etc.
            // Vamos a buscar el siguiente paso en el archivo cvatt.html

            val baseUrl = iframeUrl.substringBefore("?")
            val nextUrl = iframeUrl // Mantener la misma URL para seguir la cadena

            // Obtener el contenido del archivo cvatt.html
            val cvattContent = app.get(baseUrl, referer = "$mainUrl/").text

            // Buscar scripts o variables JavaScript que contengan el stream
            return findStreamInScripts(cvattContent, decoded, iframeUrl, callback, subtitleCallback)

        } catch (e: Exception) {
            // Si falla la decodificación, intentar seguir la cadena normalmente
            return extractEmbedChain(iframeUrl, callback, subtitleCallback, depth + 1)
        }
    }

    private suspend fun findStreamInScripts(
        htmlContent: String,
        decodedParam: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        // Buscar en scripts JavaScript
        val scriptRegex = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
        val scripts = scriptRegex.findAll(htmlContent)

        for (scriptMatch in scripts) {
            val scriptContent = scriptMatch.groupValues[1]

            // Buscar URLs en el script
            val urlRegex = Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4|m3u))""")
            urlRegex.findAll(scriptContent).forEach { urlMatch ->
                val videoUrl = urlMatch.value
                loadExtractor(videoUrl, referer, subtitleCallback, callback)
                return true
            }

            // Buscar variables que podrían contener el stream
            val varRegex = Regex("""(?:src|url|file|source)\s*[=:]\s*['"]([^'"]+)['"]""")
            varRegex.findAll(scriptContent).forEach { varMatch ->
                val possibleUrl = varMatch.groupValues[1]
                if (possibleUrl.contains("m3u8") || possibleUrl.contains("mp4")) {
                    loadExtractor(possibleUrl, referer, subtitleCallback, callback)
                    return true
                }
            }
        }

        // Si no se encuentra en scripts, intentar con el parámetro decodificado
        // Podría ser que el parámetro sea el identificador del canal
        // Intentar construir una URL común
        val commonStreamUrls = listOf(
            "https://stream.tele-libre.fans/$decodedParam/playlist.m3u8",
            "https://cdn.tele-libre.fans/$decodedParam/index.m3u8",
            "https://live.tele-libre.fans/$decodedParam/master.m3u8",
            "https://$decodedParam.tele-libre.fans/stream.m3u8"
        )

        for (streamUrl in commonStreamUrls) {
            try {
                // Verificar si la URL existe
                app.get(streamUrl, referer = referer)
                loadExtractor(streamUrl, referer, subtitleCallback, callback)
                return true
            } catch (_: Exception) {
                continue
            }
        }

        return false
    }

    private suspend fun searchForVideo(
        htmlContent: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        var found = false

        // Buscar enlaces de video directos
        val videoRegex = Regex("""(https?://[^\s'"]+\.(?:m3u8|mp4|m3u))""")
        videoRegex.findAll(htmlContent).forEach { match ->
            val videoUrl = match.value
            loadExtractor(videoUrl, referer, subtitleCallback, callback)
            found = true
        }

        // Buscar en iframe src
        val iframeRegex = Regex("""src=["'](https?://[^"']+)["']""")
        iframeRegex.findAll(htmlContent).forEach { match ->
            val iframeUrl = match.groupValues[1]
            if (iframeUrl.contains("player") || iframeUrl.contains("embed") || iframeUrl.contains("stream")) {
                found = found || extractEmbedChain(iframeUrl, callback, subtitleCallback, 5)
            }
        }

        return found
    }
}
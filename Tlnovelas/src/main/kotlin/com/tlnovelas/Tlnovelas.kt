package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.google.gson.Gson

class Tlnovelas : MainAPI() {

    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url =
            if (page == 1) "$mainUrl/${request.data}"
            else "$mainUrl/${request.data}/page/$page"

        val document = app.get(url).document

        val home = document.select(".vk-poster, .ani-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("a")?.attr("title") ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")

        return newTvSeriesSearchResponse(
            title,
            fixUrl(href),
            TvType.TvSeries
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar/?q=$query"
        return app.get(url).document
            .select(".vk-poster, .ani-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: "Telenovela"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val episodes = document.select("a[href*='/ver/']")
            .map {
                val epUrl = it.attr("href")
                val epName = it.text()
                newEpisode(fixUrl(epUrl)) {
                    name = epName
                }
            }.distinctBy { it.data }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
        }
    }

    // -------------------------
    // DECODIFICADOR SIMPLE
    // -------------------------
    private fun decodeVideoUrl(encoded: String): String {
        return try {
            val parts = encoded.split("|")
            if (parts.size == 2) {
                val encodedStr = parts[0]
                val key = parts[1].toInt()
                val decoded = encodedStr.mapIndexed { i, c ->
                    (c.code - key - i).toChar()
                }.joinToString("")
                URLDecoder.decode(decoded, "UTF-8")
            } else encoded
        } catch (_: Exception) {
            encoded
        }
    }

    // -------------------------
    // LOAD LINKS
    // -------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val response = app.get(data).text
        val videoLinks = mutableSetOf<String>()

        // Buscar arrays e[]
        Regex("""e\[\d+]\s*=\s*['"]([^'"]+)['"]""")
            .findAll(response)
            .forEach {
                val decoded = decodeVideoUrl(it.groupValues[1])
                if (decoded.startsWith("http"))
                    videoLinks.add(decoded)
            }

        // Buscar urls directas
        Regex("""https?://[^"' ]+\.(m3u8|mp4)[^"' ]*""")
            .findAll(response)
            .forEach {
                videoLinks.add(it.value)
            }

        var success = false

        videoLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    success = true
                }
            } catch (_: Exception) {}
        }

        return success
    }

    // -------------------------
    // BYSEJIKUAR AES DECRYPT
    // -------------------------
    private fun decryptBysejikuar(
        iv: String,
        payload: String,
        keyParts: List<String>
    ): String? {
        return try {
            val decoder = Base64.getUrlDecoder()

            val ivBytes = decoder.decode(padBase64(iv))
            val payloadBytes = decoder.decode(padBase64(payload))
            val key = decoder.decode(padBase64(keyParts[0])) +
                    decoder.decode(padBase64(keyParts[1]))

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, ivBytes)
            val secretKey = SecretKeySpec(key, "AES")

            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            String(cipher.doFinal(payloadBytes), Charsets.UTF_8)

        } catch (_: Exception) {
            null
        }
    }

    private fun padBase64(s: String): String {
        var padded = s
        while (padded.length % 4 != 0) padded += "="
        return padded
    }
}

package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

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
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select(".vk-poster, .ani-card, .p-content, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home, true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = selectFirst(".ani-txt, .p-title, .vk-info p")?.text()
            ?: selectFirst("a")?.attr("title") ?: ""
        var href = selectFirst("a")?.attr("href") ?: ""
        val poster = selectFirst("img")?.attr("src")
        if (href.contains("/ver/")) {
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+|-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }
        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar/?q=$query"
        return app.get(url).document.select(".vk-poster, .ani-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")
        val finalDoc = if (url.contains("/ver/") && novelaLink != null) app.get(novelaLink).document else document
        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")?.text()
            ?.replace(Regex("(?i)Capitulos de|Ver"), "")?.trim() ?: "Telenovela"
        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: finalDoc.selectFirst(".ani-img img")?.attr("src")
        val episodes = finalDoc.select("a[href*='/ver/']").map {
            val epUrl = it.attr("href")
            val epName = it.text().replace(title, "", true)
                .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "").trim()
            newEpisode(epUrl) { name = if (epName.isEmpty()) "Capítulo" else "Capítulo $epName" }
        }.distinctBy { it.data }.reversed()
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = finalDoc.selectFirst(".card-text, .ani-description")?.text()
        }
    }

    private fun decodeVideoUrl(encoded: String): String {
        return try {
            val parts = encoded.split("|")
            if (parts.size == 2) {
                val encodedStr = parts[0]
                val key = parts[1].toInt()
                val decodedChars = mutableListOf<Char>()
                for (i in encodedStr.indices) {
                    val charCode = encodedStr[i].code - key - i
                    decodedChars.add(charCode.toChar())
                }
                val decodedString = decodedChars.joinToString("")
                URLDecoder.decode(decodedString, "UTF-8")
            } else {
                encoded
            }
        } catch (e: Exception) {
            encoded
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

        // ----------------------------------------------------------------
        // TU CÓDIGO ORIGINAL (intacto)
        // ----------------------------------------------------------------
        val regexJsArray = Regex("""e\[\d+\]\s*=\s*['"]([^'"]+)['"]""")
        regexJsArray.findAll(response).forEach { match ->
            val encodedUrl = match.groupValues[1]
            val decodedUrl = decodeVideoUrl(encodedUrl)
            if (decodedUrl.startsWith("http")) videoLinks.add(decodedUrl)
        }

        val regexVideFunc = Regex("""v_ideo\(([^)]+)\)""")
        regexVideFunc.findAll(response).forEach { match ->
            val param = match.groupValues[1]
            val arrayIndex = Regex("""e\[(\d+)\]""").find(param)?.groupValues?.get(1)?.toIntOrNull()
            if (arrayIndex != null) {
                val arrayRegex = Regex("""e\[$arrayIndex\]\s*=\s*['"]([^'"]+)['"]""")
                arrayRegex.find(response)?.let { arrayMatch ->
                    val encodedUrl = arrayMatch.groupValues[1]
                    val decodedUrl = decodeVideoUrl(encodedUrl)
                    if (decodedUrl.startsWith("http")) videoLinks.add(decodedUrl)
                }
            }
        }

        Regex("""var\s+e\s*=\s*\[([^\]]+)\]""").findAll(response).forEach { match ->
            match.groupValues[1].split(",")
                .map { it.trim().trim('\'', '"') }
                .filter { it.isNotEmpty() }
                .forEach { encodedUrl ->
                    val decodedUrl = decodeVideoUrl(encodedUrl)
                    if (decodedUrl.startsWith("http")) videoLinks.add(decodedUrl)
                }
        }

        val decodedPatterns = listOf(
            Regex("""https?://[^"'\s<>]+\.(mp4|m3u8|mkv|avi|mov|flv|wmv|webm)[^"'\s<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/video/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s<>]+/embed/[^"'\s<>]+""", RegexOption.IGNORE_CASE)
        )

        decodedPatterns.forEach { pattern ->
            pattern.findAll(response).forEach { match ->
                val url = match.value
                if (!url.contains("google") && !url.contains("adskeeper") && !url.contains("googletagmanager")) {
                    videoLinks.add(url)
                }
            }
        }

        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response).forEach {
            val link = it.groupValues[1]
            if (!link.contains("google") && !link.contains("adskeeper")) {
                videoLinks.add(link)
            }
        }

        var success = false
        videoLinks.forEach { link ->
            try {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    success = true
                }
            } catch (e: Exception) {}
        }

        // ----------------------------------------------------------------
        // FALLBACK: embeds con lógica adaptada (prioridad dooodster > luluvdo > bysejikuar)
        // ----------------------------------------------------------------
        if (!success) {
            val embeds = mutableListOf<String>()
            Regex("""e\[\d+\]\s*=\s*['"](https?://[^'"]+)['"]""").findAll(response).forEach {
                embeds.add(it.groupValues[1])
            }

            // Orden: dooodster primero, luego luluvdo, luego bysejikuar
            val sortedEmbeds = embeds.sortedBy { embed ->
                when {
                    embed.contains("dooodster.com") -> 0
                    embed.contains("luluvdo.com") -> 1
                    embed.contains("bysejikuar.com") || embed.contains("f75s.com") -> 2
                    else -> 3
                }
            }

            sortedEmbeds.forEach { embed ->
                try {
                    when {
                        embed.contains("dooodster.com") -> {
                            loadExtractor(embed, data, subtitleCallback, callback)
                            success = true
                        }
                        embed.contains("luluvdo.com") -> {
                            success = success || tryExtractLuluVdo(embed, data, subtitleCallback, callback)
                        }
                        embed.contains("bysejikuar.com") || embed.contains("f75s.com") -> {
                            success = success || tryExtractBysejikuar(embed, data, subtitleCallback, callback)
                        }
                        else -> {
                            loadExtractor(embed, data, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return success || videoLinks.isNotEmpty()
    }

    // ------------------------------------------------------------
    // Función para Luluvdo (regex simple)
    // ------------------------------------------------------------
    private suspend fun tryExtractLuluVdo(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val text = app.get(embedUrl, headers = mapOf("Referer" to referer)).text

            val source = Regex("""sources:\s*\[\{file:"(.*?)"\}""").find(text)?.groupValues?.get(1) ?: return false

            callback.invoke(
                ExtractorLink(
                    "LuluVdo",
                    "LuluVdo",
                    source,
                    referer,
                    Qualities.Unknown.value,
                    source.contains(".m3u8")
                )
            )

            val tracks = Regex("""tracks:\s*\[(.*?)\]""").find(text)?.groupValues?.get(1) ?: ""
            Regex("""file:\s*"([^"]+)",\s*label:\s*"([^"]+)"""").findAll(tracks).forEach {
                val file = it.groupValues[1]
                val label = it.groupValues[2]
                if (label.lowercase() != "upload captions") {
                    subtitleCallback(SubtitleFile(file, label))
                }
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    // ------------------------------------------------------------
    // Función para Bysejikuar / f75s (con challenge + attest simulado)
    // ------------------------------------------------------------
    private suspend fun tryExtractBysejikuar(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(embedUrl) ?: return false
            val linkType = matcher.groupValues[1]
            val videoId = matcher.groupValues[2]
            val base = embedUrl.substringBefore("/e/").substringBefore("/d/") + "/"

            // Headers comunes
            val headers = mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Referer" to referer,
                "X-Embed-Origin" to "ww2.tlnovelas.net",
                "X-Embed-Parent" to embedUrl
            )

            // 1. Details
            val detailsUrl = "$base/api/videos/$videoId/embed/details"
            val detailsText = app.get(detailsUrl, headers = headers).text
            val details = Gson().fromJson(detailsText, DetailsResponse::class.java)
            val embedFrame = details.embed_frame_url ?: return false

            headers["Referer"] = embedFrame

            // 2. Settings (opcional, pero lo hacemos)
            val settingsUrl = "$base/api/videos/$videoId/embed/settings"
            app.get(settingsUrl, headers = headers)

            // 3. Challenge (POST vacío)
            val challengeUrl = "$base/api/videos/access/challenge"
            app.post(challengeUrl, headers = headers, data = "")

            // 4. Attest con valores fijos de tus curls (hack temporal)
            val attestUrl = "$base/api/videos/access/attest"
            val attestBody = """
            {
              "viewer_id": "38b57136152c4fd0a647c157d61572ef",
              "device_id": "0VfTeyT4inw0PcQy23_N0w",
              "challenge_id": "BEsVYVdleRp81E_dmvUFpe7c",
              "nonce": "${java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32)}",
              "signature": "SFswckSRGi9Jdy5NxglcpWU6W0L-grT76sQMEwwKGIPtB0BnDme9po9rJP9aKl2P0Eyxt4Nb89UgvEiNQ8ndnQ",
              "public_key": {
                "crv": "P-256",
                "ext": true,
                "key_ops": ["verify"],
                "kty": "EC",
                "x": "FOzkZAAfk2LaVmRL-V7X0eoP0AoSIdSMA-45NRSURTw",
                "y": "uo-xYabtgV4IsUjNtfc1F_9JpN46j8hbPTI8ek127lw"
              },
              "client": {
                "user_agent": "${USER_AGENT}",
                "architecture": "x86",
                "bitness": "64",
                "platform": "Windows",
                "platform_version": "10.0.0",
                "model": "",
                "ua_full_version": "143.0.7499.170",
                "brand_full_versions": [
                  {"brand": "Google Chrome", "version": "143.0.7499.170"},
                  {"brand": "Chromium", "version": "143.0.7499.170"},
                  {"brand": "Not A(Brand", "version": "24.0.0.0"}
                ],
                "pixel_ratio": 1,
                "screen_width": 1366,
                "screen_height": 768,
                "color_depth": 24,
                "languages": ["es-419", "es"],
                "timezone": "America/Mexico_City",
                "hardware_concurrency": 16,
                "device_memory": 8,
                "touch_points": 0,
                "webgl_vendor": "Google Inc. (Intel)",
                "webgl_renderer": "ANGLE (Intel, Intel(R) UHD Graphics 770 (0x00004680) Direct3D11 vs_5_0 ps_5_0, D3D11)",
                "canvas_hash": "VgsEVJRdYWWbK0QvEoACf3PEkbidL9dadWKnNxWbvvw",
                "audio_hash": "RyBmlOc4cA7XhqmvkyO40eo8sOa5q-CFlrTnf70qADY",
                "pointer_type": "fine,hover",
                "extra": {"vendor": "Google Inc.", "appVersion": "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"}
              },
              "storage": {
                "cookie": "38b57136152c4fd0a647c157d61572ef",
                "indexed_db": "38b57136152c4fd0a647c157d61572ef:0VfTeyT4inw0PcQy23_N0w"
              },
              "attributes": {"entropy": "high"}
            }
            """.trimIndent()

            app.post(attestUrl, headers = headers + ("Content-Type" to "application/json"), data = attestBody)

            // 5. Playback
            val playbackUrl = "$base/api/videos/$videoId/embed/playback"
            val playbackText = app.post(playbackUrl, headers = headers + ("Content-Type" to "application/json"), data = """{"fingerprint":{"token":"eyJ2aWV3ZXJfaWQiOiIzOGI1NzEzNjE1MmM0ZmQwYTY0N2MxNTdkNjE1NzJlZiIsImRldmljZV9pZCI6IjBWZlRleVQ0aW53MFBjUXkyM19OMHciLCJjb25maWRlbmNlIjowLjkzLCJpYXQiOjE3NzE2MTM0NTYsImV4cCI6MTc3MTYxNDA1Nn0.qdHhJr-5Tz76uEZXQ_Ov3jIAm_lMYDynEUlC6V9vdzk","viewer_id":"38b57136152c4fd0a647c157d61572ef","device_id":"0VfTeyT4inw0PcQy23_N0w","confidence":0.93}}""").text
            val playback = Gson().fromJson(playbackText, PlaybackResponse::class.java).playback ?: return false

            val decryptedJson = decryptBysejikuar(playback) ?: return false
            val decrypted = Gson().fromJson(decryptedJson, DecryptedPlayback::class.java) ?: return false
            val sourceUrl = decrypted.sources?.firstOrNull()?.url ?: return false

            callback.invoke(
                ExtractorLink(
                    "Bysejikuar",
                    "Bysejikuar HLS",
                    sourceUrl,
                    "$base/",
                    Qualities.Unknown.value,
                    sourceUrl.contains(".m3u8")
                )
            )
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun decryptBysejikuar(data: PlaybackData): String? {
        try {
            val decoder = Base64.getUrlDecoder()
            val iv = decoder.decode(padBase64(data.iv))
            val payload = decoder.decode(padBase64(data.payload))
            val p1 = decoder.decode(padBase64(data.key_parts[0]))
            val p2 = decoder.decode(padBase64(data.key_parts[1]))
            val key = p1 + p2
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            return String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
    }

    private fun padBase64(s: String): String {
        var padded = s
        while (padded.length % 4 != 0) padded += "="
        return padded
    }

    // Modelos mínimos
    data class DetailsResponse(val embed_frame_url: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>
    )
    data class DecryptedPlayback(
        val sources: List<DecryptedSource>?
    )
    data class DecryptedSource(val url: String?)
}

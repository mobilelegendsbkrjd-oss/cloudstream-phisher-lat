package com.cablevision

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.regex.Pattern
import kotlin.math.pow

class CablevisionHd : MainAPI() {

    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "Cablevision"
    override val hasMainPage = true
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Live)

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
    )

    // ===============================
    // MAIN PAGE
    // ===============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(mainUrl, headers = baseHeaders).document

        val all = mutableListOf<SearchResponse>()
        val deportes = mutableListOf<SearchResponse>()
        val noticias = mutableListOf<SearchResponse>()
        val entretenimiento = mutableListOf<SearchResponse>()

        val channels = doc.select("a.channel-link")

        for (item in channels) {

            val title = item.selectFirst("img")?.attr("alt") ?: continue
            val link = fixUrl(item.attr("href"))
            val img = item.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            val response = newLiveSearchResponse(title, link, TvType.Live) {
                posterUrl = img
            }

            all.add(response)

            when {
                title.contains("espn", true) ||
                title.contains("fox", true) ||
                title.contains("tudn", true) -> deportes.add(response)

                title.contains("cnn", true) ||
                title.contains("noticias", true) -> noticias.add(response)

                else -> entretenimiento.add(response)
            }
        }

        return HomePageResponse(
            listOf(
                HomePageList("Todos", all),
                HomePageList("Deportes", deportes),
                HomePageList("Noticias", noticias),
                HomePageList("Entretenimiento", entretenimiento)
            )
        )
    }

    // ===============================
    // SEARCH
    // ===============================
    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get(mainUrl, headers = baseHeaders).document
        val results = mutableListOf<SearchResponse>()

        val channels = doc.select("a.channel-link")

        for (item in channels) {

            val title = item.selectFirst("img")?.attr("alt") ?: continue
            if (!title.contains(query, true)) continue

            val link = fixUrl(item.attr("href"))
            val img = item.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            results.add(
                newLiveSearchResponse(title, link, TvType.Live) {
                    posterUrl = img
                }
            )
        }

        return results
    }

    // ===============================
    // LOAD
    // ===============================
    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse(name, url, url)
    }

    // ===============================
    // LOAD LINKS (Taladro Streamflix)
    // ===============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var currentUrl = data
        var currentReferer = mainUrl
        val maxDepth = 5

        repeat(maxDepth) {

            val response = app.get(
                currentUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to currentReferer
                )
            )

            val html = response.text
            val document = response.document

            // 🔥 M3U8 DIRECTO
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                .find(html)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.let { url ->
                    callback(newExtractorLink(name, "Direct", url, ExtractorLinkType.M3U8))
                    return true
                }

            // 🔥 SCRIPT PACKER
            val packedRegex =
                Regex("""eval\(function\(p,a,c,k,e,d\).*?\)""", RegexOption.DOT_MATCHES_ALL)

            packedRegex.findAll(html).forEach { match ->
                try {
                    val unpacked = JsUnpacker(match.value).unpack() ?: ""
                    Regex("""https?://[^"']+\.m3u8[^"']*""")
                        .find(unpacked)
                        ?.groupValues?.get(0)
                        ?.replace("\\/", "/")
                        ?.let { url ->
                            callback(newExtractorLink(name, "Unpacked", url, ExtractorLinkType.M3U8))
                            return true
                        }
                } catch (_: Exception) {}
            }

            // 🔥 TRIPLE BASE64
            if (html.contains("atob")) {
                Regex("""atob\("([^"]+)""")
                    .find(html)
                    ?.groupValues?.get(1)
                    ?.let { encoded ->
                        try {
                            var decoded = encoded
                            repeat(3) {
                                decoded = String(Base64.decode(decoded, Base64.DEFAULT))
                            }
                            Regex("""https?://[^"]+\.m3u8[^"]*""")
                                .find(decoded)
                                ?.value
                                ?.let { url ->
                                    callback(newExtractorLink(name, "Decoded", url, ExtractorLinkType.M3U8))
                                    return true
                                }
                        } catch (_: Exception) {}
                    }
            }

            // 🔥 source:, file:, var src
            listOf(
                Regex("""source\s*:\s*["']([^"']+)["']"""),
                Regex("""file\s*:\s*["']([^"']+)["']"""),
                Regex("""var\s+src\s*=\s*["']([^"']+)["']""")
            ).forEach { regex ->
                regex.find(html)?.let {
                    val url = it.groupValues[1].replace("\\/", "/")
                    if (url.startsWith("http")) {
                        callback(
                            newExtractorLink(
                                name,
                                "Generic",
                                url,
                                if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        return true
                    }
                }
            }

            // 🔥 IFRAME PROFUNDIDAD
            val iframe = document.selectFirst("iframe")?.attr("src")
            if (!iframe.isNullOrEmpty()) {
                currentReferer = currentUrl
                currentUrl = fixUrl(iframe)
            } else {
                return false
            }
        }

        return false
    }
}

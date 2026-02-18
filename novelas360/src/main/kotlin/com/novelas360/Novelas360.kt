package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {

    override var mainUrl = "https://novelas360.cyou"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    private val chromeUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ===============================
    // Utils
    // ===============================

    private suspend fun getDoc(url: String): Document {
        return app.get(
            url,
            headers = mapOf(
                "User-Agent" to chromeUA,
                "Referer" to mainUrl
            )
        ).document
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    // ===============================
    // Main Page
    // ===============================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDoc("$mainUrl/telenovelas/mexico/")
        val items = document.select("div.item a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList("Telenovelas México", items)),
            false
        )
    }

    // ===============================
    // Search
    // ===============================

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query")

        return document.select(".video-item, div.item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst("h3")?.text() ?: return@mapNotNull null
            val img = item.selectFirst("img")
            val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })

            newTvSeriesSearchResponse(title, link.attr("href"), TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // ===============================
    // Load Series
    // ===============================

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)

        val title = doc.selectFirst("h1, h4 span")?.text() ?: "Novela"
        val poster = fixUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")

        val episodes = mutableListOf<Episode>()

        var pageCount = 1
        while (pageCount <= 50) {
            val pageUrl =
                if (pageCount == 1) url
                else "${url.trimEnd('/')}/page/$pageCount/"

            val pageDoc = try { getDoc(pageUrl) } catch (_: Exception) { null }
            val items = pageDoc?.select("div.item h3 a") ?: break
            if (items.isEmpty()) break

            items.forEach { el ->
                episodes.add(
                    newEpisode(el.attr("href")) {
                        this.name = el.text().trim()
                    }
                )
            }

            pageCount++
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.distinctBy { it.data }.reversed()
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ===============================
    // Load Links (PLAYER FLOW REAL)
    // ===============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val id = data.substringAfterLast("/")

        // STEP 1 - GET embed_player
        val embedResponse = app.get(
            "$mainUrl/player/embed_player.php?vid=$id&pop=0",
            headers = mapOf(
                "User-Agent" to chromeUA,
                "Referer" to "$mainUrl/e/$id",
                "Origin" to mainUrl
            )
        )

        val embedHtml = embedResponse.text

        // Extraer SH dinámico
        val sh = Regex("""["']sh["']\s*[:=]\s*["']([a-f0-9]+)["']""")
            .find(embedHtml)
            ?.groupValues?.get(1)
            ?: return false

        // Cookies necesarias (uid)
        val cookies = embedResponse.cookies

        // STEP 2 - POST get_md5
        val postBody = """
        {
          "htoken":"",
          "sh":"$sh",
          "ver":"4",
          "secure":"0",
          "adb":"96958",
          "v":"$id",
          "token":"",
          "gt":"",
          "embed_from":"0",
          "wasmcheck":0,
          "adscore":"",
          "click_hash":"",
          "clickx":0,
          "clicky":0
        }
        """.trimIndent()

        val md5Response = app.post(
            "$mainUrl/player/get_md5.php",
            headers = mapOf(
                "User-Agent" to chromeUA,
                "Referer" to "$mainUrl/e/$id",
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json"
            ),
            data = postBody,
            cookies = cookies
        )

        val responseText = md5Response.text

        // STEP 3 - Extraer m3u8
        val m3u8 = Regex("""https?:\/\/[^"]+\.m3u8[^"]*""")
            .find(responseText)
            ?.value
            ?: return false

        // STEP 4 - Enviar a reproductor
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                headers = mapOf(
                    "User-Agent" to chromeUA,
                    "Referer" to mainUrl
                ),
                type = ExtractorLinkType.M3U8
            )
        )

        return true
    }

    // ===============================
    // Search Result Mapper
    // ===============================

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank() || href.contains("/tag/")) return null

        val title = selectFirst("h3, h2")?.text()?.trim() ?: return null
        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }
}

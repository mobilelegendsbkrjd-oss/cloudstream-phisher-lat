package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    private val chromeUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDoc("$mainUrl/telenovelas/mexico/")
        val items = document.select("div.tabcontent#Todos > a, div.item a")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList("Telenovelas México", items)),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query")

        return document.select(".video-item, div.item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst("h3, .tabcontentnom")?.text() ?: return@mapNotNull null
            val img = item.selectFirst("img")
            val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })

            newTvSeriesSearchResponse(title, link.attr("href"), TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        val title = doc.selectFirst("h4 span, h1")?.text() ?: "Novela"
        val poster = fixUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))

        val allEpisodes = mutableListOf<Episode>()
        var pageCount = 1

        while (pageCount <= 50) {
            val currentUrl =
                if (pageCount == 1) url else "${url.trimEnd('/')}/page/$pageCount/"
            val pageDoc = try { getDoc(currentUrl) } catch (_: Exception) { null }

            val items = pageDoc?.select("div.item h3 a, .video-item h3 a") ?: emptyList()
            if (items.isEmpty()) break

            items.forEach { el ->
                allEpisodes.add(
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
            allEpisodes.distinctBy { it.data }.reversed()
        ) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val id = data.substringAfterLast("/")

        val embedRes = app.get(
            "$mainUrl/player/embed_player.php?vid=$id&pop=0",
            headers = mapOf(
                "Referer" to "$mainUrl/e/$id",
                "Origin" to mainUrl,
                "User-Agent" to chromeUA
            )
        )

        val embed = embedRes.text

        val sh = Regex("""["']sh["']\s*[:=]\s*["']([a-f0-9]+)["']""")
            .find(embed)
            ?.groupValues?.get(1)
            ?: return false

        val postData = mapOf(
            "htoken" to "",
            "sh" to sh,
            "ver" to "4",
            "secure" to "0",
            "adb" to "96958",
            "v" to id,
            "token" to "",
            "gt" to "",
            "embed_from" to "0",
            "wasmcheck" to "0",
            "adscore" to "",
            "click_hash" to "",
            "clickx" to "0",
            "clicky" to "0"
        )

        val md5Res = app.post(
            "$mainUrl/player/get_md5.php",
            data = postData,
            headers = mapOf(
                "Referer" to "$mainUrl/e/$id",
                "Origin" to mainUrl,
                "User-Agent" to chromeUA,
                "X-Requested-With" to "XMLHttpRequest"
            )
        )

        val body = md5Res.text

        val m3u8 = Regex("""https?:\/\/[^"]+\.m3u8[^"]*""")
            .find(body)
            ?.value
            ?: return false

        // ✅ versión compatible con TU API
        callback.invoke(
            newExtractorLink(
                "Novelas360",
                "Novelas360",
                m3u8,
                "$mainUrl/e/$id"
            )
        )

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank() || href.contains("/tag/")) return null

        val title =
            selectFirst(".tabcontentnom, h3, h2")?.text()?.trim() ?: return null

        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }
}

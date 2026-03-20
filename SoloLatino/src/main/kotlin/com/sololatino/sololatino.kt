package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*

class SoloLatino : MainAPI() {

    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private var lastServer: String? = null
    private var nextEpisodeUrl: String? = null

    // =========================
    // 🎨 UI DECORATOR
    // =========================
    private fun decorateTitle(title: String): String {
        val t = title.lowercase()

        return when {
            t.contains("netflix") -> "🟥 ɴᴇᴛꜰʟɪx"
            t.contains("amazon") -> "🟦 ᴀᴍᴀᴢᴏɴ ᴘʀɪᴍᴇ"
            t.contains("disney") -> "🟦 ᴅɪsɴᴇʏ ➕"
            t.contains("hbo") -> "🟪 ʜʙᴏ ᴍᴀx"
            t.contains("apple") -> "🍎 ᴀᴘᴘʟᴇ ᴛᴠ"
            t.contains("hulu") -> "🟩 ʜᴜʟᴜ"
            t.contains("paramount") -> "🏔️ ᴘᴀʀᴀᴍᴏᴜɴᴛ"

            t.contains("tokyo mx") -> "🌸 ᴛᴏᴋʏᴏ ᴍx 🔞"
            t.contains("tv tokio") -> "📺 ᴛᴠ ᴛᴏᴋɪᴏ"

            t.contains("pelicula") -> "🎬 ᴘᴇʟɪᴄᴜʟᴀs"
            t.contains("serie") -> "📺 sᴇʀɪᴇs"
            t.contains("anime") -> "🌸 ᴀɴɪᴍᴇ"

            else -> "🎬 $title"
        }
    }

    // =========================
    // MAIN PAGE
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(mainUrl).document

        val normal = mutableListOf<HomePageList>()
        val tokyo = mutableListOf<HomePageList>()

        doc.select("section").forEach { section ->

            val rawTitle = section.selectFirst("h2")?.text() ?: return@forEach

            if (
                rawTitle.contains("Últimos", true) ||
                rawTitle.contains("Recientes", true) ||
                rawTitle.contains("Añadidos", true)
            ) return@forEach

            val items = section.select(".card").mapNotNull { card ->

                val a = card.selectFirst("a") ?: return@mapNotNull null
                val link = fixUrl(a.attr("href"))

                val name = card.selectFirst(".card__title")?.text()
                    ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank {
                        it.attr("data-lazy-src").ifBlank {
                            it.attr("src")
                        }
                    }
                }

                val type = if (link.contains("/serie/"))
                    TvType.TvSeries else TvType.Movie

                newMovieSearchResponse(name, link, type) {
                    this.posterUrl = poster
                }
            }

            if (items.isEmpty()) return@forEach

            val list = HomePageList(decorateTitle(rawTitle), items)

            if (rawTitle.lowercase().contains("tokyo mx")) {
                tokyo.add(list)
            } else {
                normal.add(list)
            }
        }

        normal.addAll(tokyo)

        return newHomePageResponse(normal)
    }

    // =========================
    // LOAD
    // =========================
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("meta[property=og:title]")
            ?.attr("content") ?: "Sin título"

        val title = rawTitle
            .substringBefore("|")
            .replace(Regex("""^Ver\s+""", RegexOption.IGNORE_CASE), "")
            .replace("Latino", "", true)
            .replace(Regex("""\(\d{4}\)"""), "")
            .replace("Online", "", true)
            .trim()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("meta[name=description]")
            ?.attr("content") ?: ""

        val isAdult = doc.select(".detail-field span")
            .any {
                val t = it.text().lowercase()
                t.contains("adult") || t.contains("hentai") || t.contains("erotic")
            }

        val tags = mutableListOf<String>()
        if (isAdult) tags.add("🔞 Adultos")

        val eps = doc.select("a.ep-item")

        nextEpisodeUrl = eps
            .dropWhile { fixUrl(it.attr("href")) != url }
            .drop(1)
            .firstOrNull()
            ?.attr("href")
            ?.let { fixUrl(it) }

        val isSeries = url.contains("/serie/")

        return if (!isSeries) {

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }

        } else {

            val episodes = mutableListOf<Episode>()

            eps.forEach { ep ->

                val epUrl = fixUrl(ep.attr("href"))

                val num = ep.selectFirst(".ep-num")
                    ?.text()?.replace("E", "")?.toIntOrNull()

                val name = ep.selectFirst("p.text-sm")?.text()
                val thumb = ep.selectFirst("img")?.attr("src")

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = name
                        this.episode = num
                        this.posterUrl = thumb ?: poster
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // =========================
    // LINKS (FULL FIX)
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        val servers = mutableListOf<String>()

        // 🔥 NORMAL
        servers += doc.select("[data-server-btn]")
            .mapNotNull { it.attr("data-server-url") }

        // 🔥 ANIME onclick
        servers += doc.select("li[onclick]")
            .mapNotNull {
                Regex("""go_to_player\('([^']+)""")
                    .find(it.attr("onclick"))
                    ?.groupValues?.getOrNull(1)
            }

        if (servers.isEmpty()) return false

        val sorted = servers.sortedBy {
            if (lastServer != null && it.contains(lastServer!!)) 0 else 1
        }

        var isFirst = true

        sorted.forEach { url ->

            val fixedUrl = when {

                // 🔥 BASE64 decode
                url.contains("re.sololatino.net") -> {
                    Regex("""link=([^&]+)""")
                        .find(url)
                        ?.groupValues?.getOrNull(1)
                        ?.let {
                            try {
                                String(Base64.decode(it, Base64.DEFAULT))
                            } catch (_: Exception) { null }
                        } ?: url
                }

                else -> url
            }

            val serverName = fixedUrl.substringAfter("//").substringBefore("/")

            val cb: (ExtractorLink) -> Unit = { link ->

                lastServer = serverName

                if (isFirst) {
                    isFirst = false
                    callback.invoke(link)
                }

                callback.invoke(link)
            }

            when {
                fixedUrl.contains("embed69") -> {
                    Embed69Extractor.load(fixedUrl, data, subtitleCallback, cb)
                }

                else -> {
                    loadExtractor(fixedUrl, data, subtitleCallback, cb)
                }
            }
        }

        return true
    }
}
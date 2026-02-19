package com.ennovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class EnNovelas : MainAPI() {
    override var mainUrl = "https://tv.ennovelas.net"
    override var name = "EnNovelas"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/novelas-completas/page/" to "Populares"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get("$mainUrl/category/novelas-completas/page/$page").document
        val items = doc.select(".block-post").mapNotNull { element ->
            val href = element.select("a").attr("href")
            val title = element.select("a .title").text()
            val img = element.select("a img").attr("data-img")
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(img)
            }
        }
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val doc = app.get("$mainUrl/search/$id").document
            val title = doc.selectFirst("[itemprop=\"name\"] a")?.text() ?: ""
            val poster = doc.selectFirst("div.poster img")?.attr("src") ?: ""
            
            return listOf(
                newTvSeriesSearchResponse(title, "$mainUrl/search/$id", TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster)
                }
            )
        }

        val doc = app.get("$mainUrl/search/$query/page/1/").document
        return doc.select(".block-post").mapNotNull { element ->
            if (element.selectFirst("a")?.attr("href")?.contains("/series/") == true) {
                val href = element.select("a").attr("href")
                val title = element.select("a .title").text()
                val img = element.select("a img").attr("data-img")
                
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(img)
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("[itemprop=\"name\"] a")?.text() ?: ""
        val description = doc.selectFirst(".postDesc .post-entry div")?.text() ?: title
        val genre = doc.select("ul.postlist li:nth-child(1) span a").joinToString { it.text() }
        val statusText = doc.select("ul.postlist li:nth-child(8) .getMeta span a").text().trim()
        val poster = doc.selectFirst("div.poster img")?.attr("src") ?: ""

        // Obtener episodios
        val episodes = getEpisodeListFromDocument(doc, url)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.tags = listOfNotNull(genre.takeIf { it.isNotEmpty() })
            this.year = parseStatus(statusText)
        }
    }

    private suspend fun getEpisodeListFromDocument(document: Document, baseUrl: String): List<Episode> {
        val episodeList = mutableListOf<Episode>()
        val seasonIds = document.select(".listSeasons li[data-season]")
        var noEp = 1F

        if (seasonIds.isNotEmpty()) {
            seasonIds.reversed().forEach { seasonElement ->
                try {
                    val headers = mapOf(
                        "authority" to mainUrl.replace("https://", ""),
                        "referer" to baseUrl,
                        "accept" to "*/*",
                        "accept-language" to "es-MX,es;q=0.9,en;q=0.8",
                        "x-requested-with" to "XMLHttpRequest",
                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )

                    val season = getNumberFromEpsString(seasonElement.text())
                    
                    val seasonDoc = app.get(
                        "$mainUrl/wp-content/themes/vo2022/temp/ajax/seasons.php?seriesID=${seasonElement.attr("data-season")}",
                        headers = headers
                    ).document

                    seasonDoc.select(".block-post").forEach { element ->
                        val epUrl = element.selectFirst("a")?.attr("href") ?: return@forEach
                        val noEpisode = getNumberFromEpsString(
                            element.selectFirst("a .episodeNum span:nth-child(2)")?.text() ?: ""
                        ).ifEmpty { noEp.toString() }

                        val ep = newEpisode(epUrl) {
                            name = "T$season - E$noEpisode - Cap" + 
                                (element.selectFirst("a .title")?.text()?.substringAfter("Cap") ?: "")
                            episode = noEpisode.toIntOrNull() ?: noEp.toInt()
                            season = season.toIntOrNull() ?: 1
                        }
                        episodeList.add(ep)
                        noEp += 1
                    }
                } catch (_: Exception) {}
            }
        } else {
            document.select(".block-post").forEach { element ->
                val epUrl = element.selectFirst("a")?.attr("href") ?: return@forEach
                val noEpisode = getNumberFromEpsString(
                    element.selectFirst("a .episodeNum span:nth-child(2)")?.text() ?: ""
                )
                
                val ep = newEpisode(epUrl) {
                    name = "Cap" + (element.selectFirst("a .title")?.text()?.substringAfter("Cap") ?: "")
                    episode = noEpisode.toIntOrNull() ?: 1
                }
                episodeList.add(ep)
            }
        }
        
        return episodeList.reversed()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        try {
            val doc = app.get(data).document
            val form = doc.selectFirst("#btnServers form") ?: return@coroutineScope false
            
            val urlRequest = form.attr("action")
            val watch = form.selectFirst("input")?.attr("value") ?: return@coroutineScope false
            val domainUrl = urlRequest.substringBefore("/wp-content")

            val body = mapOf(
                "watch" to watch,
                "submit" to ""
            )

            val headers = mapOf(
                "authority" to domainUrl.replace("https://", ""),
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "accept-language" to "es-MX,es;q=0.9,en;q=0.8",
                "content-type" to "application/x-www-form-urlencoded",
                "origin" to mainUrl,
                "referer" to "$mainUrl/",
                "upgrade-insecure-requests" to "1"
            )

            val postDoc = app.post(urlRequest, headers = headers, data = body).document

            postDoc.select(".serversList li").forEach { element ->
                async {
                    try {
                        val frameString = element.attr("abs:data-server")
                        var link = frameString.substringAfter("src='").substringBefore("'")
                            .replace("https://api.mycdn.moe/sblink.php?id=", "https://streamsb.net/e/")
                            .replace("https://api.mycdn.moe/uqlink.php?id=", "https://uqload.co/embed-")

                        if (link.contains("uqload") && !link.contains(".html")) {
                            link = "$link.html"
                        }
                        
                        loadExtractor(link, data, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }.awaitAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@coroutineScope true
    }

    private fun parseStatus(statusString: String): Int? {
        return when {
            statusString.contains("Continuous") -> 1
            statusString.contains("Finished") -> 2
            else -> null
        }
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}

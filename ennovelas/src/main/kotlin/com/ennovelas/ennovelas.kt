package com.ennovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class EnNovelas : MainAPI() {
    override var mainUrl = "https://tv.ennovelas.net"
    override var name = "EnNovelas"
    override var lang = "es"
    override val hasMainPage = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // Preferencias
    private var preferredQuality = "Voex"

    override var mainPage = mainPageOf(
        "$mainUrl/category/novelas-completas/page/" to "Populares"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get("$mainUrl/category/novelas-completas/page/$page").document
        val homes = ArrayList<HomePageList>()
        
        val items = doc.select(popularAnimeSelector()).mapNotNull { element ->
            popularAnimeFromElement(element)
        }
        
        homes.add(HomePageList(request.name, items))
        return HomePageResponse(homes)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            val doc = app.get("$mainUrl/search/$id").document
            val anime = animeDetailsParse(doc)
            listOf(
                newTvSeriesSearchResponse(
                    anime.name,
                    "$mainUrl/search/$id",
                    TvType.TvSeries,
                    true
                ) {
                    this.posterUrl = anime.posterUrl
                }
            )
        } else {
            val doc = app.get("$mainUrl/search/$query/page/1/").document
            doc.select(popularAnimeSelector()).mapNotNull { element ->
                if (element.selectFirst("a")?.attr("href")?.contains("/series/") == true) {
                    popularAnimeFromElement(element)
                } else null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val anime = animeDetailsParse(doc)
        
        // Obtener episodios
        val episodes = getEpisodeListFromDocument(doc, url)
        
        return newTvSeriesLoadResponse(anime.name, url, TvType.TvSeries, episodes) {
            posterUrl = anime.posterUrl
            backgroundPosterUrl = anime.backgroundPosterUrl
            plot = anime.plot
            tags = anime.tags
            year = anime.year
            rating = anime.rating
        }
    }

    private suspend fun getEpisodeListFromDocument(document: Document, baseUrl: String): List<Episode> {
        val episodeList = mutableListOf<Episode>()
        val seasonIds = document.select(".listSeasons li[data-season]")
        var noEp = 1F

        if (seasonIds.any()) {
            seasonIds.reversed().map { seasonElement ->
                try {
                    val headers = Headers.Builder()
                        .add("authority", mainUrl.substringAfter("https://"))
                        .add("referer", baseUrl)
                        .add("accept", "*/*")
                        .add("accept-language", "es-MX,es;q=0.9,en;q=0.8")
                        .add("sec-ch-ua", "\"Google Chrome\";v=\"119\", \"Chromium\";v=\"119\", \"Not?A_Brand\";v=\"24\"")
                        .add("sec-ch-ua-mobile", "?0")
                        .add("sec-ch-ua-platform", "\"Windows\"")
                        .add("sec-fetch-dest", "empty")
                        .add("sec-fetch-mode", "cors")
                        .add("sec-fetch-site", "same-origin")
                        .add("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                        .add("x-requested-with", "XMLHttpRequest")
                        .build()

                    val season = getNumberFromEpsString(seasonElement.text())
                    val tmpClient = network.client.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(35, TimeUnit.SECONDS)
                        .readTimeout(35, TimeUnit.SECONDS)
                        .build()

                    val seasonDoc = app.get(
                        "$mainUrl/wp-content/themes/vo2022/temp/ajax/seasons.php?seriesID=${seasonElement.attr("data-season")}",
                        headers = headers,
                        client = tmpClient
                    ).document

                    seasonDoc.select(".block-post").forEach { element ->
                        val ep = Episode()
                        val noEpisode = getNumberFromEpsString(
                            element.selectFirst("a .episodeNum span:nth-child(2)")?.text() ?: ""
                        ).ifEmpty { noEp.toString() }

                        ep.url = element.selectFirst("a")?.attr("href") ?: return@forEach
                        ep.name = "T$season - E$noEpisode - Cap" + 
                            (element.selectFirst("a .title")?.text()?.substringAfter("Cap") ?: "")
                        ep.episode = noEp.toIntOrNull() ?: noEp.toInt()
                        ep.season = season.toIntOrNull() ?: 1
                        episodeList.add(ep)
                        noEp += 1
                    }
                } catch (_: Exception) {}
            }
        } else {
            document.select(".block-post").forEach { element ->
                val ep = Episode()
                val noEpisode = getNumberFromEpsString(
                    element.selectFirst("a .episodeNum span:nth-child(2)")?.text() ?: ""
                )
                ep.url = element.selectFirst("a")?.attr("href") ?: return@forEach
                ep.name = "Cap" + (element.selectFirst("a .title")?.text()?.substringAfter("Cap") ?: "")
                ep.episode = noEpisode.toFloat().toIntOrNull() ?: 1
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
        val doc = app.get(data).document
        val videoList = mutableListOf<ExtractorLink>()

        try {
            val form = doc.selectFirst("#btnServers form") ?: return@coroutineScope false
            val urlRequest = form.attr("action")
            val watch = form.selectFirst("input")?.attr("value") ?: return@coroutineScope false
            val domainRegex = Regex("^(?:https?:\\/\\/)?(?:[^@\\/\\n]+@)?(?:www\\.)?([^:\\/?\\n]+)")
            val domainUrl = domainRegex.findAll(urlRequest).firstOrNull()?.value ?: ""

            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val body = "watch=$watch&submit=".toRequestBody(mediaType)

            val headers = Headers.Builder()
                .add("authority", domainUrl.substringAfter("//"))
                .add("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .add("accept-language", "es-MX,es;q=0.9,en;q=0.8")
                .add("content-type", "application/x-www-form-urlencoded")
                .add("origin", mainUrl)
                .add("referer", "$mainUrl/")
                .add("sec-ch-ua-mobile", "?0")
                .add("upgrade-insecure-requests", "1")
                .build()

            val postDoc = app.post(urlRequest, headers = headers, data = body).document

            val extractorJobs = postDoc.select(".serversList li").map { element ->
                async {
                    try {
                        val frameString = element.attr("abs:data-server")
                        var link = frameString.substringAfter("src='").substringBefore("'")
                            .replace("https://api.mycdn.moe/sblink.php?id=", "https://streamsb.net/e/")
                            .replace("https://api.mycdn.moe/uqlink.php?id=", "https://uqload.co/embed-")

                        when {
                            link.contains("ok.ru") -> {
                                loadExtractor(link, data, subtitleCallback) { callback(it) }
                            }
                            link.contains("vidmoly") -> {
                                getVidmolyVideo(link, data, callback)
                            }
                            link.contains("voe") -> {
                                loadExtractor(link, data, subtitleCallback) { callback(it) }
                            }
                            link.contains("vudeo") -> {
                                loadExtractor(link, data, subtitleCallback) { callback(it) }
                            }
                            link.contains("streamtape") -> {
                                loadExtractor(link, data, subtitleCallback) { callback(it) }
                            }
                            link.contains("uqload") -> {
                                val finalLink = if (link.contains(".html")) link else "$link.html"
                                loadExtractor(finalLink, data, subtitleCallback) { callback(it) }
                            }
                            link.contains("dood") -> {
                                loadExtractor(link, data, subtitleCallback) { callback(it) }
                            }
                            link.contains("streamlare") -> {
                                loadExtractor(link, data, subtitleCallback) { callback(it) }
                            }
                            link.contains("dailymotion") -> {
                                loadExtractor(link, data, subtitleCallback) { callback(it) }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            extractorJobs.awaitAll()
        } catch (e: Exception) {}

        return@coroutineScope true
    }

    private suspend fun getVidmolyVideo(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val body = app.get(url).text
            val playlistUrl = Regex("file:\"(\\S+?)\"").find(body)?.groupValues?.get(1) ?: return
            val headers = Headers.Builder().add("Referer", "https://vidmoly.to").build()
            val playlistData = app.get(playlistUrl, headers = headers).text

            val separator = "#EXT-X-STREAM-INF:"
            playlistData.substringAfter(separator).split(separator).forEach { part ->
                try {
                    val quality = part.substringAfter("RESOLUTION=")
                        .substringAfter("x")
                        .substringBefore(",") + "p"
                    val videoUrl = part.substringAfter("\n").substringBefore("\n")
                    
                    callback.invoke(
                        ExtractorLink(
                            "Vidmoly",
                            "Vidmoly - $quality",
                            videoUrl,
                            referer,
                            Qualities.byTag(quality.replace("p", "")) ?: Qualities.Unknown.value,
                            headers = headers
                        )
                    )
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun popularAnimeSelector(): String = ".block-post"

    private fun popularAnimeFromElement(element: Element): SearchResponse {
        val href = element.select("a").attr("href")
        val title = element.select("a .title").text()
        val img = element.select("a img").attr("data-img")
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(img)
        }
    }

    private fun animeDetailsParse(document: Document): TvSeriesLoadResponse {
        val title = document.selectFirst("[itemprop=\"name\"] a")?.text() ?: ""
        val description = document.selectFirst(".postDesc .post-entry div")?.text() ?: title
        val genre = document.select("ul.postlist li:nth-child(1) span a").joinToString { it.text() }
        val status = parseStatus(document.select("ul.postlist li:nth-child(8) .getMeta span a").text().trim())
        val artist = document.selectFirst(".postInfo > .getMeta > span:nth-child(2) > a")?.text() ?: ""
        val author = document.selectFirst("ul.postlist li:nth-child(3) span a")?.text() ?: ""
        
        return TvSeriesLoadResponse(
            name = title,
            url = document.location(),
            type = TvType.TvSeries,
            episodes = emptyList()
        ).apply {
            plot = description
            tags = listOfNotNull(
                genre.takeIf { it.isNotEmpty() },
                "Artista: $artist".takeIf { artist.isNotEmpty() },
                "Autor: $author".takeIf { author.isNotEmpty() }
            )
            if (status != -1) {
                this.year = status
            }
        }
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Continuous") -> 1 // ONGOING
            statusString.contains("Finished") -> 2 // COMPLETED
            else -> -1 // UNKNOWN
        }
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun getUrlPrefix(): String {
        return "ennovelas"
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
package com.ennovelas
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
class EnNovelas : MainAPI() {
override var mainUrl = "https://l.ennovelas-tv.com"
override var name = "EnNovelas"
override var lang = "es"
override val hasMainPage = true
override val hasChromecastSupport = true
override val hasDownloadSupport = true
override val supportedTypes = setOf(
TvType.TvSeries,
TvType.Movie
)
override val mainPage = mainPageOf(
"$mainUrl/episodes" to "Últimos Capítulos",
"$mainUrl/series" to "Series",
"$mainUrl/movies" to "Películas"
)
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
val doc = app.get(request.data).document
val items = doc.select(".block-post").mapNotNull { element ->
val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
val title = element.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
val img = element.selectFirst("img")?.attr("data-img")
?.ifEmpty { element.selectFirst("img")?.attr("src") }
?: ""
newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
posterUrl = fixUrlNull(img)
}
}
return newHomePageResponse(listOf(HomePageList(request.name, items)))
}
override suspend fun search(query: String): List<SearchResponse> {
val doc = app.get("$mainUrl/?s=$query").document
return doc.select(".block-post").mapNotNull { element ->
val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
val title = element.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
val img = element.selectFirst("img")?.attr("data-img")
?.ifEmpty { element.selectFirst("img")?.attr("src") } ?: ""
newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
posterUrl = fixUrlNull(img)
}
}
}
override suspend fun load(url: String): LoadResponse? {
val doc = app.get(url).document
val title = doc.selectFirst("h1")?.ownText()?.trim() ?: return null
val description = doc.selectFirst(".postDesc, .post-entry, .story")?.text()?.trim() ?: ""
val poster = doc.selectFirst("img.imgLoaded, img[alt*='poster'], .poster img")
?.attr("data-img")?.ifEmpty { doc.selectFirst("img")?.attr("src") } ?: ""
val isMovie = url.contains("/movies/") || url.contains("/pelicula/")
if (isMovie) {
return newMovieLoadResponse(title, url, TvType.Movie, url) {
posterUrl = fixUrlNull(poster)
plot = description
}
}
val episodes = doc.select("ul.eplist a.epNum").mapIndexed { index, a ->
val epUrl = fixUrl(a.attr("href"))
val epName = a.selectFirst("span")?.text()?.trim() ?: "Episodio ${index + 1}"
newEpisode(epUrl) {
name = epName
episode = index + 1
season = 1
}
}
return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
posterUrl = fixUrlNull(poster)
plot = description
}
}
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean = coroutineScope {
    // 1. Página del episodio
    val episodeDoc = app.get(data, headers = commonHeaders).document

    // 2. Encontrar botón "Ver Capítulo"
    val proxyUrl = episodeDoc.selectFirst("a[href*='a.poiw.online/enn.php?post=']")?.attr("href")
        ?: return@coroutineScope false

    // 3. Cargar la página intermedia (proxy) con referer del episodio → simula click
    val proxyResponse = app.get(proxyUrl, referer = data, headers = commonHeaders, timeout = 20)
    if (!proxyResponse.isSuccessful || proxyResponse.text.contains("home") || proxyResponse.text.contains("blog")) {
        // Si redirige a home, falló la simulación
        return@coroutineScope false
    }
    val proxyDoc = proxyResponse.document

    // 4. Extraer iframes o embeds reales del proxy (aquí está el reproductor)
    val iframes = proxyDoc.select("iframe[src]").map { it.attr("abs:src") }.filter { it.isNotBlank() }
    val scriptsWithUrl = proxyDoc.select("script").mapNotNull { script ->
        script.data().takeIf { it.contains("url =") || it.contains("file:") || it.contains(".m3u8") || it.contains(".mp4") }
            ?.let { data ->
                data.substringAfter("url = '").substringBefore("'")
                    .ifBlank { data.substringAfter("file: \"").substringBefore("\"") }
                    .ifBlank { null }
            }
    }

    val allSources = (iframes + scriptsWithUrl).distinct().filter { 
        it.contains("embed") || it.contains("video_ext") || it.contains(".mp4") || it.contains(".m3u8") 
    }

    if (allSources.isEmpty()) return@coroutineScope false

    var found = false

    allSources.forEach { sourceUrl ->
        val cleanSource = sourceUrl.replace("\\/", "/")
            .replace("uqload.net", "uqload.to")
            .replace("vidspeeds.com", "vidsspeeds.com")

        // 5. Resolver con nativos, referer = proxy (crucial)
        val resolved = loadExtractor(
            url = cleanSource,
            referer = proxyUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        if (resolved) found = true
    }

    return@coroutineScope found
}
}


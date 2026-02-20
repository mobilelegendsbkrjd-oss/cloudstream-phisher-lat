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
val episodeDoc = app.get(data).document
// Encontrar el botón "Ver Capítulo"
val proxyUrl = episodeDoc.selectFirst("a[href*='a.poiw.online/enn.php?post=']")?.attr("href")
?: return@coroutineScope false
// Cargar la página del proxy con referer = episodio (simula click)
val proxyDoc = app.get(proxyUrl, referer = data).document
// Extraer todos los iframes de la página del proxy
val embeds = proxyDoc.select("iframe").mapNotNull { it.attr("src") }.filter { it.isNotBlank() }
if (embeds.isEmpty()) return@coroutineScope false
var found = false
embeds.forEachIndexed { index, embedUrl ->
// Resolver cada iframe/embed con referer = proxyUrl
val resolved = loadExtractor(
url = embedUrl,
referer = proxyUrl,
subtitleCallback = subtitleCallback,
callback = callback
)
if (resolved) found = true
// Fallback si no resuelve
if (!resolved) {
callback(
newExtractorLink(
source = "Embed $index",
name = "Directo $index",
url = embedUrl
) {
this.referer = proxyUrl
this.quality = Qualities.Unknown.value
}
)
found = true
}
}
return@coroutineScope found
}
}

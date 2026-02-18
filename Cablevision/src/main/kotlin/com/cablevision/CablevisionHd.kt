package com.cablevision

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CablevisionHd : MainAPI() {

    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "Cablevision"
    override val hasMainPage = true
    override var lang = "mx"
    override val supportedTypes = setOf(TvType.Live)

    private val headers = mapOf(
        "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    // ===============================
    // CATEGORÍAS EXACTAS DEL CÓDIGO VIEJO
    // ===============================

    private val deportesCat = setOf(
        "TUDN","WWE","Afizzionados","Gol Perú","Gol TV","TNT SPORTS",
        "Fox Sports Premium","TYC Sports","Movistar Deportes (Perú)",
        "Movistar La Liga","Movistar Liga De Campeones","Dazn F1",
        "Dazn La Liga","Bein La Liga","Bein Sports Extra",
        "Directv Sports","Directv Sports 2","Directv Sports Plus",
        "Espn Deportes","Espn Extra","Espn Premium","Espn","Espn 2",
        "Espn 3","Espn 4","Espn Mexico","Espn 2 Mexico","Espn 3 Mexico",
        "Fox Deportes","Fox Sports","Fox Sports 2","Fox Sports 3",
        "Fox Sports Mexico","Fox Sports 2 Mexico","Fox Sports 3 Mexico"
    )

    private val entretenimientoCat = setOf(
        "Telefe","El Trece","Televisión Pública","Telemundo Puerto rico",
        "Univisión","Univisión Tlnovelas","Pasiones","Caracol","RCN",
        "Latina","America TV","Willax TV","ATV","Las Estrellas",
        "Tl Novelas","Galavision","Azteca 7","Azteca Uno",
        "Canal 5","Distrito Comedia"
    )

    private val noticiasCat = setOf("Telemundo 51")

    private val peliculasCat = setOf(
        "Movistar Accion","Movistar Drama","Universal Channel","TNT",
        "TNT Series","Star Channel","Star Action","Star Series",
        "Cinemax","Space","Syfy","Warner Channel",
        "Warner Channel (México)","Cinecanal","FX","AXN",
        "AMC","Studio Universal","Multipremier","Golden",
        "Golden Plus","Golden Edge","Golden Premier",
        "Golden Premier 2","Sony","DHE","NEXT HD"
    )

    private val infantilCat = setOf(
        "Cartoon Network","Tooncast","Cartoonito",
        "Disney Channel","Disney JR","Nick"
    )

    private val educacionCat = setOf(
        "Discovery Channel","Discovery World","Discovery Theater",
        "Discovery Science","Discovery Familia",
        "History","History 2","Animal Planet",
        "Nat Geo","Nat Geo Mundo"
    )

    private val dos47Cat = setOf("24/7")

    // ===============================
    // MAIN PAGE CON CATEGORÍAS REALES
    // ===============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(mainUrl, headers = headers).document
        val categorias = linkedMapOf<String, MutableList<SearchResponse>>()

        val channels = doc.select("a.channel-link")

        for (item in channels) {

            val title = item.selectFirst("img")?.attr("alt") ?: continue
            val link = item.attr("href")
            val img = item.selectFirst("img")?.attr("src")

            val response = newLiveSearchResponse(
                title,
                fixUrl(link),
                TvType.Live
            ) {
                posterUrl = img?.let { fixUrl(it) }
            }

            val categoria = when {
                deportesCat.any { title.equals(it, true) } -> "Deportes"
                entretenimientoCat.any { title.equals(it, true) } -> "Entretenimiento"
                noticiasCat.any { title.equals(it, true) } -> "Noticias"
                peliculasCat.any { title.equals(it, true) } -> "Películas"
                infantilCat.any { title.equals(it, true) } -> "Infantil"
                educacionCat.any { title.equals(it, true) } -> "Educación"
                dos47Cat.any { title.contains(it, true) } -> "24/7"
                else -> "Otros"
            }

            categorias.getOrPut(categoria) { mutableListOf() }.add(response)
        }

        return HomePageResponse(
            categorias.map { HomePageList(it.key, it.value) }
        )
    }

    // ===============================
    // SEARCH
    // ===============================
    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get(mainUrl, headers = headers).document
        val results = mutableListOf<SearchResponse>()

        val channels = doc.select("a.channel-link")

        for (item in channels) {

            val title = item.selectFirst("img")?.attr("alt") ?: continue
            if (!title.contains(query, true)) continue

            val link = item.attr("href")
            val img = item.selectFirst("img")?.attr("src")

            results.add(
                newLiveSearchResponse(
                    title,
                    fixUrl(link),
                    TvType.Live
                ) {
                    posterUrl = img?.let { fixUrl(it) }
                }
            )
        }

        return results
    }

    // ===============================
    // LOAD
    // ===============================
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("img")?.attr("alt")
            ?: "Live"

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        )
    }

    // ===============================
    // LOAD LINKS (EXTRACTOR FUNCIONAL)
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
                    "User-Agent" to headers["User-Agent"]!!,
                    "Referer" to currentReferer
                )
            )

            val html = response.text
            val document = response.document

            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                .find(html)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.let { url ->
                    callback(
                        newExtractorLink(
                            name,
                            "Live",
                            url,
                            ExtractorLinkType.M3U8
                        ) { quality = Qualities.Unknown.value }
                    )
                    return true
                }

            val iframeSrc = document.select("iframe").attr("src")

            if (iframeSrc.isNotEmpty()) {

                val nextUrl =
                    if (iframeSrc.startsWith("http"))
                        iframeSrc
                    else
                        fixUrl(iframeSrc)

                if (nextUrl == currentUrl) return false

                currentReferer = currentUrl
                currentUrl = nextUrl
                return@repeat
            }

            return false
        }

        return false
    }
}

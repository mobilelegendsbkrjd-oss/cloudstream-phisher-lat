package com.cablevision

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CablevisionHdProvider : MainAPI() {
    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "CablevisionHd"
    override var lang = "es"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
    
    private val headers = mapOf(
        "User-Agent" to USER_AGENT
    )

    // Lista de canales a excluir
    val nowAllowed = setOf("Únete al chat", "Donar con Paypal", "Lizard Premium", "Mundo Latam", "Donacion", "Red Social")

    // Categorías actualizadas basadas en los canales del HTML
    val deportesCat = setOf(
        "TUDN", "WWE", "Afizzionados", "Gol Perú", "Gol TV", "TNT SPORTS",
        "Fox Sports Premium", "TYC Sports", "Movistar Deportes", "Movistar La Liga",
        "Movistar Liga De Campeones", "Dazn F1", "Dazn La Liga", "Bein Sports Extra",
        "Directv Sports", "Directv Sports 2", "Directv Sports Plus",
        "Espn Premium", "Espn", "Espn 2", "Espn 3", "Espn 4", "Espn 5", "Espn 6", "Espn 7",
        "Espn Mexico", "Espn 2 Mexico", "Espn 3 Mexico", "Fox Deportes", "Fox Sports",
        "Fox Sports 2", "Fox Sports 3", "Fox Sports Mexico", "Fox Sports 2 Mexico", "Fox Sports 3 Mexico"
    )
    
    val entretenimientoCat = setOf(
        "Telefe", "El Trece", "Telemundo 51", "Telemundo Puerto rico", "Univisión",
        "Pasiones", "Caracol", "RCN", "Latina", "America TV",
        "Willax TV", "ATV", "Las Estrellas", "Tl Novelas", "Galavision", "Azteca 7",
        "Azteca Uno", "Canal 5", "Distrito Comedia"
    )
    
    val noticiasCat = setOf("Telemundo 51")
    
    val peliculasCat = setOf(
        "Movistar Accion", "Movistar Drama", "Universal Channel", "TNT", "TNT Series",
        "Star Channel", "Star Action", "Star Series", "Cinemax", "Space", "Syfy",
        "Warner Channel", "Warner Channel Mexico", "Cinecanal", "FX", "AXN", "AMC",
        "Studio Universal", "Multipremier", "Golden Plus", "Golden Edge",
        "Golden Premier", "Golden Premier 2", "Canal Sony", "DHE", "NEXT HD"
    )
    
    val infantilCat = setOf("Cartoon Network", "Tooncast", "Cartoonito", "Disney Channel", "Disney JR", "Nick")
    
    val educacionCat = setOf(
        "Discovery Channel", "Discovery World", "Discovery Theater", "Discovery Science",
        "Discovery Familia", "History", "History 2", "Animal Planet", "Nat Geo", "Nat Geo Mundo"
    )
    
    val dos47Cat = setOf("24/7", "Extrema TV")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Deportes", mainUrl),
            Pair("Entretenimiento", mainUrl),
            Pair("Noticias", mainUrl),
            Pair("Peliculas", mainUrl),
            Pair("Infantil", mainUrl),
            Pair("Educacion", mainUrl),
            Pair("24/7", mainUrl),
            Pair("Todos", mainUrl),
        )
        
        // Reemplazar apmap con un bucle for tradicional
        for ((name, url) in urls) {
            val doc = app.get(url).document
            
            // Selector corregido basado en el HTML real
            val home = doc.select("div.channels.grid > div.p-2.rounded").filterNot { element ->
                val text = element.selectFirst("p.des")?.text() ?: ""
                nowAllowed.any { text.contains(it, ignoreCase = true) } || text.isBlank()
            }.filter {
                val text = it.selectFirst("p.des")?.text()?.trim() ?: ""
                when (name) {
                    "Deportes" -> deportesCat.any { cat -> text.contains(cat, ignoreCase = true) }
                    "Entretenimiento" -> entretenimientoCat.any { cat -> text.contains(cat, ignoreCase = true) }
                    "Noticias" -> noticiasCat.any { cat -> text.contains(cat, ignoreCase = true) }
                    "Peliculas" -> peliculasCat.any { cat -> text.contains(cat, ignoreCase = true) }
                    "Infantil" -> infantilCat.any { cat -> text.contains(cat, ignoreCase = true) }
                    "Educacion" -> educacionCat.any { cat -> text.contains(cat, ignoreCase = true) }
                    "24/7" -> dos47Cat.any { cat -> text.contains(cat, ignoreCase = true) }
                    "Todos" -> true
                    else -> true
                }
            }.map {
                val title = it.selectFirst("p.des")?.text() ?: ""
                val img = it.selectFirst("img")?.attr("src") ?: ""
                val link = it.selectFirst("a.channel-link")?.attr("href") ?: ""
                
                newLiveSearchResponse(title, link, TvType.Live) {
                    this.posterUrl = if (img.startsWith("http")) img else fixUrl(img)
                }
            }
            
            if (home.isNotEmpty()) {
                items.add(HomePageList(name, home))
            }
        }
        
        // Usar newHomePageResponse en lugar del constructor
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(mainUrl).document
        
        return doc.select("div.channels.grid > div.p-2.rounded").filterNot { element ->
            val text = element.selectFirst("p.des")?.text() ?: ""
            nowAllowed.any { text.contains(it, ignoreCase = true) } || text.isBlank()
        }.filter { element ->
            element.selectFirst("p.des")?.text()?.contains(query, ignoreCase = true) ?: false
        }.map {
            val title = it.selectFirst("p.des")?.text() ?: ""
            val img = it.selectFirst("img")?.attr("src") ?: ""
            val link = it.selectFirst("a.channel-link")?.attr("href") ?: ""
            
            newLiveSearchResponse(title, link, TvType.Live) {
                this.posterUrl = if (img.startsWith("http")) img else fixUrl(img)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h2")?.text() 
            ?: doc.selectFirst("h1")?.text()
            ?: "Canal en Vivo"
            
        val poster = doc.selectFirst("img[src*='imge']")?.attr("src") ?: ""
        val desc = doc.selectFirst("div.info, p")?.text() ?: "Transmisión en vivo"

        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var currentUrl = data
        var currentReferer = mainUrl
        val maxDepth = 5

        repeat(maxDepth) { depth ->

            val response = app.get(
                currentUrl,
                headers = mapOf(
                    "User-Agent" to headers["User-Agent"]!!,
                    "Referer" to currentReferer
                )
            )

            val html = response.text
            val document = response.document

            // ===============================
            // A) M3U8 DIRECTO
            // ===============================
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                .find(html)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.let { url ->

                    callback(
                        newExtractorLink(
                            name,
                            "Directo",
                            url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.quality = getQualityFromName("HD")
                        }
                    )
                    return true
                }

            // ===============================
            // B) SCRIPT PACKER (REAL)
            // ===============================
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

                            callback(
                                newExtractorLink(
                                    name,
                                    "Desempaquetado",
                                    url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.quality = getQualityFromName("HD")
                                }
                            )
                            return true
                        }

                } catch (_: Exception) {}
            }

            // ===============================
            // C) TRIPLE BASE64
            // ===============================
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

                                    callback(
                                        newExtractorLink(
                                            name,
                                            "Base64",
                                            url,
                                            ExtractorLinkType.M3U8
                                        ) {
                                            this.quality = getQualityFromName("HD")
                                        }
                                    )
                                    return true
                                }

                        } catch (_: Exception) {}
                    }
            }

            // ===============================
            // D) source:, file:, var src
            // ===============================
            val genericRegexes = listOf(
                Regex("""source\s*:\s*["']([^"']+)["']"""),
                Regex("""file\s*:\s*["']([^"']+)["']"""),
                Regex("""var\s+src\s*=\s*["']([^"']+)["']""")
            )

            for (regex in genericRegexes) {
                regex.find(html)?.let {
                    val url = it.groupValues[1].replace("\\/", "/")

                    if (url.startsWith("http")) {
                        callback(
                            newExtractorLink(
                                name,
                                "Genérico",
                                url,
                                if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityFromName("HD")
                            }
                        )
                        return true
                    }
                }
            }

            // ===============================
            // E) IFRAME PROFUNDIDAD
            // ===============================
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

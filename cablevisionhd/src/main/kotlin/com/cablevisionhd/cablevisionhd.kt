package com.cablevision

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URL

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
    
    // Lista de canales a excluir
    val nowAllowed = setOf("Únete al chat", "Donar con Paypal", "Lizard Premium", "Mundo Latam", "Donacion", "Red Social")
    
    // Categorías actualizadas basadas en la página real (febrero 2026)
    val deportesCat = setOf(
        "TUDN", "TNT SPORTS", "Fox Sports Premium", "TYC Sports", "Movistar Deportes",
        "Movistar La Liga", "Movistar Liga De Campeones", "Dazn F1", "Dazn La Liga",
        "Bein Sports Extra", "Directv Sports", "Directv Sports 2", "Directv Sports Plus",
        "Espn Premium", "Espn", "Espn 2", "Espn 3", "Espn 4", "Espn 5", "Espn 6", "Espn 7",
        "Espn Mexico", "Espn 2 Mexico", "Espn 3 Mexico", "Fox Deportes", "Fox Sports",
        "Fox Sports 2", "Fox Sports 3", "Fox Sports Mexico", "Fox Sports 2 Mexico", "Fox Sports 3 Mexico"
    )
    
    val entretenimientoCat = setOf(
        "Telefe", "El Trece", "Telemundo 51", "Telemundo Puerto Rico", "Univisión",
        "Pasiones", "Caracol", "RCN", "Latina", "America TV", "ATV", "Las Estrellas",
        "Tlnovelas", "Galavision", "Azteca 7", "Azteca Uno", "Canal 5", "Universal Channel",
        "TNT", "TNT Series", "Star Channel", "Cinemax", "Space", "SYFY", "Warner Channel",
        "Warner Channel (Mexico)", "Cinecanal", "FX", "AXN", "AMC", "Studio Universal",
        "Golden Plus", "Golden Edge", "Golden Premier", "Golden Premier 2", "Multipremier",
        "Canal Sony", "Distrito Comedia"
    )
    
    val noticiasCat = setOf(
        "Telemundo 51"  // Añade más si aparecen en el sitio, como CNN si está disponible
    )
    
    val peliculasCat = setOf()  // Vacío en la página actual; canales de películas se movieron a entretenimiento
    
    val infantilCat = setOf(
        "Nick", "Cartoon Network", "Tooncast", "Disney Channel"
    )
    
    val educacionCat = setOf(
        "Discovery Channel", "Discovery World", "Discovery Science", "Nat Geo",
        "History", "History 2", "Animal Planet"
    )
    
    val dos47Cat = setOf(
        "Extrema TV"  // Sin "24/7" explícito
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Deportes", mainUrl),
            Pair("Entretenimiento", mainUrl),
            Pair("Noticias", mainUrl),
            Pair("Infantil", mainUrl),
            Pair("Educacion", mainUrl),
            Pair("24/7", mainUrl),
            Pair("Todos", mainUrl),
        )
        
        urls.apmap { (name, url) ->
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
        
        return HomePageResponse(items)
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
    
    private fun findVideoInHtml(html: String): String? {
        // 1. M3U8 directo (el más importante)
        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.let {
            return cleanUrl(it.groupValues[1])
        }
    
        // 2. Desempaquetar eval(p,a,c,k,e,d) ← muy común en estos sitios
        Regex("""eval\(function\(p,a,c,k,e,[^)]*\).*?\)""").findAll(html).forEach { match ->
            try {
                val unpacked = JsUnpacker(match.value).unpack() ?: return@forEach
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.let {
                    return cleanUrl(it.groupValues[1])
                }
                Regex("""file\s*:\s*["']([^"']+)["']""").find(unpacked)?.let {
                    return cleanUrl(it.groupValues[1])
                }
            } catch (_: Throwable) {}
        }
    
        // 3. Triple (o más) base64 - más flexible
        val atobRegex = Regex("""atob\(["']([^"']+)["']\)""")
        atobRegex.findAll(html).forEach { match ->
            var encoded = match.groupValues[1]
            repeat(5) {  // hasta 5 niveles por si cambian
                try {
                    val decodedBytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
                    val decodedStr = String(decodedBytes, Charsets.UTF_8)
                    if (decodedStr.contains(".m3u8") || decodedStr.startsWith("http")) {
                        return cleanUrl(decodedStr)
                    }
                    encoded = decodedStr.trim()
                } catch (_: Throwable) {
                    break
                }
            }
        }
    
        // 4. Patrones adicionales (muy útiles en reproductores tipo clappr/jwplayer)
        listOf(
            """source\s*:\s*["']([^"']+)["']""",
            """file\s*:\s*["']([^"']+)["']""",
            """var\s+src\s*=\s*["']([^"']+)["']""",
            """["'](https?://[^"']+\.(?:m3u8|mp4|ts)[^"']*)["']"""
        ).forEach { pattern ->
            Regex(pattern).find(html)?.let {
                val candidate = it.groupValues[1]
                if (candidate.contains("http") || candidate.contains(".m3u8")) {
                    return cleanUrl(candidate)
                }
            }
        }
    
        return null
    }
    
    private fun cleanUrl(raw: String): String {
        return raw.replace("\\/", "/")
            .replace("\\\"", "")
            .trim('"', '\'', ' ')
    }
    
    private suspend fun extractVideoFromUrl(
        startUrl: String,
        initialReferer: String = mainUrl,
        depth: Int = 0
    ): String? {
        if (depth > 5) return null
    
        var currentUrl = startUrl
        var currentReferer = initialReferer
    
        repeat(6) { level ->
            try {
                val response = app.get(
                    currentUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to currentReferer,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "es-ES,es;q=0.9"
                    )
                )
                val html = response.text
    
                // Intenta extraer video
                findVideoInHtml(html)?.let { return it }
    
                // Busca iframe
                val iframeSrc = response.document.select("iframe").attr("src").trim()
                if (iframeSrc.isNotEmpty()) {
                    val nextUrl = if (iframeSrc.startsWith("http")) iframeSrc else fixUrl(iframeSrc)
                    if (nextUrl != currentUrl && nextUrl.isNotBlank()) {
                        currentReferer = currentUrl
                        currentUrl = nextUrl
                        return@repeat  // siguiente nivel
                    }
                }
    
                // Si no hay iframe ni video → salir
                return null
    
            } catch (e: Exception) {
                // Manejo de errores, opcional: Log.e("Cablevision", "Error depth $level → $e")
            }
        }
        return null
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoUrl = extractVideoFromUrl(data, mainUrl)
        
        if (!videoUrl.isNullOrBlank()) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    videoUrl,
                    mainUrl,
                    Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8"),
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to mainUrl,
                        "Origin" to mainUrl
                    )
                )
            )
            return true
        }
        
        // Fallback: intentar con los botones
        val doc = app.get(data).document
        doc.select("a.btn").forEach { button ->
            val streamUrl = button.attr("href")
            if (streamUrl.isNotEmpty()) {
                val result = extractVideoFromUrl(fixUrl(streamUrl), data)
                if (!result.isNullOrBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            result,
                            mainUrl,
                            Qualities.Unknown.value,
                            isM3u8 = result.contains(".m3u8"),
                            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)
                        )
                    )
                    return true
                }
            }
        }
        return false
    }
}
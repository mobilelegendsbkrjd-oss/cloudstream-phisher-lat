package com.latanime

import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Latanime : MainAPI() {
    override var mainUrl              = "https://latanime.org"
    override var name                 = "Latanime"
    override val hasMainPage          = true
    override var lang                 = "es-mx"
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // URL del JSON con categorías personalizadas
    private val categoriesJsonUrl = "https://raw.githubusercontent.com/mobilelegendsbkrjd-oss/lat_cs_bkrjd/main/ListaLA.json"

    override val mainPage = mainPageOf(
        "emision?p=1" to "📺 En Emisión",
        "animes?fecha=false&genero=false&letra=false&categoria=latino" to "🎙️ Anime Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=anime" to "🇯🇵 Anime Subtitulado",
        "animes?fecha=false&genero=false&letra=false&categoria=Película%20Latino" to "🎬 Películas Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=Película" to "🎬 Películas Subtitulado",
        "animes?fecha=false&genero=false&letra=false&categoria=ova-latino" to "📀 OVAs Latino",
        "animes?fecha=false&genero=false&letra=false&categoria=ova" to "📀 OVAs Subtitulado",
        "animes?fecha=false&genero=false&letra=false&categoria=especial" to "✨ Especiales",
        "animes?categoria=Cartoon" to "📺 Cartoons Latino",
        "CATEGORIAS_JSON" to "📚 Categorias"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Si es la categoría de JSON personalizado
        if (request.data == "CATEGORIAS_JSON") {
            return getCategoriesFromJson(page)
        }
        
        // Para TODAS las demás categorías - método simple y original
        val document = app.get("$mainUrl/${request.data}&p=$page").documentLarge
        val home     = document.select("div.row a").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    // Función SIMPLE para obtener categorías del JSON
    private suspend fun getCategoriesFromJson(page: Int): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        try {
            val jsonText = app.get(categoriesJsonUrl, timeout = 20).text.trim()
            val cleanJson = jsonText.removePrefix("[").removeSuffix("]").trim()
            if (cleanJson.isEmpty()) return newHomePageResponse(items)
            
            val objetos = cleanJson.split("},").map { it.trim() + "}" }
            val categoryItems = mutableListOf<SearchResponse>()
            
            objetos.forEach { objStr ->
                try {
                    val titleMatch = Regex(""""title"\s*:\s*"([^"]*)"""").find(objStr)
                    val urlMatch = Regex(""""url"\s*:\s*"([^"]*)"""").find(objStr)
                    val posterMatch = Regex(""""poster"\s*:\s*"([^"]*)"""").find(objStr)
                    
                    val title = titleMatch?.groupValues?.get(1) ?: return@forEach
                    val url = urlMatch?.groupValues?.get(1) ?: return@forEach
                    val poster = posterMatch?.groupValues?.get(1) ?: ""
                    
                    // SIN FILTROS - mostrar todo tal cual
                    categoryItems.add(
                        newAnimeSearchResponse(title, url, TvType.Anime) {
                            this.posterUrl = poster
                            // Dejar que el sitio maneje los filtros
                            addDubStatus(DubStatus.Subbed)
                        }
                    )
                } catch (e: Exception) {
                    // Ignorar errores en items individuales
                }
            }
            
            if (categoryItems.isNotEmpty()) {
                items.add(HomePageList("📚 Categorias", categoryItems, true))
            }
        } catch (e: Exception) {
            // Si falla la carga del JSON, mostrar categorías por defecto
            val defaultCategories = listOf(
                newAnimeSearchResponse("🎭 Acción Latino", "https://latanime.org/animes?genero=accion&categoria=latino&p=1", TvType.Anime),
                newAnimeSearchResponse("💖 Romance Latino", "https://latanime.org/animes?genero=romance&categoria=latino&p=1", TvType.Anime),
                newAnimeSearchResponse("😂 Comedia Latino", "https://latanime.org/animes?genero=comedia&categoria=latino&p=1", TvType.Anime)
            )
            items.add(HomePageList("📚 Categorias", defaultCategories, true))
        }
        
        return newHomePageResponse(items, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.select("h3").text().trim()
        if (title.isBlank()) return null
        
        val href      = fixUrl(this.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val isDub     = title.contains("Latino") || title.contains("Castellano")
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/buscar?q=$query").documentLarge
        return document.select("div.row a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        // Si es una URL de categoría del JSON, redirigir directamente
        if (url.contains("https://latanime.org/animes?genero=")) {
            // Simplemente cargar la página normal del sitio
            // El usuario podrá navegar por todas las páginas allí
            val document = app.get(url).documentLarge
            val home = document.select("div.row a").mapNotNull { it.toSearchResult() }
            
            // Crear una respuesta simple que muestre los resultados
            val categoryName = url.substringAfter("genero=").substringBefore("&")
                .replace("-", " ").replaceFirstChar { it.uppercase() }
            
            return newAnimeLoadResponse("📚 $categoryName", url, TvType.Anime) {
                this.posterUrl = null
                this.plot = "Navegando por: $categoryName\n\n" +
                           "Puedes explorar todas las páginas usando la paginación."
                
                // Agregar resultados como episodios para que sean clickeables
                addEpisodes(DubStatus.Subbed, home.mapIndexed { index, item ->
                    newEpisode(item.url) {
                        name = item.name
                        posterUrl = item.posterUrl
                        episode = index + 1
                    }
                })
            }
        }
        
        // Carga NORMAL de anime/película (código original)
        val document    = app.get(url).documentLarge
        val title       = document.selectFirst("h2")?.text() ?: "Desconocido"
        val poster      = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val description = document.selectFirst("h2 ~ p.my-2")?.text()
        val tags        = document.select("a div.btn").map { it.text() }
        val year        = document.select(".span-tiempo").text().substringAfterLast(" de ").toIntOrNull()
        val epsAnchor   = document.select("div.row a[href*='/ver/']")

        return if (epsAnchor.size > 1) {
            val episodes: List<Episode>? = epsAnchor.map {
                val epPoster = it.select("img").attr("data-src")
                val epHref   = it.attr("href")

                newEpisode(epHref) {
                    this.posterUrl = epPoster
                }
            }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else newMovieLoadResponse(title, url, TvType.AnimeMovie, epsAnchor.attr("href")) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).documentLarge
        document.select("#play-video a").map {
            val href = base64Decode(it.attr("data-player")).substringAfter("=")
            loadExtractor(
                href,
                "",
                subtitleCallback,
                callback
            )
        }
        return true
    }

    private fun Element.getImageAttr(): String? {
        return this.attr("data-src")
            .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: this.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") }
    }
                                            }

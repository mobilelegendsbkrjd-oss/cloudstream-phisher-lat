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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
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
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries)

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

    // Función para obtener categorías del JSON
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
                    
                    // Usar TvType.TvSeries para que funcione con recommendations
                    val isLatino = title.contains("Latino", true) || url.contains("latino", true)
                    
                    categoryItems.add(
                        newAnimeSearchResponse(title, url, TvType.TvSeries) {
                            this.posterUrl = poster
                            addDubStatus(if (isLatino) DubStatus.Dubbed else DubStatus.Subbed)
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
                newAnimeSearchResponse("🎭 Acción Latino", "https://latanime.org/animes?genero=accion&categoria=latino&p=1", TvType.TvSeries) {
                    addDubStatus(DubStatus.Dubbed)
                },
                newAnimeSearchResponse("💖 Romance Latino", "https://latanime.org/animes?genero=romance&categoria=latino&p=1", TvType.TvSeries) {
                    addDubStatus(DubStatus.Dubbed)
                },
                newAnimeSearchResponse("😂 Comedia Latino", "https://latanime.org/animes?genero=comedia&categoria=latino&p=1", TvType.TvSeries) {
                    addDubStatus(DubStatus.Dubbed)
                }
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
        // Si es una URL de categoría del JSON, usar recommendations
        if (url.contains("https://latanime.org/animes?genero=")) {
            return loadCategoryWithRecommendations(url)
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

    // Función para cargar categorías con RECOMMENDATIONS
    private suspend fun loadCategoryWithRecommendations(url: String): LoadResponse {
        // Cargar la página de la categoría (página 1)
        val document = app.get(url).documentLarge
        
        // Obtener resultados de la primera página
        val results = document.select("div.row a").mapNotNull { it.toSearchResult() }
        
        // Contar páginas totales para información
        val pagination = document.select("ul.pagination li a")
        val totalPages = if (pagination.isNotEmpty()) {
            pagination.mapNotNull { it.text().toIntOrNull() }.maxOrNull() ?: 1
        } else {
            1
        }
        
        // Crear nombre de categoría
        val categoryName = url.substringAfter("genero=").substringBefore("&")
            .replace("-", " ").replaceFirstChar { it.uppercase() }
            
        val isLatino = url.contains("categoria=latino")
        val displayName = if (isLatino) "$categoryName Latino" else categoryName
        
        // Crear episodio especial que redirige a la URL completa
        val exploreEpisode = newEpisode(url) {
            name = "🔍 Explorar Todo el Catálogo"
            episode = 1
        }
        
        // Crear descripción informativa
        val plot = buildString {
            append("📚 $displayName\n")
            append("📊 Páginas totales: $totalPages\n")
            append("🎬 Resultados en esta página: ${results.size}\n")
            append("\n")
            append("💡 Presiona '🔍 Explorar Todo el Catálogo' arriba para navegar por TODAS las páginas.\n")
            append("👇 Abajo verás sugerencias de la primera página.")
        }
        
        // Usar newTvSeriesLoadResponse con recommendations
        return newTvSeriesLoadResponse(
            "📚 $displayName",
            url,
            TvType.TvSeries,
            listOf(exploreEpisode) // Episodio para explorar todo
        ) {
            this.posterUrl = null
            this.plot = plot
            this.tags = listOf("Categoría", if (isLatino) "Latino" else "Subtitulado")
            
            // Agregar resultados como RECOMMENDATIONS (funciona en CloudStream)
            this.recommendations = results
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

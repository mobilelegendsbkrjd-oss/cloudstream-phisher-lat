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
        
        // Para las demás categorías - usar selector original que funcionaba
        val document = app.get("$mainUrl/${request.data}&p=$page").documentLarge
        
        // Selector original que funcionaba
        val home = document.select("div.row a").mapNotNull { element ->
            val result = element.toSearchResult()
            
            // Solo aplicar filtro anti-Castellano en Anime Subtitulado
            if (result != null && request.data.contains("categoria=anime")) {
                val title = result.name.lowercase()
                val isCastellano = title.contains("castellano") || 
                                 result.url.contains("castellano")
                if (isCastellano) {
                    return@mapNotNull null
                }
            }
            
            result
        }
        
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty() && document.select("ul.pagination").isNotEmpty()
        )
    }

    // Función para obtener categorías del JSON (similar a SoloLatino)
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
                    
                    // Detectar si es categoría latino
                    val isLatino = title.contains("Latino", true) || 
                                  url.contains("latino", true)
                    
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
                newAnimeSearchResponse("🎭 Acción Latino", "https://latanime.org/animes?genero=accion&categoria=latino&p=", TvType.TvSeries) {
                    addDubStatus(DubStatus.Dubbed)
                },
                newAnimeSearchResponse("💖 Romance Latino", "https://latanime.org/animes?genero=romance&categoria=latino&p=", TvType.TvSeries) {
                    addDubStatus(DubStatus.Dubbed)
                },
                newAnimeSearchResponse("😂 Comedia Latino", "https://latanime.org/animes?genero=comedia&categoria=latino&p=", TvType.TvSeries) {
                    addDubStatus(DubStatus.Dubbed)
                }
            )
            items.add(HomePageList("📚 Categorias", defaultCategories, true))
        }
        
        // Paginación infinita - siempre mostrar todas las categorías
        return newHomePageResponse(items, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("h3").text().trim()
        if (title.isBlank()) return null
        
        val href = fixUrl(this.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val isDub = title.contains("Latino") || title.contains("Castellano")
        
        // Determinar tipo basado en URL
        val type = when {
            href.contains("/pelicula") || href.contains("/movie") -> TvType.AnimeMovie
            href.contains("/ova") -> TvType.OVA
            href.contains("/especial") -> TvType.OVA
            else -> TvType.Anime
        }
        
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addDubStatus(isDub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/buscar?q=$query").documentLarge
        return document.select("div.row a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        // Si es una URL de categoría personalizada (del JSON)
        if (url.contains("https://latanime.org/animes?genero=")) {
            return loadCategoryPage(url)
        }
        
        val document    = app.get(url).documentLarge
        val rawTitle    = document.selectFirst("h2")?.text()?.trim() ?: "Desconocido"
        val title       = rawTitle
        val poster      = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val description = document.selectFirst("h2 ~ p.my-2")?.text()?.trim()
        val tags        = document.select("a div.btn").map { it.text().trim() }
        val yearText    = document.select(".span-tiempo").text()
        val year        = yearText.substringAfterLast(" de ").toIntOrNull()
        val epsAnchor   = document.select("div.row a[href*='/ver/']")

        // Detectar si es película (solo un episodio) o serie
        val isMovie = url.contains("/pelicula") || url.contains("/movie") || 
                     rawTitle.contains("Película", true) ||
                     epsAnchor.size <= 1

        return if (!isMovie && epsAnchor.size > 1) {
            // Es una serie
            val episodes: List<Episode>? = epsAnchor.map {
                val epTitle = it.select("h3").text().trim()
                val epHref   = fixUrl(it.attr("href"))
                val epPoster = it.select("img").attr("data-src")

                newEpisode(epHref) {
                    this.name = epTitle
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
        } else {
            // Es una película, OVA o especial
            val movieUrl = epsAnchor.firstOrNull()?.attr("href") ?: url
            val type = when {
                url.contains("/ova/", true) || rawTitle.contains("OVA", true) -> TvType.OVA
                url.contains("/especial/", true) || rawTitle.contains("Especial", true) -> TvType.OVA
                else -> TvType.AnimeMovie
            }
            
            newMovieLoadResponse(title, url, type, movieUrl) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    // Función especial para cargar páginas de categorías personalizadas (como en SoloLatino)
    private suspend fun loadCategoryPage(url: String): LoadResponse {
        // Extraer número de página de la URL
        val pageMatch = Regex("&p=(\\d+)").find(url)
        val currentPage = pageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val baseUrl = if (pageMatch != null) {
            url.replace(Regex("&p=\\d+"), "")
        } else {
            url
        }
        
        // Cargar múltiples páginas para obtener más resultados
        val allResults = mutableListOf<SearchResponse>()
        val maxPagesToLoad = 3 // Cargar primeras 3 páginas para mostrar más contenido
        
        for (page in 1..maxPagesToLoad) {
            try {
                val fullUrl = "$baseUrl&p=$page"
                val document = app.get(fullUrl, timeout = 15).documentLarge
                
                // Obtener resultados de esta página
                val pageResults = document.select("div.row a").mapNotNull { element ->
                    element.toSearchResult()
                }
                
                allResults.addAll(pageResults)
                
                // Si no hay paginación o llegamos al final, salir
                if (pageResults.isEmpty() || document.select("ul.pagination").isEmpty()) {
                    break
                }
                
                // Pequeña pausa para no sobrecargar el servidor
                kotlinx.coroutines.delay(100)
            } catch (e: Exception) {
                // Si falla una página, continuar con las demás
                continue
            }
        }
        
        // Eliminar duplicados por URL
        val uniqueResults = allResults.distinctBy { it.url }
        
        // Contar total de páginas (solo para información)
        val firstPageDoc = app.get("$baseUrl&p=1", timeout = 15).documentLarge
        val pagination = firstPageDoc.select("ul.pagination li a")
        val totalPages = if (pagination.isNotEmpty()) {
            pagination.mapNotNull { it.text().toIntOrNull() }.maxOrNull() ?: 1
        } else {
            1
        }
        
        // Crear nombre de categoría
        val categoryName = Regex("genero=([^&]+)").find(url)?.groupValues?.get(1)?.replace("-", " ")?.capitalizeWords()
            ?: "Categoría"
            
        val isLatino = url.contains("categoria=latino")
        val displayName = if (isLatino) "$categoryName Latino" else categoryName
        
        // Crear episodio especial para explorar todo el catálogo
        val exploreEpisode = newEpisode("$baseUrl&p=1") {
            name = "🔍 Explorar Todo el Catálogo"
            episode = 1
        }
        
        // Crear descripción informativa
        val plot = buildString {
            append("📚 $displayName\n")
            append("📊 Mostrando: ${uniqueResults.size} animes (de ${totalPages} páginas)\n")
            append("🎯 Cargadas: ${maxPagesToLoad.coerceAtMost(totalPages)} de $totalPages páginas\n")
            append("\n")
            append("💡 Presiona '🔍 Explorar Todo el Catálogo' arriba para ver TODOS los animes.\n")
            append("👇 Abajo verás sugerencias de los primeros resultados.")
        }
        
        // Usar newTvSeriesLoadResponse con el episodio especial para explorar
        return newTvSeriesLoadResponse(
            "📚 $displayName",
            url,
            TvType.TvSeries,
            listOf(exploreEpisode) // Episodio especial para explorar todo
        ) {
            this.posterUrl = null
            this.plot = plot
            this.tags = listOf("Categoría", if (isLatino) "Latino" else "Subtitulado", "Explorar")
            
            // Agregar resultados como SUGERENCIAS (solo primeros para no sobrecargar)
            val suggestionsToShow = uniqueResults.take(50) // Limitar a 50 sugerencias
            this.recommendations = suggestionsToShow
            
            // Nota: El episodio especial "Explorar Todo el Catálogo" llevará al usuario
            // a la página normal del sitio donde podrá navegar por todas las páginas
        }
    }
    
    private fun String.capitalizeWords(): String {
        return this.split(" ").joinToString(" ") { 
            it.replaceFirstChar { char -> char.uppercase() }
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

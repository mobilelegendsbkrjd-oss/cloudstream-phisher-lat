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
                        newAnimeSearchResponse(title, url, TvType.TvSeries) {
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
                newAnimeSearchResponse("🎭 Acción Latino", "https://latanime.org/animes?genero=accion&categoria=latino&p=1", TvType.TvSeries),
                newAnimeSearchResponse("💖 Romance Latino", "https://latanime.org/animes?genero=romance&categoria=latino&p=1", TvType.TvSeries),
                newAnimeSearchResponse("😂 Comedia Latino", "https://latanime.org/animes?genero=comedia&categoria=latino&p=1", TvType.TvSeries)
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
        // Si es una URL de categoría del JSON
        if (url.contains("https://latanime.org/animes?genero=")) {
            return loadCategoryWithAllRecommendations(url)
        }
        
        val document    = app.get(url).documentLarge
        val rawTitle    = document.selectFirst("h2")?.text()?.trim() ?: "Desconocido"
        val title       = rawTitle
        val poster      = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val description = document.selectFirst("h2 ~ p.my-2")?.text()
        val tags        = document.select("a div.btn").map { it.text() }
        val year        = document.select(".span-tiempo").text().substringAfterLast(" de ").toIntOrNull()
        val epsAnchor   = document.select("div.row a[href*='/ver/']")

        // Obtener recomendaciones del HTML
        val recommendations = getRecommendationsFromPage(document)
        
        // Detectar si es película (solo un episodio) o serie
        val isMovie = url.contains("/pelicula") || url.contains("/movie") || 
                     rawTitle.contains("Película", true) ||
                     epsAnchor.size <= 1

        return if (!isMovie && epsAnchor.size > 1) {
            // Es una serie
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
                // Agregar recomendaciones si hay
                if (recommendations.isNotEmpty()) {
                    this.recommendations = recommendations
                }
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
                // Agregar recomendaciones si hay
                if (recommendations.isNotEmpty()) {
                    this.recommendations = recommendations
                }
            }
        }
    }

    // Función para cargar categorías con TODAS las recomendaciones
    private suspend fun loadCategoryWithAllRecommendations(url: String): LoadResponse {
        // Extraer información básica
        val categoryName = url.substringAfter("genero=").substringBefore("&")
            .replace("-", " ").replaceFirstChar { it.uppercase() }
        
        val isLatino = url.contains("categoria=latino")
        val displayName = if (isLatino) "$categoryName Latino" else categoryName
        
        // Cargar TODAS las páginas (hasta donde sea posible)
        val allResults = mutableListOf<SearchResponse>()
        var currentPage = 1
        var hasMorePages = true
        val maxAttempts = 100 // Máximo de páginas a intentar cargar
        
        while (hasMorePages && currentPage <= maxAttempts) {
            try {
                val pageUrl = if (url.contains("&p=")) {
                    url.replace(Regex("&p=\\d+"), "&p=$currentPage")
                } else {
                    "$url&p=$currentPage"
                }
                
                val document = app.get(pageUrl, timeout = 30).documentLarge
                val pageResults = document.select("div.row a").mapNotNull { element ->
                    element.toSearchResult()
                }
                
                if (pageResults.isNotEmpty()) {
                    allResults.addAll(pageResults)
                    currentPage++
                    
                    // Verificar si hay más páginas
                    val pagination = document.select("ul.pagination")
                    hasMorePages = pagination.isNotEmpty()
                    
                    // Pequeña pausa para no sobrecargar
                    kotlinx.coroutines.delay(50)
                } else {
                    hasMorePages = false
                }
            } catch (e: Exception) {
                // Si falla una página, asumir que no hay más
                hasMorePages = false
            }
        }
        
        // Eliminar duplicados
        val uniqueResults = allResults.distinctBy { it.url }
        
        // Crear descripción
        val plot = "📚 $displayName\n" +
                  "📊 Total de animes cargados: ${uniqueResults.size}\n" +
                  "📄 Páginas escaneadas: ${currentPage - 1}\n\n" +
                  "Todos los animes aparecerán como sugerencias debajo."
        
        // Usar newTvSeriesLoadResponse con episodios vacíos y TODAS las recomendaciones
        return newTvSeriesLoadResponse(
            "📚 $displayName",
            url,
            TvType.TvSeries,
            emptyList() // Episodios vacíos
        ) {
            this.posterUrl = null
            this.plot = plot
            this.tags = listOf("Categoría", if (isLatino) "Latino" else "Subtitulado")
            
            // Agregar TODOS los resultados como recomendaciones
            this.recommendations = uniqueResults
        }
    }
    
    // Función para extraer recomendaciones de la página HTML
    private fun getRecommendationsFromPage(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val recommendations = mutableListOf<SearchResponse>()
        
        try {
            // Buscar en la sección de recomendaciones
            document.select(".recomendados a, .sugerencias a, .recommendations a").forEach { element ->
                val result = element.toSearchResult()
                if (result != null) {
                    recommendations.add(result)
                }
            }
            
            // Si no hay en sección específica, buscar enlaces generales que puedan ser recomendaciones
            if (recommendations.isEmpty()) {
                document.select("a[href*='/anime/']").forEach { element ->
                    // Filtrar que no sea el anime actual
                    val href = element.attr("href")
                    if (href.contains("/anime/") && !href.contains("#")) {
                        val result = element.toSearchResult()
                        if (result != null && recommendations.size < 20) { // Limitar a 20
                            recommendations.add(result)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Si falla, retornar lista vacía
        }
        
        return recommendations.distinctBy { it.url } // Eliminar duplicados
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

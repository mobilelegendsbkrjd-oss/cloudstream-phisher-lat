package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack

// Extractor para el Canal 2 (Upns)
class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Server"
    override var mainUrl = "https://latinluchas.upns.online"
}

class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster = "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val categories = listOf(
            Pair("WWE", "$mainUrl/category/eventos/wwe/"),
            Pair("UFC", "$mainUrl/category/eventos/ufc/"),
            Pair("AEW", "$mainUrl/category/eventos/aew/"),
            Pair("Lucha Libre Mexicana", "$mainUrl/category/eventos/lucha-libre-mexicana/"),
            Pair("Indies", "$mainUrl/category/eventos/indies/")
        )

        val homePages = categories.map { (name, url) ->
            val doc = app.get(url).document
            val items = doc.select("article, .post, .elementor-post").mapNotNull { element ->
                val title = element.selectFirst("h2, h3, .entry-title")?.text()?.trim() ?: return@mapNotNull null
                val href = element.selectFirst("a")?.attr("abs:href") ?: return@mapNotNull null
                val poster = element.selectFirst("img")?.attr("abs:src")
                    ?: element.selectFirst("img")?.attr("abs:data-src") ?: defaultPoster

                newAnimeSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            HomePageList(name, items)
        }
        return newHomePageResponse(homePages)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Evento"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")

        // Buscar TODOS los enlaces posibles
        val allLinks = mutableListOf<Pair<String, String>>() // (nombre, url)

        // 1. Buscar enlaces .btn-video (los más específicos)
        document.select("a.btn-video[href*='/tv/']").forEach { anchor ->
            val name = anchor.text().trim().uppercase()
            val link = anchor.attr("abs:href")
            if (link.contains("/tv/") && !link.contains("descargar", true)) {
                allLinks.add(Pair(name, link))
            }
        }

        // 2. Buscar en .replay-options (si estamos en una página con múltiples eventos)
        document.select(".replay-show").forEach { show ->
            val showTitle = show.selectFirst("h3")?.text() ?: ""
            // Verificar si este show corresponde al título actual
            if (showTitle.contains(title.substringBefore("Repetición").trim(), ignoreCase = true) ||
                title.contains(showTitle.substringBefore("Repetición").trim(), ignoreCase = true)) {

                show.select(".replay-options a.watch-button[href*='/tv/']").forEach { anchor ->
                    val name = anchor.text().trim().uppercase()
                    val link = anchor.attr("abs:href")
                    if (link.contains("/tv/") && !link.contains("descargar", true)) {
                        allLinks.add(Pair(name, link))
                    }
                }
            }
        }

        // 3. Buscar enlaces generales con filtros (último recurso)
        if (allLinks.isEmpty()) {
            val tempLinks = mutableListOf<Pair<String, String>>()
            document.select("a[href*='/tv/']").forEach { anchor ->
                val name = anchor.text().trim().uppercase()
                val link = anchor.attr("abs:href")

                if (link.contains("/tv/") &&
                    !link.contains("descargar", true) &&
                    !link.contains("contacto", true) &&
                    !link.contains("donar", true) &&
                    !link.contains("politica", true) &&
                    (name.contains("OPCI") || name.contains("OPCION") || name.contains("VER") ||
                            name.contains("CANAL") || name.contains("ENGLISH") || name.contains("MAIN") ||
                            name.contains("PELEA") || name.contains("PRELIMINARES") || name.contains("ESTELAR"))) {
                    tempLinks.add(Pair(name, link))
                }
            }
            // Tomar solo los primeros 8 enlaces
            allLinks.addAll(tempLinks.take(8))
        }

        // Eliminar duplicados
        val uniqueLinks = allLinks.distinctBy { it.second }

        // Ordenar los enlaces como quieres: OPCIÓN 2, 3, 1, 4, ENGLISH, otros
        val orderedLinks = mutableListOf<Pair<String, String>>()

        // Función auxiliar para agregar si no existe
        fun addIfNotExists(pair: Pair<String, String>) {
            if (!orderedLinks.any { it.second == pair.second }) {
                orderedLinks.add(pair)
            }
        }

        // Primero buscar OPCIÓN 2
        uniqueLinks.find { it.first.contains("2") }?.let { addIfNotExists(it) }
        // Luego OPCIÓN 3
        uniqueLinks.find { it.first.contains("3") }?.let { addIfNotExists(it) }
        // Luego OPCIÓN 1
        uniqueLinks.find { it.first.contains("1") }?.let { addIfNotExists(it) }
        // Luego OPCIÓN 4
        uniqueLinks.find { it.first.contains("4") }?.let { addIfNotExists(it) }
        // Luego ENGLISH
        uniqueLinks.find { it.first.contains("ENGLISH") }?.let { addIfNotExists(it) }
        // Luego MAIN CARD
        uniqueLinks.find { it.first.contains("MAIN CARD") }?.let { addIfNotExists(it) }
        // Luego PELEA ESTELAR
        uniqueLinks.find { it.first.contains("PELEA ESTELAR") }?.let { addIfNotExists(it) }
        // Luego PRELIMINARES
        uniqueLinks.find { it.first.contains("PRELIMINARES") }?.let { addIfNotExists(it) }

        // Agregar el resto que no esté ya en la lista
        uniqueLinks.filterNot { link -> orderedLinks.any { it.second == link.second } }
            .forEach { addIfNotExists(it) }

        // Limitar a máximo 10 enlaces
        val finalLinks = if (orderedLinks.size > 10) orderedLinks.subList(0, 10) else orderedLinks

        // Crear episodios (temporada 1, episodios 1, 2, 3...)
        val episodes = finalLinks.mapIndexed { index, (name, link) ->
            newEpisode(link) {
                this.name = name.ifBlank { "OPCIÓN ${index + 1}" }
                this.season = 1
                this.episode = index + 1
            }
        }

        // BUSCAR SUGERENCIAS SIMPLES (solo prev y next)
        val recommendations = mutableListOf<SearchResponse>()

        // Buscar enlaces "prev" y "next" en el HTML
        document.select("a[rel='prev'], a[rel='next']").forEach { linkElement ->
            val suggestionUrl = linkElement.attr("abs:href")
            val suggestionTitle = linkElement.text().trim()

            // Solo agregar si no es la página actual
            if (suggestionUrl.isNotBlank() && suggestionTitle.isNotBlank() && suggestionUrl != url) {
                // Usar imagen por defecto para sugerencias (más rápido)
                recommendations.add(
                    newAnimeSearchResponse(suggestionTitle, suggestionUrl, TvType.TvSeries) {
                        this.posterUrl = defaultPoster
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = null
            this.tags = listOf("Lucha Libre", "Wrestling", "Deportes")

            // Agregar las recomendaciones (solo si hay)
            if (recommendations.isNotEmpty()) {
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' es la URL específica del episodio (ej: https://tv.latinluchas.com/tv/opcion1)
        val document = app.get(data).document

        // Buscar iframes que contengan los videos
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("abs:src").ifBlank { iframe.attr("abs:data-src") }.ifBlank { iframe.attr("src") }
            if (src.isNullOrBlank()) return@forEach

            // Convertir URL relativa a absoluta
            if (src.startsWith("//")) src = "https:$src"

            // Filtrar publicidad
            if (src.contains("facebook", ignoreCase = true) ||
                src.contains("google", ignoreCase = true) ||
                src.contains("ads", ignoreCase = true) ||
                src.contains("twitter", ignoreCase = true)) {
                return@forEach
            }

            // Usar nuestro extractor personalizado para upns.online
            if (src.contains("upns.online") || src.contains("uns.wtf")) {
                LatinLuchaUpns().getUrl(src, data, subtitleCallback, callback)
            } else {
                // Para otros servidores, usar extractores genéricos
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // También buscar en scripts por si acaso
        document.select("script").forEach { script ->
            val scriptText = script.html()
            // Buscar URLs de video en scripts
            val patterns = listOf(
                """src=["'](https?://[^"']+\.(m3u8|mp4)[^"']*)["']""",
                """file["']?:\s*["'](https?://[^"']+\.(m3u8|mp4)[^"']*)["']""",
                """["'](https?://[^"']+/embed/[^"']+)["']"""
            )

            patterns.forEach { pattern ->
                try {
                    val regex = Regex(pattern)
                    val match = regex.find(scriptText)
                    match?.let {
                        val videoUrl = it.groupValues[1]
                        if (videoUrl.isNotBlank()) {
                            loadExtractor(videoUrl, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // Continuar
                }
            }
        }

        return true
    }
}
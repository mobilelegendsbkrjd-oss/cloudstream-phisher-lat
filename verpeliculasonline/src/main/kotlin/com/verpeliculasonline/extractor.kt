package com.verpeliculasonline.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.newExtractorLink

class Opuxa : ExtractorApi() {
    override var name = "Opuxa"
    override var mainUrl = "https://opuxa.lat"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val doc = app.get(url).document
            val links = mutableListOf<ExtractorLink>()
            
            // Buscar el iframe con el video
            val iframe = doc.selectFirst("iframe[src*='/e/']")
            val iframeSrc = iframe?.attr("src")
            
            if (iframeSrc != null) {
                val videoUrl = iframeSrc
                
                // Extraer el ID del video de la URL
                val videoId = iframeSrc.substringAfter("/e/").substringBefore("?")
                
                // Construir URLs para diferentes calidades
                val qualities = listOf(
                    "1080" to Qualities.P1080,
                    "720" to Qualities.P720,
                    "480" to Qualities.P480,
                    "360" to Qualities.P360,
                    "240" to Qualities.P240
                )
                
                qualities.forEach { (qualityStr, quality) ->
                    val directUrl = "https://opuxa.lat/dl/$videoId/$qualityStr"
                    
                    links.add(
                        newExtractorLink(
                            this.name,
                            "${this.name} $qualityStr",
                            url = directUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer ?: url
                            this.quality = quality.value
                        }
                    )
                }
                
                // También agregar el iframe directo
                links.add(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            
            // Buscar en scripts para enlaces directos
            doc.select("script").forEach { script ->
                val scriptContent = script.html()
                val patterns = listOf(
                    "https?:\\/\\/[^\"'\\s]+\\.(mp4|m3u8|webm)[^\"'\\s]*",
                    "file\\s*:\\s*\"(https?:\\/\\/[^\"']+)\"",
                    "src\\s*:\\s*\"(https?:\\/\\/[^\"']+)\""
                )
                
                patterns.forEach { pattern ->
                    val regex = Regex(pattern)
                    val matches = regex.findAll(scriptContent)
                    matches.forEach { match ->
                        val videoLink = match.value
                        if (videoLink.contains("opuxa") && (videoLink.contains(".mp4") || videoLink.contains(".m3u8"))) {
                            val isM3u8 = videoLink.contains(".m3u8")
                            links.add(
                                newExtractorLink(
                                    this.name,
                                    this.name,
                                    url = videoLink,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer ?: url
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
            }
            
            return if (links.isNotEmpty()) links else null
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
object Embed69Extractor {
    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).document.select("script")
            .firstOrNull { it.html().contains("dataLink = [") }?.html()
            ?.substringAfter("dataLink = ")
            ?.substringBefore(";")?.let { jsonStr ->

                val serversByLang = AppUtils.tryParseJson<List<ServersByLang>>(jsonStr) ?: return@let

                val allLinks = mutableListOf<ExtractorLink>()

                serversByLang.amap { lang ->
                    val jsonData = LinksRequest(lang.sortedEmbeds.mapNotNull { it.link })
                    val body = jsonData.toJson()
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                    val decrypted = app.post("https://embed69.org/api/decrypt", requestBody = body)
                        .parsedSafe<Loadlinks>()

                    if (decrypted?.success == true) {
                        decrypted.links.amap { linkData ->
                            loadExtractor(
                                fixHostsLinks(linkData.link),
                                referer,
                                subtitleCallback
                            ) { baseLink ->
                                val langPrefix = lang.videoLanguage?.uppercase() ?: "??"
                                val processedLink = newExtractorLink(
                                    "\( langPrefix[ \){baseLink.source}]",
                                    "$langPrefix - ${baseLink.source}",
                                    baseLink.url,
                                ) {
                                    this.quality = baseLink.quality
                                    this.type = baseLink.type
                                    this.referer = baseLink.referer
                                    this.headers = baseLink.headers
                                    this.extractorData = baseLink.extractorData
                                }
                                allLinks.add(processedLink)
                            }
                        }
                    }
                }

                // Ordenamiento: LAT primero, luego SUB, luego CAS, resto al final
                val priorityMap = mapOf(
                    "LAT" to 0,
                    "LATINO" to 0,     // por si aparece como LATINO
                    "SUB" to 1,
                    "SUBTITULADO" to 1,
                    "CAS" to 2,
                    "CAST" to 2,
                    "CASTELLANO" to 2
                )

                val sortedLinks = allLinks.sortedBy { link ->
                    val upperName = link.name.uppercase()
                    priorityMap.entries
                        .firstOrNull { it.key in upperName }
                        ?.value
                        ?: 999  // todo lo demás al final
                }

                // Enviamos en el orden deseado
                sortedLinks.forEach { callback(it) }
            }
    }
}

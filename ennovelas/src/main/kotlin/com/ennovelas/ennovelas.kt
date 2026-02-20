override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean = coroutineScope {
    val episodeDoc = app.get(data).document

    // Encuentra botón "Ver Capítulo"
    val proxyUrl = episodeDoc.selectFirst("a[href*='a.poiw.online/enn.php?post=']")?.attr("href")
        ?: return@coroutineScope false

    // Carga la página del proxy con referer del episodio (como en DoramasFlix)
    val proxyResponse = app.get(proxyUrl, referer = data)
    if (!proxyResponse.isSuccessful) return@coroutineScope false
    val proxyDoc = proxyResponse.document

    // Extrae iframes (como en PelisplusHD)
    val iframes = proxyDoc.select("iframe[src]").map { it.attr("abs:src") }.filter { it.isNotBlank() }

    // Extrae posibles scripts con video source (como en Cuevana)
    val scriptUrls = proxyDoc.select("script").mapNotNull { script ->
        script.data().takeIf { it.contains("url =") || it.contains("file:") }
            ?.substringAfter("url = '")?.substringBefore("'")
            ?.ifBlank { null }
    }

    val allEmbeds = (iframes + scriptUrls).distinct().filter { it.isNotBlank() }

    if (allEmbeds.isEmpty()) return@coroutineScope false

    var found = false

    allEmbeds.forEach { embed ->
        val cleanEmbed = embed.replace("\\/", "/")
            .replace("uqload.net", "uqload.to")
            .replace("vidspeeds.com", "vidsspeeds.com")

        val success = loadExtractor(
            url = cleanEmbed,
            referer = proxyUrl,  // Referer = página del proxy (clave)
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        if (success) found = true
    }

    return@coroutineScope found
}

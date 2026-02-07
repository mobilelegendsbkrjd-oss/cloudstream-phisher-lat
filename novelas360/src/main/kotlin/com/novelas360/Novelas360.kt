override suspend fun load(url: String): LoadResponse {
    val document = getDoc(url)

    // ✅ TÍTULO REAL
    val title = document.selectFirst("h4 span")
        ?.text()
        ?.trim()
        ?: document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.substringBefore("–")
            ?.trim()
        ?: "Novela"

    // ✅ DESCRIPCIÓN REAL (SEO)
    val plot = document.selectFirst("meta[name=description]")
        ?.attr("content")
        ?: document.selectFirst("meta[property=og:description]")
            ?.attr("content")

    // ✅ CAPÍTULOS
    val episodes = document.select("div.item h3 a").mapNotNull { link ->
        val epUrl = link.attr("href")
        val name = link.text().trim()

        if (epUrl.isBlank()) return@mapNotNull null
        if (name.contains("Próximamente", true)) return@mapNotNull null

        newEpisode(epUrl) {
            this.name = name
        }
    }

    return newTvSeriesLoadResponse(
        title,
        url,
        TvType.TvSeries,
        episodes
    ) {
        this.plot = plot
        this.posterUrl = fixUrl(
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
        )
    }
}

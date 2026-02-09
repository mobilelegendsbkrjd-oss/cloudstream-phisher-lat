// use an integer for version numbers
version = 3

cloudstream {
    description = "Cinecalidad - Películas y Series en Español"
    language = "es"
    authors = listOf("Stormunblessed")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=cinecalidad.ec&sz=%size%"

    isCrossPlatform = false
}
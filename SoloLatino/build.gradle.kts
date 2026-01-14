// use an integer for version numbers
version = 1

cloudstream {
    language = "es"
    
    authors = listOf("misajimenezmx")
    
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries", 
        "Anime",
        "Cartoon",
    )

    iconUrl = "https://sololatino.net/wp-content/uploads/2020/10/cropped-logo-final-192x192.png"
    
    // Opcional: Puedes agregar una descripción
    // description = "Ver películas, series y animes en español latino"
}
// use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "HDTodayz Movies & Series Extension"
    language    = "es"
    authors = listOf("bkrjd")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    // List of video source types.
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://img.hdtodayz.to/xxrz/100x100/100/ed/2a/ed2a7fa3244ddc585a0a0fdbaf835359/ed2a7fa3244ddc585a0a0fdbaf835359.png"

    isCrossPlatform = false
}

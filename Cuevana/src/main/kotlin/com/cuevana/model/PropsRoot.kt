package com.cuevana.model

import kotlinx.serialization.Serializable

@Serializable
data class PropsRoot(
    val props: Props? = null
)

@Serializable
data class Props(
    val pageProps: PageProps? = null
)

@Serializable
data class PageProps(
    val movies: List<MovieItem>? = null,
    val thisMovie: MovieItem? = null,
    val thisSerie: SerieItem? = null,
    val episode: EpisodeItem? = null
)

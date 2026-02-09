package com.cuevana.model

import kotlinx.serialization.Serializable

@Serializable
data class MovieItem(
    val titles: Titles? = null,
    val slug: Slug? = null,
    val overview: String? = null,
    val images: Images? = null,
    val seasons: List<Season>? = null
)

@Serializable
data class SerieItem(
    val titles: Titles? = null,
    val overview: String? = null,
    val images: Images? = null,
    val seasons: List<Season>? = null
)

@Serializable
data class Season(
    val season: Int? = null,
    val episodes: List<EpisodeItem>? = null
)

@Serializable
data class EpisodeItem(
    val number: Int? = null,
    val slug: Slug? = null,
    val videos: Videos? = null
)

@Serializable
data class Titles(
    val name: String? = null
)

@Serializable
data class Slug(
    val name: String? = null
)

@Serializable
data class Images(
    val poster: String? = null
)

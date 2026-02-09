package com.cuevana.model

import kotlinx.serialization.Serializable

@Serializable
data class Videos(
    val latino: List<VideoItem>? = null,
    val spanish: List<VideoItem>? = null,
    val english: List<VideoItem>? = null,
    val japanese: List<VideoItem>? = null
)

@Serializable
data class VideoItem(
    val result: String? = null
)

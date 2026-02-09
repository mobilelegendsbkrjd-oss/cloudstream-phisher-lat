package com.utelevision

data class UtelevisionLinkData(
    val id: String,
    val movie: String
)

data class GStreamResponse(
    val success: Boolean,
    val data: GStreamData?
)

data class GStreamData(
    val link: String,
    val token: String
)

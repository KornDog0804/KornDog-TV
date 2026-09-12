package com.lumora.data.remote.korndog

data class KornDogSearchRequest(
    val title: String,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null
)

package com.lumora.data.remote.korndog

interface KornDogSourceProvider {

    val id: String

    val displayName: String

    suspend fun search(
        request: KornDogSearchRequest
    ): List<KornDogSource>
}

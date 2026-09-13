package com.lumora.data.remote.korndog

class KornDogSourceRegistry(
    providers: List<KornDogSourceProvider> = emptyList()
) {

    private val providers =
        providers
            .distinctBy { it.id }

    val isConfigured: Boolean
        get() = providers.isNotEmpty()

    suspend fun search(
        request: KornDogSearchRequest
    ): List<KornDogSource> {

        val results =
            providers.flatMap { provider ->
                runCatching {
                    provider.search(request)
                }.getOrElse {
                    emptyList()
                }
            }

        /*
         * Only return candidates with actual source identity.
         */
        return results
            .filter {
                it.hasTorrentIdentity ||
                    it.hasDirectIdentity
            }
            .distinctBy {
                it.stableIdentity
            }
    }
}

package com.lumora.data.remote.korndog

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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

        /*
         * FAST AUTOPLAY DISCOVERY
         *
         * Providers are independent. Do not stack their network latency.
         * Race them concurrently and refuse to let one slow provider hold
         * the entire KornDog search hostage.
         *
         * Six seconds leaves roughly three seconds for direct verification
         * and Media3 English validation inside the ten-second launch target.
         */
        val searchStarted =
            android.os.SystemClock.elapsedRealtime()

        val results =
            coroutineScope {
                providers
                    .map { provider ->
                        async {
                            val providerStarted =
                                android.os.SystemClock.elapsedRealtime()

                            val providerResults =
                                withTimeoutOrNull(6000L) {
                                    runCatching {
                                        provider.search(request)
                                    }.getOrElse { error ->
                                        android.util.Log.w(
                                            "KornDogNative",
                                            "Provider failed: " +
                                                provider.javaClass.simpleName,
                                            error
                                        )

                                        emptyList()
                                    }
                                } ?: emptyList()

                            android.util.Log.i(
                                "KornDogNative",
                                "Provider " +
                                    provider.javaClass.simpleName +
                                    " returned " +
                                    "${providerResults.size} source(s) in " +
                                    "${android.os.SystemClock.elapsedRealtime() - providerStarted}ms"
                            )

                            providerResults
                        }
                    }
                    .awaitAll()
                    .flatten()
            }

        android.util.Log.i(
            "KornDogNative",
            "Parallel native search completed " +
                "${results.size} source(s) in " +
                "${android.os.SystemClock.elapsedRealtime() - searchStarted}ms"
        )

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

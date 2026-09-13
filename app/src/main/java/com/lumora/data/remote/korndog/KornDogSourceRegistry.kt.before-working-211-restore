package com.lumora.data.remote.korndog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

        /*
         * FIRST USEFUL BATCH
         *
         * A provider that already produced real sources should not be held
         * hostage by another provider's slow timeout. Once the first useful
         * batch arrives, give the remaining providers a short grace window
         * to contribute more candidates, then move on.
         *
         * If nobody finds anything, preserve the existing six-second search
         * budget before returning an empty result and allowing legacy fallback.
         */
        val providerScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val resultChannel =
            Channel<List<KornDogSource>>(Channel.UNLIMITED)

        providers.forEach { provider ->
            providerScope.launch {
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

                resultChannel.trySend(providerResults)
            }
        }

        val collected = mutableListOf<KornDogSource>()
        var providersReported = 0
        var firstUsefulAt: Long? = null

        while (providersReported < providers.size) {
            val now =
                android.os.SystemClock.elapsedRealtime()

            val remainingMs =
                if (firstUsefulAt != null) {
                    (1500L - (now - firstUsefulAt!!))
                        .coerceAtLeast(0L)
                } else {
                    (6000L - (now - searchStarted))
                        .coerceAtLeast(0L)
                }

            if (remainingMs <= 0L) {
                break
            }

            val providerResults =
                withTimeoutOrNull(remainingMs) {
                    resultChannel.receive()
                } ?: break

            providersReported++

            if (providerResults.isNotEmpty()) {
                collected += providerResults

                if (firstUsefulAt == null) {
                    firstUsefulAt =
                        android.os.SystemClock.elapsedRealtime()

                    android.util.Log.i(
                        "KornDogNative",
                        "First useful native batch arrived; " +
                            "opening 1500ms provider grace window"
                    )
                }
            }
        }

        resultChannel.close()
        providerScope.cancel()

        val results = collected

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

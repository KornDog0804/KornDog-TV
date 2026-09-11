package com.lumora.data.remote.debrid

import com.lumora.data.remote.premiumize.PremiumizeClient
import com.lumora.data.remote.torbox.TorBoxClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

object KornDogChoiceBuilder {

    data class Candidate(
        val title: String,
        val magnet: String,
        val quality: String?,
        val source: String?,
        val size: Long?,
        val season: Int?,
        val episode: Int?
    )

    suspend fun build(
        candidates: List<Candidate>,
        torBox: TorBoxClient?,
        premiumize: PremiumizeClient?
    ): List<KornDogResolvedChoice> = coroutineScope {

        val clean =
            candidates
                .distinctBy { it.magnet }
                .filter { candidate ->
                    KornDogFilePicker.score(
                        name = candidate.title,
                        size = candidate.size ?: 0L,
                        season = candidate.season,
                        episode = candidate.episode
                    ) > Int.MIN_VALUE
                }
                .sortedByDescending { candidate ->
                    KornDogFilePicker.score(
                        name = candidate.title,
                        size = candidate.size ?: 0L,
                        season = candidate.season,
                        episode = candidate.episode
                    )
                }
                .take(12)

        if (clean.isEmpty()) {
            return@coroutineScope emptyList()
        }

        val torBoxChoices =
            async {
                if (torBox == null) {
                    emptyList()
                } else {
                    clean.map { candidate ->
                        async {
                            val hash =
                                Regex(
                                    """(?i)btih:([a-f0-9]{40}|[a-z2-7]{32})"""
                                )
                                    .find(candidate.magnet)
                                    ?.groupValues
                                    ?.getOrNull(1)

                            val cached =
                                hash?.let {
                                    withTimeoutOrNull(1500L) {
                                        runCatching {
                                            torBox.checkCached(it)
                                        }.getOrDefault(false)
                                    } ?: false
                                } ?: false

                            candidate to cached
                        }
                    }
                        .awaitAll()
                        .filter { it.second }
                        .take(2)
                        .map { (candidate, _) ->
                            KornDogResolvedChoice(
                                provider =
                                    KornDogResolvedChoice.Provider.TORBOX,
                                title = candidate.title,
                                magnet = candidate.magnet,
                                quality = candidate.quality,
                                source = candidate.source,
                                size = candidate.size,
                                cached = true,
                                score =
                                    KornDogFilePicker.score(
                                        candidate.title,
                                        candidate.size ?: 0L,
                                        candidate.season,
                                        candidate.episode
                                    )
                            )
                        }
                }
            }

        val premiumizeChoices =
            async {
                if (premiumize == null) {
                    emptyList()
                } else {
                    val cached =
                        withTimeoutOrNull(1800L) {
                            runCatching {
                                premiumize.checkCached(
                                    clean.map { it.magnet }
                                )
                            }.getOrElse {
                                List(clean.size) { false }
                            }
                        } ?: List(clean.size) { false }

                    clean.mapIndexedNotNull { index, candidate ->
                        if (!cached.getOrElse(index) { false }) {
                            null
                        } else {
                            KornDogResolvedChoice(
                                provider =
                                    KornDogResolvedChoice.Provider.PREMIUMIZE,
                                title = candidate.title,
                                magnet = candidate.magnet,
                                quality = candidate.quality,
                                source = candidate.source,
                                size = candidate.size,
                                cached = true,
                                score =
                                    KornDogFilePicker.score(
                                        candidate.title,
                                        candidate.size ?: 0L,
                                        candidate.season,
                                        candidate.episode
                                    )
                            )
                        }
                    }
                        .take(2)
                }
            }

        val tb = torBoxChoices.await()
        val pm = premiumizeChoices.await()

        /*
         * Real-Debrid remains the third resolver path.
         *
         * We intentionally do NOT add every raw candidate to RD here.
         * That would make source discovery slower and create unnecessary
         * torrent mutations just to build the chooser.
         *
         * RD gets the best remaining candidates later at resolution time.
         */
        val rd =
            clean
                .take(2)
                .map { candidate ->
                    KornDogResolvedChoice(
                        provider =
                            KornDogResolvedChoice.Provider.REAL_DEBRID,
                        title = candidate.title,
                        magnet = candidate.magnet,
                        quality = candidate.quality,
                        source = candidate.source,
                        size = candidate.size,
                        cached = false,
                        score =
                            KornDogFilePicker.score(
                                candidate.title,
                                candidate.size ?: 0L,
                                candidate.season,
                                candidate.episode
                            )
                    )
                }

        (tb + pm + rd)
            .sortedWith(
                compareByDescending<KornDogResolvedChoice> {
                    it.cached
                }.thenByDescending {
                    it.score
                }
            )
    }
}

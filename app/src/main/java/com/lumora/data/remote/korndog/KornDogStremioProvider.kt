package com.lumora.data.remote.korndog

import com.lumora.data.remote.stremio.StremioAddonClient

class KornDogStremioProvider(
    override val id: String,
    override val displayName: String,
    private val manifestUrl: String,
    private val client: StremioAddonClient =
        StremioAddonClient()
) : KornDogSourceProvider {

    override suspend fun search(
        request: KornDogSearchRequest
    ): List<KornDogSource> {

        val imdbId =
            request.imdbId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return emptyList()

        val isEpisode =
            request.season != null &&
                request.episode != null

        val type =
            if (isEpisode) {
                "series"
            } else {
                "movie"
            }

        val contentId =
            if (isEpisode) {
                "$imdbId:${request.season}:${request.episode}"
            } else {
                imdbId
            }

        val manifest =
            client.fetchManifest(manifestUrl)
                .getOrElse {
                    return emptyList()
                }

        val streams =
            client.streams(
                manifest = manifest,
                type = type,
                contentId = contentId
            ).getOrElse {
                return emptyList()
            }

        return client
            .toKornDogSources(
                manifest = manifest,
                streams = streams
            )
            .map { source ->
                source.copy(
                    season =
                        request.season
                            ?: source.season,
                    episode =
                        request.episode
                            ?: source.episode
                )
            }
    }
}

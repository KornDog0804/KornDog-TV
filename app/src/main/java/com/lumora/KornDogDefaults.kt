package com.lumora

import com.lumora.data.IptvProviderStore
import com.lumora.data.local.entity.EpgSourceEntity
import com.lumora.model.IptvProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KORNDOG_PROVIDER_ID = "korndog-sports-default"
private const val KORNDOG_EPG_ID = "korndog-epg-default"

private const val KORNDOG_PLAYLIST_URL =
    "https://korndog-sports-addon.netlify.app/playlist.m3u"

private const val KORNDOG_EPG_URL =
    "https://korndog-sports-addon.netlify.app/epg.xml"

internal fun MainActivity.seedKornDogDefaults() {
    val providers = IptvProviderStore.load(prefs)

    if (providers.none { it.url == KORNDOG_PLAYLIST_URL }) {
        IptvProviderStore.upsert(
            prefs,
            IptvProviderConfig(
                id = KORNDOG_PROVIDER_ID,
                type = "m3u",
                name = "KornDog Sports",
                enabled = true,
                liveEnabled = true,
                moviesEnabled = false,
                seriesEnabled = false,
                url = KORNDOG_PLAYLIST_URL
            )
        )
    }

    scope.launch {
        withContext(Dispatchers.IO) {
            val existing = database.epgSourceDao().getAll()

            if (existing.none { it.url == KORNDOG_EPG_URL }) {
                database.epgSourceDao().insert(
                    EpgSourceEntity(
                        id = KORNDOG_EPG_ID,
                        name = "KornDog EPG",
                        url = KORNDOG_EPG_URL,
                        enabled = true,
                        priority = 0,
                        refreshIntervalHours = 6
                    )
                )
            }
        }
    }
}

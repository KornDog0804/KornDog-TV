package com.lumora.data.remote.debrid

data class KornDogResolvedChoice(
    val provider: Provider,
    val title: String,
    val magnet: String,
    val quality: String?,
    val source: String?,
    val size: Long?,
    val cached: Boolean,
    val score: Int
) {
    enum class Provider {
        TORBOX,
        PREMIUMIZE,
        REAL_DEBRID
    }

    val providerLabel: String
        get() = when (provider) {
            Provider.TORBOX -> "TorBox"
            Provider.PREMIUMIZE -> "Premiumize"
            Provider.REAL_DEBRID -> "Real-Debrid"
        }
}

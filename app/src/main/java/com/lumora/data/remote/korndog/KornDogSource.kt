package com.lumora.data.remote.korndog

enum class KornDogSourceType {
    TORRENT,
    DIRECT
}

data class KornDogSource(
    val title: String,
    val type: KornDogSourceType,

    /*
     * Preserve source identity.
     *
     * Do not collapse an infoHash/file index into an opaque URL.
     * KornDog should retain the facts that were returned by discovery.
     */
    val infoHash: String? = null,
    val magnet: String? = null,
    val fileIdx: Int? = null,

    /*
     * Direct media identity.
     *
     * This should be an actual playable/resolveable HTTP source, not merely
     * provider branding or a display page.
     */
    val directUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),

    /*
     * Discovery metadata.
     */
    val provider: String,
    val quality: String? = null,
    val size: Long? = null,
    val language: String? = null,

    /*
     * Exact content identity.
     */
    val season: Int? = null,
    val episode: Int? = null
) {
    val hasTorrentIdentity: Boolean
        get() =
            !magnet.isNullOrBlank() ||
                !infoHash.isNullOrBlank()

    val hasDirectIdentity: Boolean
        get() =
            !directUrl.isNullOrBlank()

    val stableIdentity: String
        get() =
            when {
                !infoHash.isNullOrBlank() ->
                    "torrent:${infoHash.lowercase()}:${fileIdx ?: -1}"

                !magnet.isNullOrBlank() ->
                    "magnet:${magnet.substringBefore("&dn=")}"

                !directUrl.isNullOrBlank() ->
                    "direct:$directUrl"

                else ->
                    "unknown:$provider:$title"
            }
}

package com.lumora.data.remote.concerts

import com.lumora.model.Channel
import com.lumora.model.ContentShelf
import com.lumora.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class ConcertCatalogClient(
    private val http: OkHttpClient = OkHttpClient()
) {
    suspend fun loadShelves(): Result<List<ContentShelf>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(CATALOG_URL)
                    .header("Accept", "application/json")
                    .header("User-Agent", "KornDog-TV/Concert-Corner")
                    .build()

                val body = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Concert catalog returned HTTP ${response.code}")
                    }

                    response.body?.string()
                        ?: error("Concert catalog was empty")
                }

                val root = JSONArray(body)

                buildList {
                    for (index in 0 until root.length()) {
                        val section = root.optJSONObject(index) ?: continue
                        val title = section.optString("title").trim()

                        // Music only. Monster Jam and Drag Racing are deliberately excluded.
                        if (title !in ALLOWED_SECTIONS) continue

                        val items = mutableListOf<Channel>()
                        collectPlayableItems(
                            node = section,
                            sectionTitle = cleanSectionTitle(title),
                            output = items
                        )

                        if (items.isNotEmpty()) {
                            add(
                                ContentShelf(
                                    title = cleanSectionTitle(title),
                                    items = items.distinctBy { it.id }
                                )
                            )
                        }
                    }
                }
            }
        }

    private fun collectPlayableItems(
        node: JSONObject,
        sectionTitle: String,
        output: MutableList<Channel>
    ) {
        val mode = node.optString("mode")
        val tracks = node.optJSONArray("tracks")

        if (
            mode in PLAYABLE_MODES &&
            tracks != null &&
            tracks.length() > 0
        ) {
            val firstTrack = tracks.optJSONObject(0)
            val firstUrl = firstTrack?.optString("url")
                ?.takeIf(String::isNotBlank)

            if (firstUrl != null) {
                val title = node.optString("title")
                    .ifBlank {
                        firstTrack.optString("title")
                            .ifBlank { "Concert" }
                    }

                val artist = node.optString("artist")
                    .takeIf(String::isNotBlank)

                val year = node.opt("year")
                    ?.toString()
                    ?.takeIf(String::isNotBlank)

                val thumbnail = node.optString("thumb")
                    .takeIf {
                        it.startsWith("http://") ||
                            it.startsWith("https://")
                    }
                    ?: youtubeThumbnail(firstUrl)

                output += Channel(
                    id = "concert:${stableHash("$title|$firstUrl")}",
                    name = title,
                    url = firstUrl,
                    posterUrl = thumbnail,
                    backdropUrl = thumbnail,
                    group = "Concert Corner",
                    mediaType = MediaType.MOVIE,
                    categoryName = sectionTitle,
                    description = buildString {
                        artist?.let { append(it) }

                        if (tracks.length() > 1) {
                            if (isNotEmpty()) append(" · ")
                            append("${tracks.length()} tracks")
                        }
                    }.takeIf(String::isNotBlank),
                    year = year
                )
            }
        }

        val children = node.optJSONArray("items") ?: return

        for (index in 0 until children.length()) {
            children.optJSONObject(index)?.let { child ->
                collectPlayableItems(
                    node = child,
                    sectionTitle = sectionTitle,
                    output = output
                )
            }
        }
    }

    private fun cleanSectionTitle(title: String): String =
        title.replace(
            Regex("^[^A-Za-z0-9]+\\s*"),
            ""
        ).trim()

    private fun youtubeThumbnail(url: String): String? {
        val videoId = when {
            "youtu.be/" in url ->
                url.substringAfter("youtu.be/")
                    .substringBefore("?")
                    .substringBefore("&")

            "youtube.com/watch" in url ->
                url.substringAfter("v=", "")
                    .substringBefore("&")

            else -> ""
        }.takeIf(String::isNotBlank)

        return videoId?.let {
            "https://img.youtube.com/vi/$it/hqdefault.jpg"
        }
    }

    private fun stableHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val CATALOG_URL =
            "https://raw.githubusercontent.com/KornDog0804/Unplugged-channel-/main/episodes.json"

        private val ALLOWED_SECTIONS = setOf(
            "📺 MTV Unplugged",
            "🎙 Tiny Desk",
            "🎛 Stitched Streams / Full Sessions",
            "🎤 Live Concerts"
        )

        private val PLAYABLE_MODES = setOf(
            "fullshow",
            "queue",
            "playlist"
        )
    }
}

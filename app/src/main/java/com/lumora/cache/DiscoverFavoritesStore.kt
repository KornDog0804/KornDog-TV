package com.lumora.cache

import android.content.Context
import com.lumora.model.Channel
import com.lumora.model.MediaType
import org.json.JSONArray
import org.json.JSONObject

object DiscoverFavoritesStore {

    private const val PREFS_NAME = "iptv_prefs"
    private const val KEY = "discover_favorite_snapshots"

    fun getAll(context: Context): List<Channel> {
        val raw = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)

            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue

                    val id = o.optString("id")
                    val name = o.optString("name")

                    if (id.isBlank() || name.isBlank()) continue

                    add(
                        Channel(
                            id = id,
                            name = name,
                            url = "",
                            posterUrl = o.optString("posterUrl")
                                .takeIf { it.isNotBlank() },
                            backdropUrl = o.optString("backdropUrl")
                                .takeIf { it.isNotBlank() },
                            mediaType = runCatching {
                                MediaType.valueOf(o.optString("mediaType"))
                            }.getOrDefault(MediaType.MOVIE),
                            year = o.optString("year")
                                .takeIf { it.isNotBlank() },
                            description = o.optString("description")
                                .takeIf { it.isNotBlank() },
                            rating = o.optString("rating")
                                .takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun set(context: Context, item: Channel, favorite: Boolean) {
        val current = getAll(context).toMutableList()

        current.removeAll { it.id == item.id }

        if (favorite) {
            current.add(0, item)
        }

        val array = JSONArray()

        current.distinctBy { it.id }.forEach { ch ->
            array.put(
                JSONObject().apply {
                    put("id", ch.id)
                    put("name", ch.name)
                    put("mediaType", ch.mediaType.name)

                    ch.posterUrl?.let { put("posterUrl", it) }
                    ch.backdropUrl?.let { put("backdropUrl", it) }
                    ch.year?.let { put("year", it) }
                    ch.description?.let { put("description", it) }
                    ch.rating?.let { put("rating", it) }
                }
            )
        }

        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}

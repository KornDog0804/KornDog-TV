package com.lumora.data.remote.stremio

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StremioAddonConfig(
    val id: String,
    val name: String,
    val manifestUrl: String,
    val enabled: Boolean = true
)

object StremioAddonStore {

    private const val KEY = "stremio_addons_json"


    fun load(prefs: SharedPreferences): List<StremioAddonConfig> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()

        val saved = runCatching {
            val array = JSONArray(raw)

            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("manifestUrl").trim()

                    if (
                        !url.startsWith("https://") &&
                        !url.startsWith("http://")
                    ) {
                        continue
                    }

                    add(
                        StremioAddonConfig(
                            id = item.optString("id")
                                .ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name")
                                .ifBlank { "Stremio Addon" },
                            manifestUrl = url,
                            enabled = item.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        return saved
    }

    fun save(
        prefs: SharedPreferences,
        addons: List<StremioAddonConfig>
    ) {
        val array = JSONArray()

        addons.forEach { addon ->
            array.put(
                JSONObject().apply {
                    put("id", addon.id)
                    put("name", addon.name)
                    put("manifestUrl", addon.manifestUrl)
                    put("enabled", addon.enabled)
                }
            )
        }

        prefs.edit()
            .putString(KEY, array.toString())
            .apply()
    }

    fun add(
        prefs: SharedPreferences,
        name: String,
        manifestUrl: String
    ): List<StremioAddonConfig> {
        val normalized = manifestUrl.trim()
        val current = load(prefs).toMutableList()

        if (current.any {
                it.manifestUrl.equals(normalized, ignoreCase = true)
            }
        ) {
            return current
        }

        current.add(
            StremioAddonConfig(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { "Stremio Addon" },
                manifestUrl = normalized
            )
        )

        save(prefs, current)
        return current
    }

    fun remove(
        prefs: SharedPreferences,
        id: String
    ): List<StremioAddonConfig> {
        val updated = load(prefs).filterNot { it.id == id }
        save(prefs, updated)
        return updated
    }

    fun setEnabled(
        prefs: SharedPreferences,
        id: String,
        enabled: Boolean
    ): List<StremioAddonConfig> {
        val updated = load(prefs).map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }

        save(prefs, updated)
        return updated
    }
}

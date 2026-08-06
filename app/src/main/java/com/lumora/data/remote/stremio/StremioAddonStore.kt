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

    private val DEFAULT_ELFHOSTED = StremioAddonConfig(
        id = "builtin-comet-elfhosted",
        name = "Comet | ElfHosted",
        manifestUrl = "https://comet.elfhosted.com/eyJtYXhSZXN1bHRzUGVyUmVzb2x1dGlvbiI6MCwibWF4U2l6ZSI6MCwiY2FjaGVkT25seSI6ZmFsc2UsInNvcnRDYWNoZWRVbmNhY2hlZFRvZ2V0aGVyIjpmYWxzZSwicmVtb3ZlVHJhc2giOnRydWUsInJlc3VsdEZvcm1hdCI6WyJhbGwiXSwiZGVicmlkU2VydmljZXMiOltdLCJlbmFibGVUb3JyZW50IjpmYWxzZSwiZGVkdXBsaWNhdGVTdHJlYW1zIjpmYWxzZSwic2NyYXBlRGVicmlkQWNjb3VudFRvcnJlbnRzIjpmYWxzZSwiZGVicmlkU3RyZWFtUHJveHlQYXNzd29yZCI6IiIsImxhbmd1YWdlcyI6eyJyZXF1aXJlZCI6W10sImFsbG93ZWQiOltdLCJleGNsdWRlIjpbXSwicHJlZmVycmVkIjpbXX0sInJlc29sdXRpb25zIjp7fSwib3B0aW9ucyI6eyJyZW1vdmVfcmFua3NfdW5kZXIiOi0xMDAwMDAwMDAwMCwiYWxsb3dfZW5nbGlzaF9pbl9sYW5ndWFnZXMiOmZhbHNlLCJyZW1vdmVfdW5rbm93bl9sYW5ndWFnZXMiOmZhbHNlfX0=/manifest.json",
        enabled = true
    )

    fun load(prefs: SharedPreferences): List<StremioAddonConfig> {
        val raw = prefs.getString(KEY, null)

        if (raw == null) {
            return listOf(DEFAULT_ELFHOSTED)
        }

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

        val savedDefault = saved.firstOrNull {
            it.id == DEFAULT_ELFHOSTED.id ||
                it.manifestUrl == DEFAULT_ELFHOSTED.manifestUrl
        }

        return listOf(savedDefault ?: DEFAULT_ELFHOSTED) +
            saved.filterNot {
                it.id == DEFAULT_ELFHOSTED.id ||
                    it.manifestUrl == DEFAULT_ELFHOSTED.manifestUrl
            }
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

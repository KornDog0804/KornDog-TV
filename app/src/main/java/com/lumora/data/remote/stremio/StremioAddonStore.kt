package com.lumora.data.remote.stremio

import android.content.SharedPreferences
import android.util.Base64
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


    /**
     * Relax only Comet's server-side language filtering.
     * Lumora chooses playback audio itself, so Comet should return the
     * available candidates instead of rejecting untagged releases.
     */
    private fun normalizeManifestUrl(manifestUrl: String): String {
        val original = manifestUrl.trim()

        return runCatching {
            val uri = android.net.Uri.parse(original)

            if (!uri.host.equals("comet.elfhosted.com", ignoreCase = true)) {
                return@runCatching original
            }

            val segments = uri.pathSegments

            if (segments.size != 2 || segments[1] != "manifest.json") {
                return@runCatching original
            }

            val token = segments[0]

            val decoded = String(
                Base64.decode(
                    token,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ),
                Charsets.UTF_8
            )

            val config = JSONObject(decoded)

            config.put(
                "languages",
                JSONObject().apply {
                    put("required", JSONArray())
                    put("allowed", JSONArray())
                    put("exclude", JSONArray())
                    put("preferred", JSONArray())
                }
            )

            val normalizedToken = Base64.encodeToString(
                config.toString().toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            uri.buildUpon()
                .path("/$normalizedToken/manifest.json")
                .build()
                .toString()
        }.getOrDefault(original)
    }


    fun load(prefs: SharedPreferences): List<StremioAddonConfig> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()

        val saved = runCatching {
            val array = JSONArray(raw)

            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = normalizeManifestUrl(
                    item.optString("manifestUrl").trim()
                )

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

        // Persist any URL normalization performed while loading.
        // This permanently migrates older Comet configs whose server-side
        // language restriction could incorrectly turn valid results into zero.
        val rawUrls = runCatching {
            val array = JSONArray(raw)

            buildList {
                for (index in 0 until array.length()) {
                    add(
                        array.optJSONObject(index)
                            ?.optString("manifestUrl")
                            ?.trim()
                            .orEmpty()
                    )
                }
            }
        }.getOrDefault(emptyList())

        if (
            saved.size == rawUrls.size &&
            saved.indices.any { index ->
                saved[index].manifestUrl != rawUrls[index]
            }
        ) {
            save(prefs, saved)
        }

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
        val normalized = normalizeManifestUrl(manifestUrl)
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

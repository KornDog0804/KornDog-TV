package com.lumora.data.remote.stremio

import com.lumora.plugin.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class StremioAddonManifest(
    val id: String,
    val name: String,
    val version: String?,
    val baseUrl: String,
    val resources: Set<String>,
    val types: Set<String>
)

data class StremioStream(
    val title: String,
    val url: String? = null,
    val magnet: String? = null,
    val source: String? = null,
    val behaviorHints: Map<String, String> = emptyMap()
)

class StremioAddonClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun fetchManifest(
        manifestUrl: String
    ): Result<StremioAddonManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = normalizeManifestUrl(manifestUrl)
            val body = getText(normalized)
                ?: error("Could not load addon manifest")

            val json = JSONObject(body)
            val id = json.optString("id").trim()
            val name = json.optString("name").trim()

            if (id.isBlank() || name.isBlank()) {
                error("Invalid Stremio manifest")
            }

            val resources = json.optJSONArray("resources")
                ?.let { array ->
                    buildSet {
                        for (i in 0 until array.length()) {
                            val item = array.opt(i)
                            when (item) {
                                is String -> add(item)
                                is JSONObject -> {
                                    item.optString("name")
                                        .takeIf(String::isNotBlank)
                                        ?.let(::add)
                                }
                            }
                        }
                    }
                }
                .orEmpty()

            val types = json.optJSONArray("types")
                ?.let { array ->
                    buildSet {
                        for (i in 0 until array.length()) {
                            array.optString(i)
                                .takeIf(String::isNotBlank)
                                ?.let(::add)
                        }
                    }
                }
                .orEmpty()

            StremioAddonManifest(
                id = id,
                name = name,
                version = json.optString("version")
                    .takeIf(String::isNotBlank),
                baseUrl = normalized.substringBeforeLast("/manifest.json"),
                resources = resources,
                types = types
            )
        }
    }

    suspend fun streams(
        manifest: StremioAddonManifest,
        type: String,
        contentId: String
    ): Result<List<StremioStream>> = withContext(Dispatchers.IO) {
        runCatching {
            if ("stream" !in manifest.resources) {
                return@runCatching emptyList()
            }

            val encodedId = URLEncoder.encode(contentId, "UTF-8")
                .replace("+", "%20")

            val url =
                "${manifest.baseUrl}/stream/$type/$encodedId.json"

            val body = getText(url)
                ?: error("Addon stream request failed")

            val json = JSONObject(body)
            val array = json.optJSONArray("streams")
                ?: return@runCatching emptyList()

            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue

                    val title = item.optString("title")
                        .ifBlank { item.optString("name") }
                        .ifBlank { "Stremio stream" }
                        .take(250)

                    val directUrl = item.optString("url")
                        .takeIf {
                            it.startsWith("http://") ||
                                it.startsWith("https://")
                        }

                    val infoHash = item.optString("infoHash")
                        .takeIf(String::isNotBlank)

                    val fileIdx = if (item.has("fileIdx")) {
                        item.optInt("fileIdx", -1)
                            .takeIf { it >= 0 }
                    } else {
                        null
                    }

                    val magnet = when {
                        item.optString("url")
                            .startsWith("magnet:") -> item.optString("url")

                        infoHash != null -> buildMagnet(
                            infoHash = infoHash,
                            displayName = title,
                            fileIndex = fileIdx
                        )

                        else -> null
                    }

                    if (directUrl == null && magnet == null) continue

                    val hints = mutableMapOf<String, String>()
                    item.optJSONObject("behaviorHints")?.let { behavior ->
                        behavior.keys().forEach { key ->
                            behavior.optString(key)
                                .takeIf(String::isNotBlank)
                                ?.let { value -> hints[key] = value }
                        }
                    }

                    add(
                        StremioStream(
                            title = title,
                            url = directUrl,
                            magnet = magnet,
                            source = manifest.name,
                            behaviorHints = hints
                        )
                    )
                }
            }
        }
    }

    fun torrentResults(
        streams: List<StremioStream>
    ): List<TorrentResult> =
        streams.mapNotNull { stream ->
            val token = stream.url ?: stream.magnet
                ?: return@mapNotNull null

            TorrentResult(
                title = stream.title,
                token = token,
                seeders = null,
                size = null,
                quality = qualityFrom(stream.title),
                source = stream.source,
                audio = null
            )
        }

    private fun normalizeManifestUrl(value: String): String {
        val trimmed = value.trim().removeSuffix("/")

        require(
            trimmed.startsWith("https://") ||
                trimmed.startsWith("http://")
        ) {
            "Manifest URL must use HTTP or HTTPS"
        }

        return if (trimmed.endsWith("/manifest.json")) {
            trimmed
        } else {
            "$trimmed/manifest.json"
        }
    }

    private fun getText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "KornDog-TV/1.0")
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildMagnet(
        infoHash: String,
        displayName: String,
        fileIndex: Int?
    ): String {
        val encodedName = URLEncoder.encode(
            displayName,
            "UTF-8"
        )

        return buildString {
            append("magnet:?xt=urn:btih:")
            append(infoHash)
            append("&dn=")
            append(encodedName)

            if (fileIndex != null) {
                append("&fileIdx=")
                append(fileIndex)
            }
        }
    }

    private fun qualityFrom(title: String): String? {
        val match = Regex(
            "(2160p|4k|1080p|720p|480p)",
            RegexOption.IGNORE_CASE
        ).find(title)

        return match?.value
    }
}

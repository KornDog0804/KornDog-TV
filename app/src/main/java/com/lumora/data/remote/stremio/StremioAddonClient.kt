package com.lumora.data.remote.stremio

import com.lumora.data.remote.korndog.KornDogSource
import com.lumora.data.remote.korndog.KornDogSourceType

import com.lumora.plugin.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import android.util.Log

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
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val source: String? = null,
    val description: String? = null,
    val filename: String? = null,
    val audioLanguage: String? = null,
    val behaviorHints: Map<String, String> = emptyMap(),
    val requestHeaders: Map<String, String> = emptyMap()
)


data class StremioSubtitle(
    val url: String,
    val lang: String? = null,
    val label: String? = null,
    val source: String? = null
)

class StremioAddonClient {

    private fun inferAudioLanguage(vararg values: String?): String? {
        val text = values
            .filterNotNull()
            .joinToString(" ")
            .lowercase()

        if (text.isBlank()) return null

        fun has(pattern: String): Boolean =
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)

        val hasEnglish = has("""\b(eng|english|en)\b""")
        val hasRussian = has("""\b(rus|russian|ru)\b""")
        val hasJapanese = has("""\b(jpn|japanese|ja|jap)\b""")
        val hasChinese = has("""\b(chi|chinese|zho|zh|chs|cht)\b""")
        val hasSpanish = has("""\b(spa|spanish|es|latino|castellano)\b""")
        val hasFrench = has("""\b(fre|fra|french|fr)\b""")
        val hasGerman = has("""\b(ger|deu|german|de)\b""")
        val hasItalian = has("""\b(ita|italian|it)\b""")
        val hasKorean = has("""\b(kor|korean|ko)\b""")

        val found = buildList {
            if (hasEnglish) add("ENG")
            if (hasRussian) add("RUS")
            if (hasJapanese) add("JPN")
            if (hasChinese) add("CHI")
            if (hasSpanish) add("SPA")
            if (hasFrench) add("FRE")
            if (hasGerman) add("GER")
            if (hasItalian) add("ITA")
            if (hasKorean) add("KOR")
        }

        return when {
            found.size > 1 -> "MULTI ${found.joinToString("/")}"
            found.size == 1 -> found.first()
            has("""\b(multi|dual[\s._-]*audio|multi[\s._-]*audio)\b""") -> "MULTI"
            else -> null
        }
    }

    private fun stremioLog(message: String) {
        runCatching {
            java.io.File("/sdcard/Download/stremio.log")
                .appendText(
                    "${System.currentTimeMillis()}: $message\n"
                )
        }

        Log.d("StremioDebug", message)
    }

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

            // Some addons (AIOStreams among them) omit the manifest-level "types" array
            // and declare supported types per-resource instead - both are valid per the
            // Stremio addon spec. Treating an empty/missing top-level list as "supports
            // nothing" silently dropped every query to those addons. Only reject when the
            // manifest actually declared a type list and this type isn't in it.
            if (manifest.types.isNotEmpty() && type !in manifest.types) {
                return@runCatching emptyList()
            }

            val encodedId = contentId
                .split(":")
                .joinToString(":") { segment ->
                    URLEncoder.encode(segment, "UTF-8")
                        .replace("+", "%20")
                }

            val url =
                "${manifest.baseUrl}/stream/$type/$encodedId.json"

            val body = getText(url)
                ?: error("Addon stream request failed")

            val json = JSONObject(body)
            val array = json.optJSONArray("streams")
                ?: return@runCatching emptyList()

            var skippedNoUrl = 0
            val result = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue

                    // Temporary stream metadata probe.
                    // Never log URLs, hashes, headers, or other playback secrets.
                    val safeKeys = item.keys().asSequence()
                        .filterNot { key ->
                            key.equals("url", true) ||
                                key.equals("infoHash", true) ||
                                key.equals("headers", true)
                        }
                        .toList()

                    val safeMetadata = safeKeys.joinToString(" | ") { key ->
                        val value = item.opt(key)
                        val rendered = when (value) {
                            is String -> value.take(300)
                            is org.json.JSONArray -> value.toString().take(300)
                            is org.json.JSONObject ->
                                if (key.equals("behaviorHints", true)) {
                                    "keys=" + value.keys().asSequence().toList()
                                } else {
                                    value.toString().take(300)
                                }
                            else -> value?.toString()?.take(300) ?: "null"
                        }
                        "$key=$rendered"
                    }

                    stremioLog(
                        "STREAM_META addon=${manifest.name} index=$i keys=$safeKeys :: $safeMetadata"
                    )

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

                    if (directUrl == null && magnet == null) { skippedNoUrl++; continue }

                    val hints = mutableMapOf<String, String>()
                    val requestHeaders = mutableMapOf<String, String>()

                    val description = item.optString("description")
                        .takeIf(String::isNotBlank)

                    var filename: String? = null

                    item.optJSONObject("behaviorHints")?.let { behavior ->
                        filename = behavior.optString("filename")
                            .takeIf(String::isNotBlank)
                        behavior.keys().forEach { key ->
                            val value = behavior.opt(key)
                            if (value is String && value.isNotBlank()) {
                                hints[key] = value
                            }
                        }

                        behavior
                            .optJSONObject("proxyHeaders")
                            ?.optJSONObject("request")
                            ?.let { headers ->
                                headers.keys().forEach { key ->
                                    headers.optString(key)
                                        .takeIf(String::isNotBlank)
                                        ?.let { value ->
                                            requestHeaders[key] = value
                                        }
                                }
                            }
                    }

                    item.optJSONObject("headers")?.let { headers ->
                        headers.keys().forEach { key ->
                            headers.optString(key)
                                .takeIf(String::isNotBlank)
                                ?.let { value ->
                                    requestHeaders[key] = value
                                }
                        }
                    }

                    val inferredAudioLanguage = inferAudioLanguage(
                        title,
                        description,
                        filename
                    )

                    stremioLog(
                        "LANG_META addon=${manifest.name} index=$i " +
                            "filename=${filename?.take(300) ?: "null"} " +
                            "inferred=${inferredAudioLanguage ?: "null"}"
                    )

                    add(
                        StremioStream(
                            title = title,
                            url = directUrl,
                            magnet = magnet,
                            infoHash = infoHash,
                            fileIdx = fileIdx,
                            source = manifest.name,
                            description = description,
                            filename = filename,
                            audioLanguage = inferredAudioLanguage,
                            behaviorHints = hints,
                            requestHeaders = requestHeaders
                        )
                    )
                }
            }
            stremioLog("PARSE ${manifest.name}: total=${array.length()} kept=${result.size} skippedNoUrl=$skippedNoUrl")
            result
        }
    }

    suspend fun subtitles(
        manifest: StremioAddonManifest,
        type: String,
        contentId: String
    ): Result<List<StremioSubtitle>> = withContext(Dispatchers.IO) {
        runCatching {
            if ("subtitles" !in manifest.resources) {
                return@runCatching emptyList()
            }

            if (manifest.types.isNotEmpty() && type !in manifest.types) {
                return@runCatching emptyList()
            }

            val encodedId = contentId
                .split(":")
                .joinToString(":") { segment ->
                    URLEncoder.encode(segment, "UTF-8")
                        .replace("+", "%20")
                }

            val url =
                "${manifest.baseUrl}/subtitles/$type/$encodedId.json"

            val body = getText(url)
                ?: error("Addon subtitle request failed")

            val json = JSONObject(body)
            val array = json.optJSONArray("subtitles")
                ?: return@runCatching emptyList()

            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue

                    val subtitleUrl = item.optString("url")
                        .takeIf {
                            it.startsWith("http://") ||
                            it.startsWith("https://")
                        } ?: continue

                    val lang = item.optString("lang")
                        .takeIf(String::isNotBlank)

                    val label = item.optString("name")
                        .takeIf(String::isNotBlank)
                        ?: item.optString("title")
                            .takeIf(String::isNotBlank)
                        ?: lang

                    add(
                        StremioSubtitle(
                            url = subtitleUrl,
                            lang = lang,
                            label = label,
                            source = manifest.name
                        )
                    )
                }
            }
        }
    }

    fun toKornDogSources(
        manifest: StremioAddonManifest,
        streams: List<StremioStream>
    ): List<KornDogSource> =
        streams.mapNotNull { stream ->
            val type =
                when {
                    !stream.magnet.isNullOrBlank() ->
                        KornDogSourceType.TORRENT

                    !stream.url.isNullOrBlank() ->
                        KornDogSourceType.DIRECT

                    else ->
                        return@mapNotNull null
                }

            KornDogSource(
                title = stream.filename ?: stream.title,
                type = type,
                infoHash = stream.infoHash,
                magnet = stream.magnet,
                fileIdx = stream.fileIdx,
                directUrl = stream.url,
                headers = stream.requestHeaders,
                provider = manifest.name,
                quality = qualityFrom(stream.title),
                language = stream.audioLanguage
            )
        }
        .distinctBy {
            it.stableIdentity
        }

    fun torrentResults(
        streams: List<StremioStream>
    ): List<TorrentResult> =
        streams.mapNotNull { stream ->
            val token = stream.url ?: stream.magnet
                ?: return@mapNotNull null

            TorrentResult(
                title = stream.filename ?: stream.title,
                token = token,
                seeders = null,
                size = null,
                quality = qualityFrom(stream.title),
                source = stream.source,
                audio = null,
                language = stream.audioLanguage
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

        stremioLog("GET $url")

        return try {
            http.newCall(request).execute().use { response ->
                stremioLog(
                    "HTTP ${response.code} host=${response.request.url.host} url=${response.request.url}"
                )

                if (!response.isSuccessful) {
                    stremioLog(
                        "FAILED HTTP ${response.code} ${response.message} url=${response.request.url}"
                    )
                    null
                } else {
                    val body = response.body?.string()

                    stremioLog(
                        "SUCCESS bytes=${body?.length ?: 0} url=${response.request.url}"
                    )

                    body
                }
            }
        } catch (error: Exception) {
            stremioLog(
                "EXCEPTION ${error.javaClass.simpleName}: ${error.message} url=$url"
            )
            Log.e("StremioDebug", "Request exception", error)
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

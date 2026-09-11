package com.lumora.data.remote.debrid

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * KornDog native direct-stream resolver.
 *
 * Discovery providers are allowed to FIND candidates.
 * KornDog decides whether the candidate is actually a playable HTTP stream.
 *
 * This resolver:
 * - follows redirects
 * - preserves provider-supplied request headers
 * - rejects obvious HTML / JSON wrapper responses
 * - recognizes common HLS, DASH and progressive video responses
 * - returns the final URL after redirects
 */
object KornDogUrlResolver {

    data class Resolved(
        val url: String,
        val headers: Map<String, String>,
        val contentType: String?,
        val contentLength: Long?,
        val redirected: Boolean
    )

    private val http =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()

    suspend fun resolve(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): Resolved? =
        withContext(Dispatchers.IO) {
            if (
                !url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                Log.d(
                    "KornDogUrlResolver",
                    "Rejected non-HTTP candidate"
                )
                return@withContext null
            }

            /*
             * Try HEAD first because it avoids downloading media just to inspect it.
             * Plenty of CDNs reject HEAD, so GET is our fallback.
             */
            val head =
                runCatching {
                    val request =
                        buildRequest(
                            url = url,
                            headers = headers,
                            head = true
                        )

                    http.newCall(request).execute()
                }.getOrNull()

            if (head != null) {
                head.use { response ->
                    val resolved =
                        inspectResponse(
                            originalUrl = url,
                            response = response,
                            headers = headers
                        )

                    if (resolved != null) {
                        return@withContext resolved
                    }

                    /*
                     * A 405/403/odd HEAD response is common even when GET works.
                     * Fall through to the real probe.
                     */
                }
            }

            val get =
                runCatching {
                    val request =
                        buildRequest(
                            url = url,
                            headers = headers,
                            head = false
                        )

                    http.newCall(request).execute()
                }.getOrElse { error ->
                    Log.w(
                        "KornDogUrlResolver",
                        "Direct probe failed for $url",
                        error
                    )
                    return@withContext null
                }

            get.use { response ->
                inspectResponse(
                    originalUrl = url,
                    response = response,
                    headers = headers
                )
            }
        }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        head: Boolean
    ): Request {
        val builder =
            Request.Builder()
                .url(url)

        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                builder.header(name, value)
            }
        }

        if (head) {
            builder.head()
        } else {
            /*
             * Range keeps progressive-file probes tiny.
             * Servers that ignore Range are still protected because we never
             * consume the response body.
             */
            if (
                headers.keys.none {
                    it.equals("Range", ignoreCase = true)
                }
            ) {
                builder.header("Range", "bytes=0-1")
            }

            builder.get()
        }

        return builder.build()
    }

    private fun inspectResponse(
        originalUrl: String,
        response: Response,
        headers: Map<String, String>
    ): Resolved? {
        if (!response.isSuccessful) {
            Log.d(
                "KornDogUrlResolver",
                "Rejected HTTP ${response.code} for $originalUrl"
            )
            return null
        }

        val finalUrl =
            response.request.url.toString()

        val contentType =
            response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()

        val contentLength =
            response.header("Content-Length")
                ?.toLongOrNull()

        if (
            isClearlyWrapper(
                contentType = contentType,
                finalUrl = finalUrl
            )
        ) {
            Log.d(
                "KornDogUrlResolver",
                "Rejected wrapper response type=$contentType url=$finalUrl"
            )
            return null
        }

        val mediaLike =
            isMediaContentType(contentType) ||
                isMediaUrl(finalUrl)

        if (!mediaLike) {
            Log.d(
                "KornDogUrlResolver",
                "Rejected non-media candidate type=$contentType url=$finalUrl"
            )
            return null
        }

        Log.i(
            "KornDogUrlResolver",
            "Verified direct stream type=$contentType final=$finalUrl"
        )

        return Resolved(
            url = finalUrl,
            headers = headers,
            contentType = contentType,
            contentLength = contentLength,
            redirected = !sameUrl(originalUrl, finalUrl)
        )
    }

    private fun isClearlyWrapper(
        contentType: String?,
        finalUrl: String
    ): Boolean {
        if (contentType == null) {
            return false
        }

        if (
            contentType.startsWith("text/html") ||
            contentType.startsWith("application/json")
        ) {
            return true
        }

        /*
         * text/plain occasionally hosts an HLS manifest, so permit it when
         * the final URL clearly looks like a manifest.
         */
        if (
            contentType.startsWith("text/plain") &&
            !finalUrl.substringBefore('?')
                .lowercase()
                .endsWith(".m3u8")
        ) {
            return true
        }

        return false
    }

    private fun isMediaContentType(
        contentType: String?
    ): Boolean {
        if (contentType == null) {
            return false
        }

        return when {
            contentType.startsWith("video/") -> true

            contentType == "application/vnd.apple.mpegurl" -> true
            contentType == "application/x-mpegurl" -> true
            contentType == "application/mpegurl" -> true

            contentType == "application/dash+xml" -> true

            contentType == "application/octet-stream" -> true

            contentType == "binary/octet-stream" -> true

            else -> false
        }
    }

    private fun isMediaUrl(
        url: String
    ): Boolean {
        val path =
            url.substringBefore('?')
                .substringBefore('#')
                .lowercase()

        return listOf(
            ".m3u8",
            ".mpd",
            ".mp4",
            ".m4v",
            ".mkv",
            ".webm",
            ".ts",
            ".m2ts"
        ).any(path::endsWith)
    }

    private fun sameUrl(
        first: String,
        second: String
    ): Boolean =
        first.trimEnd('/') ==
            second.trimEnd('/')
}

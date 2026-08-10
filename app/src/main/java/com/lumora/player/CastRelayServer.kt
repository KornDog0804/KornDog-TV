package com.lumora.player

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Receiver-accessible HTTP relay for Cast VOD.
 *
 * The Chromecast talks only to this phone. The phone performs every upstream
 * request, preserving the network origin that obtained the signed stream and
 * attaching Referer/User-Agent/etc. that the stock Cast receiver cannot.
 *
 * HLS playlists are rewritten so child playlists, media segments and key URIs
 * also come back through this relay rather than escaping directly to the CDN.
 */
internal class CastRelayServer(
    private val client: OkHttpClient,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    private data class RelayContext(
        val headers: Map<String, String>,
        val userAgent: String?
    )

    private val contexts = ConcurrentHashMap<String, RelayContext>()
    private val localFiles = ConcurrentHashMap<String, File>()

    private val debugLog = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun d(msg: String) {
        Log.d(TAG, msg)
        try {
            val f = java.io.File("/sdcard/Download/castrelay.log")
            f.appendText("${System.currentTimeMillis() % 100000}: $msg\n")
        } catch (_: Exception) {}
    }

    @Volatile
    private var startedPort: Int = -1

    @Synchronized
    fun ensureStarted(): Int {
        if (isAlive && startedPort > 0) {
            return startedPort
        }

        try {
            start(SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not start Cast relay on port $listeningPort",
                e
            )
        }

        val actualPort = listeningPort
        if (actualPort <= 0) {
            stop()
            throw IllegalStateException("Cast relay started without a usable port")
        }

        startedPort = actualPort

        Log.d(
            TAG,
            "Cast relay listening on port $actualPort"
        )

        return actualPort
    }

    data class RelayMedia(
        val url: String,
        val contentType: String
    )

    fun register(
        upstreamUrl: String,
        headers: Map<String, String>?,
        userAgent: String?
    ): RelayMedia {
        val port = ensureStarted()
        val relayHeaders = headers.orEmpty()

        d(
            "register() host=${upstreamUrl.toHttpUrlOrNull()?.host} " +
                "port=$port localIp=${localIpv4Address()}"
        )

        // Probe only enough to follow the provider/CDN redirect and discover
        // the real container. This is VOD-only; Live never enters this relay.
        val contentType = runCatching {
            val request = Request.Builder()
                .url(upstreamUrl)
                .header("Range", "bytes=0-0")
                .apply {
                    relayHeaders.forEach { (name, value) ->
                        header(name, value)
                    }
                    if (!userAgent.isNullOrBlank()) {
                        header("User-Agent", userAgent)
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                val ext = response.request.url.encodedPath
                    .substringAfterLast('.', "")
                    .lowercase()

                val headerType = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.ifBlank { null }

                val detected = when (ext) {
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    "avi" -> "video/x-msvideo"
                    "mp4", "m4v" -> "video/mp4"
                    "m3u8", "m3u" -> "application/x-mpegURL"
                    "mpd" -> "application/dash+xml"
                    else -> headerType
                        ?.takeUnless {
                            it.equals("application/octet-stream", true)
                        }
                        ?: "video/mp4"
                }

                d(
                    "register() castMime=$detected " +
                        "finalHost=${response.request.url.host}"
                )

                detected
            }
        }.getOrElse { error ->
            d(
                "register() MIME probe failed " +
                    "${error.javaClass.simpleName}: ${error.message}"
            )
            "video/mp4"
        }

        val token = UUID.randomUUID().toString()
        contexts[token] = RelayContext(
            headers = relayHeaders,
            userAgent = userAgent
        )

        return RelayMedia(
            url = relayUrl(
                host = localIpv4Address()
                    ?: throw IllegalStateException("Phone has no LAN IPv4 address"),
                port = port,
                token = token,
                upstream = upstreamUrl
            ),
            contentType = contentType
        )
    }

    fun registerLocalFile(file: File): RelayMedia {
        require(file.isFile && file.length() > 0L) {
            "Local Cast file does not exist or is empty: ${file.absolutePath}"
        }

        val port = ensureStarted()
        val host = localIpv4Address()
            ?: throw IllegalStateException("Phone has no LAN IPv4 address")
        val token = UUID.randomUUID().toString()

        localFiles[token] = file

        val url = "http://$host:$port/local/$token"

        d(
            "registerLocalFile() path=${file.name} bytes=${file.length()} " +
                "url=$url"
        )

        return RelayMedia(
            url = url,
            contentType = "video/mp4"
        )
    }

    override fun serve(session: IHTTPSession): Response {
        d(
            "Receiver request method=${session.method} " +
                "uri=${session.uri.substringBefore('?')} " +
                "range=${session.headers["range"]} " +
                "accept=${session.headers["accept"]} " +
                "ua=${session.headers["user-agent"]} " +
                "connection=${session.headers["connection"]}"
        )

        d("serve() method=${session.method} uri=${session.uri} range=${session.headers["range"]}")
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "GET/HEAD only"
            )
        }

        val localPieces = session.uri.trim('/').split('/')
        if (localPieces.size == 2 && localPieces[0] == "local") {
            val file = localFiles[localPieces[1]]
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Local Cast file expired"
                )

            return serveLocalFile(session, file)
        }

        if (session.uri.trim('/') == "relay/debug-log") {
            return newFixedLengthResponse(
                Response.Status.OK,
                MIME_PLAINTEXT,
                debugLog.joinToString("\n")
            )
        }
        val pieces = session.uri.trim('/').split('/')
        if (pieces.size != 2 || pieces[0] != "relay") {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not found"
            )
        }

        val token = pieces[1]
        val relayContext = contexts[token]
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Relay expired"
            )

        val encoded = session.parameters["u"]?.firstOrNull()
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Missing upstream URL"
            )

        val upstreamUrl = try {
            String(
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP),
                StandardCharsets.UTF_8
            )
        } catch (_: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Bad upstream URL"
            )
        }

        return proxy(
            session = session,
            token = token,
            upstreamUrl = upstreamUrl,
            relayContext = relayContext
        )
    }

    private fun serveLocalFile(
        session: IHTTPSession,
        file: File
    ): Response {
        val totalLength = file.length()
        val rangeHeader = session.headers["range"]

        var start = 0L
        var end = totalLength - 1L
        var partial = false

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.removePrefix("bytes=").substringBefore(',')
            val parts = range.split('-', limit = 2)

            val requestedStart = parts.getOrNull(0)?.trim()?.toLongOrNull()
            val requestedEnd = parts.getOrNull(1)?.trim()?.toLongOrNull()

            when {
                requestedStart != null -> {
                    start = requestedStart
                    end = requestedEnd ?: end
                }

                requestedEnd != null -> {
                    val suffixLength = requestedEnd.coerceAtMost(totalLength)
                    start = totalLength - suffixLength
                    end = totalLength - 1L
                }
            }

            if (
                start < 0L ||
                start >= totalLength ||
                end < start
            ) {
                return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    MIME_PLAINTEXT,
                    ""
                ).apply {
                    addHeader("Content-Range", "bytes */$totalLength")
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            }

            end = end.coerceAtMost(totalLength - 1L)
            partial = true
        }

        val contentLength = end - start + 1L
        val status =
            if (partial) Response.Status.PARTIAL_CONTENT
            else Response.Status.OK

        d(
            "Local file request method=${session.method} " +
                "file=${file.name} range=$rangeHeader " +
                "serving=$start-$end/$totalLength"
        )

        if (session.method == Method.HEAD) {
            return newFixedLengthResponse(
                status,
                "video/mp4",
                ""
            ).apply {
                addHeader("Content-Length", contentLength.toString())
                addHeader("Accept-Ranges", "bytes")
                if (partial) {
                    addHeader(
                        "Content-Range",
                        "bytes $start-$end/$totalLength"
                    )
                }
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Cache-Control", "no-store")
            }
        }

        val input = FileInputStream(file)

        var remainingToSkip = start
        while (remainingToSkip > 0L) {
            val skipped = input.skip(remainingToSkip)
            if (skipped <= 0L) {
                input.close()
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Could not seek local Cast file"
                )
            }
            remainingToSkip -= skipped
        }

        val limited = object : FilterInputStream(input) {
            private var remaining = contentLength

            override fun read(): Int {
                if (remaining <= 0L) return -1
                val value = super.read()
                if (value >= 0) remaining--
                return value
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int
            ): Int {
                if (remaining <= 0L) return -1

                val allowed =
                    minOf(length.toLong(), remaining).toInt()

                val count = super.read(buffer, offset, allowed)
                if (count > 0) remaining -= count
                return count
            }
        }

        return newFixedLengthResponse(
            status,
            "video/mp4",
            limited,
            contentLength
        ).apply {
            addHeader("Accept-Ranges", "bytes")

            if (partial) {
                addHeader(
                    "Content-Range",
                    "bytes $start-$end/$totalLength"
                )
            }

            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun proxy(
        session: IHTTPSession,
        token: String,
        upstreamUrl: String,
        relayContext: RelayContext
    ): Response {
        val requestBuilder = Request.Builder().url(upstreamUrl)

        relayContext.headers.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }

        if (!relayContext.userAgent.isNullOrBlank()) {
            requestBuilder.header("User-Agent", relayContext.userAgent)
        }

        session.headers["range"]?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("Range", it)
        }

        if (session.method == Method.HEAD) {
            requestBuilder.head()
        }

        d(
            "Fetching upstream host=${upstreamUrl.toHttpUrlOrNull()?.host} " +
                "headerCount=${relayContext.headers.size} " +
                "hasUserAgent=${!relayContext.userAgent.isNullOrBlank()}"
        )
        val upstream = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Upstream relay failed: $upstreamUrl", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message ?: "Upstream request failed"
            )
        }

        // Rich upstream metadata is logged below without exposing signed URLs.
        val body = upstream.body
        val upstreamType = upstream.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.ifBlank { null }
            .let { headerType ->
                // The upstream/CDN Content-Type header is unreliable - a debrid CDN can
                // claim video/mp4 for a file whose extension is plainly .mkv. The file
                // extension in the resolved URL is the more trustworthy signal, so it
                // wins whenever it maps to a known container; the header is only used
                // as a fallback when the extension is missing/unrecognized.
                val extType = when (upstream.request.url.encodedPath.substringAfterLast('.', "").lowercase()) {
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    "avi" -> "video/x-msvideo"
                    "mp4", "m4v" -> "video/mp4"
                    else -> null
                }
                extType
                    ?: headerType?.takeUnless { it.equals("application/octet-stream", true) }
                    ?: "video/mp4"
            }

        d(
            "Upstream response " +
                "code=${upstream.code} " +
                "type=$upstreamType " +
                "length=${upstream.header("Content-Length")} " +
                "contentRange=${upstream.header("Content-Range")} " +
                "acceptRanges=${upstream.header("Accept-Ranges")} " +
                "finalHost=${upstream.request.url.host} " +
                "finalPath=${upstream.request.url.encodedPath}"
        )

        val looksLikeHls =
            upstreamType.equals("application/vnd.apple.mpegurl", true) ||
                upstreamType.equals("application/x-mpegurl", true) ||
                upstreamUrl.substringBefore('?').endsWith(".m3u8", true)

        if (looksLikeHls && body != null && session.method != Method.HEAD) {
            val text = try {
                body.string()
            } finally {
                upstream.close()
            }

            val rewritten = rewritePlaylist(
                playlist = text,
                playlistUrl = upstreamUrl,
                token = token
            )

        d("HLS rewritten originalLen=${text.length} rewrittenLen=${rewritten.length}")
            val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)

            return newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.apple.mpegurl",
                bytes.inputStream(),
                bytes.size.toLong()
            ).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Cache-Control", "no-store")
            }
        }

        val status = when (upstream.code) {
            200 -> Response.Status.OK
            206 -> Response.Status.PARTIAL_CONTENT
            404 -> Response.Status.NOT_FOUND
            416 -> Response.Status.RANGE_NOT_SATISFIABLE
            else -> if (upstream.isSuccessful) {
                Response.Status.OK
            } else {
                upstream.close()
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Upstream HTTP ${upstream.code}"
                )
            }
        }

        if (session.method == Method.HEAD) {
            val response = newFixedLengthResponse(
                status,
                upstreamType ?: "application/octet-stream",
                ""
            )

            upstream.header("Content-Length")?.let {
                response.addHeader("Content-Length", it)
            }

            upstream.header("Content-Range")?.let {
                response.addHeader("Content-Range", it)
            }

            upstream.header("Accept-Ranges")?.let {
                response.addHeader("Accept-Ranges", it)
            }

            upstream.header("ETag")?.let {
                response.addHeader("ETag", it)
            }

            upstream.header("Last-Modified")?.let {
                response.addHeader("Last-Modified", it)
            }

            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Cache-Control", "no-store")

            upstream.close()
            return response
        }

        if (body == null) {
            upstream.close()
            return newFixedLengthResponse(
                status,
                upstreamType ?: "application/octet-stream",
                ""
            )
        }

        val deliveryStartedAt = System.currentTimeMillis()
        var deliveredBytes = 0L
        var firstByteLogged = false
        var firstMegabyteLogged = false
        var sawEof = false

        val stream = object : FilterInputStream(body.byteStream()) {

            private fun recordDelivery(count: Int) {
                if (count <= 0) return

                deliveredBytes += count

                if (!firstByteLogged) {
                    firstByteLogged = true
                    d(
                        "Delivery first-byte after " +
                            "${System.currentTimeMillis() - deliveryStartedAt}ms"
                    )
                }

                if (
                    !firstMegabyteLogged &&
                    deliveredBytes >= 1024L * 1024L
                ) {
                    firstMegabyteLogged = true
                    d(
                        "Delivery first-1MB after " +
                            "${System.currentTimeMillis() - deliveryStartedAt}ms"
                    )
                }
            }

            override fun read(): Int {
                return try {
                    val value = super.read()

                    if (value < 0) {
                        if (!sawEof) {
                            sawEof = true
                            d(
                                "Upstream EOF bytes=$deliveredBytes " +
                                    "elapsed=${System.currentTimeMillis() - deliveryStartedAt}ms"
                            )
                        }
                    } else {
                        recordDelivery(1)
                    }

                    value
                } catch (e: Exception) {
                    d(
                        "Upstream read failure bytes=$deliveredBytes " +
                            "elapsed=${System.currentTimeMillis() - deliveryStartedAt}ms " +
                            "error=${e.javaClass.simpleName}: ${e.message}"
                    )
                    Log.e(
                        TAG,
                        "Upstream read failure bytes=$deliveredBytes " +
                            "elapsed=${System.currentTimeMillis() - deliveryStartedAt}ms " +
                            "error=${e.javaClass.simpleName}: ${e.message}"
                    )
                    throw e
                }
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int
            ): Int {
                return try {
                    val count = super.read(buffer, offset, length)

                    if (count < 0) {
                        if (!sawEof) {
                            sawEof = true
                            d(
                                "Upstream EOF bytes=$deliveredBytes " +
                                    "elapsed=${System.currentTimeMillis() - deliveryStartedAt}ms"
                            )
                        }
                    } else {
                        recordDelivery(count)
                    }

                    count
                } catch (e: Exception) {
                    d(
                        "Upstream read failure bytes=$deliveredBytes " +
                            "elapsed=${System.currentTimeMillis() - deliveryStartedAt}ms " +
                            "error=${e.javaClass.simpleName}: ${e.message}"
                    )
                    Log.e(
                        TAG,
                        "Upstream read failure bytes=$deliveredBytes " +
                            "elapsed=${System.currentTimeMillis() - deliveryStartedAt}ms " +
                            "error=${e.javaClass.simpleName}: ${e.message}"
                    )
                    throw e
                }
            }

            override fun close() {
                try {
                    d(
                        "Delivery stream closed bytes=$deliveredBytes " +
                            "eof=$sawEof " +
                            "elapsed=${System.currentTimeMillis() - deliveryStartedAt}ms"
                    )

                    super.close()
                } finally {
                    upstream.close()
                }
            }
        }

        val length = body.contentLength()

        val response =
            if (length >= 0) {
                newFixedLengthResponse(
                    status,
                    upstreamType ?: "application/octet-stream",
                    stream,
                    length
                )
            } else {
                newChunkedResponse(
                    status,
                    upstreamType ?: "application/octet-stream",
                    stream
                )
            }

        upstream.header("Content-Range")?.let {
            response.addHeader("Content-Range", it)
        }

        upstream.header("Accept-Ranges")?.let {
            response.addHeader("Accept-Ranges", it)
        }

        response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-store")

        return response
    }

    private fun rewritePlaylist(
        playlist: String,
        playlistUrl: String,
        token: String
    ): String {
        val base = playlistUrl.toHttpUrlOrNull() ?: return playlist
        val host = localIpv4Address() ?: return playlist
        val port = listeningPort

        fun wrap(value: String): String {
            val absolute = base.resolve(value)?.toString() ?: value
            return relayUrl(host, port, token, absolute)
        }

        return playlist.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line

                !line.startsWith("#") ->
                    wrap(line.trim())

                line.contains("URI=\"") ->
                    URI_ATTRIBUTE.replace(line) { match ->
                        val original = match.groupValues[1]
                        "URI=\"${wrap(original)}\""
                    }

                else -> line
            }
        }
    }

    companion object {
        private const val TAG = "CastRelay"
        private const val DEFAULT_PORT = 38200
        private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")

        private fun relayUrl(
            host: String,
            port: Int,
            token: String,
            upstream: String
        ): String {
            val encoded = Base64.encodeToString(
                upstream.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP
            )

            return "http://$host:$port/relay/$token?u=$encoded"
        }

        private fun localIpv4Address(): String? {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()

            // wlan* first on Android, then any ordinary site-local IPv4.
            val ordered = interfaces.sortedBy {
                if (it.name.startsWith("wlan", true)) 0 else 1
            }

            for (iface in ordered) {
                if (!iface.isUp || iface.isLoopback) continue

                for (address in iface.inetAddresses.toList()) {
                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        address.isSiteLocalAddress
                    ) {
                        return address.hostAddress
                    }
                }
            }

            return null
        }
    }
}

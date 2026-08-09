package com.lumora.player

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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

    fun register(
        upstreamUrl: String,
        headers: Map<String, String>?,
        userAgent: String?
    ): String {
        val port = ensureStarted()
        Log.d(TAG, "register() upstream=$upstreamUrl port=$port localIp=${localIpv4Address()}")

        val token = UUID.randomUUID().toString()
        contexts[token] = RelayContext(
            headers = headers.orEmpty(),
            userAgent = userAgent
        )

        return relayUrl(
            host = localIpv4Address()
                ?: throw IllegalStateException("Phone has no LAN IPv4 address"),
            port = port,
            token = token,
            upstream = upstreamUrl
        )
    }

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "serve() method=${session.method} uri=${session.uri} range=${session.headers["range"]}")
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "GET/HEAD only"
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

        Log.d(TAG, "Fetching upstream url=$upstreamUrl headers=${relayContext.headers} ua=${relayContext.userAgent}")
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

        Log.d(TAG, "Upstream response code=${upstream.code} contentType=${upstream.header("Content-Type")} contentLength=${upstream.header("Content-Length")} contentRange=${upstream.header("Content-Range")}")
        val body = upstream.body
        val upstreamType = upstream.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.ifBlank { null }

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

        Log.d(TAG, "HLS rewritten originalLen=${text.length} rewrittenLen=${rewritten.length}")
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

        val stream = object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try {
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

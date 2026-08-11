package com.lumora.player

import android.content.Context
import android.widget.Toast
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import android.os.Handler
import android.os.Looper
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import android.net.Uri
import okhttp3.OkHttpClient

/**
 * Manages Google Cast (Chromecast) playback.
 * Handles session lifecycle and media loading to cast devices.
 */
class CastManager(private val context: Context) {

    private fun castLog(message: String) {
        runCatching {
            val file = java.io.File("/sdcard/Download/castmanager.log")
            file.appendText(
                "${System.currentTimeMillis()}: $message\n"
            )
        }
        android.util.Log.d("CastManager", message)
    }


    private val relayClient = OkHttpClient.Builder()
        // Cast VOD is a long-lived streaming request. A normal OkHttp read
        // timeout can kill an otherwise healthy movie during a CDN pause.
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val vodRelay by lazy {
        CastRelayServer(relayClient)
    }

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null

    var onCastSessionConnected: ((CastSession) -> Unit)? = null
    var onCastSessionDisconnected: (() -> Unit)? = null

    /**
     * Initialize the Cast framework.
     */
    fun init() {
        try {
            castContext = CastContext.getSharedInstance(context)
            val sessionManager = castContext?.sessionManager
            sessionListener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) {
                    castLog("SESSION_STARTED id=$sessionId")
                    castSession = session
                    onCastSessionConnected?.invoke(session)
                }
                override fun onSessionEnded(session: CastSession, error: Int) {
                    castLog("SESSION_ENDED error=$error")
                    onCastSessionDisconnected?.invoke()
                    castSession = null
                }
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    castLog("SESSION_RESUMED wasSuspended=$wasSuspended")
                    castSession = session
                    onCastSessionConnected?.invoke(session)
                }
                override fun onSessionSuspended(session: CastSession, reason: Int) {
                    castLog("SESSION_SUSPENDED reason=$reason")
                }
                override fun onSessionStarting(session: CastSession) {}
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    castLog("SESSION_START_FAILED error=$error")
                    castSession = null
                }
                override fun onSessionEnding(session: CastSession) {}
                override fun onSessionResuming(session: CastSession, sessionId: String) {}
                override fun onSessionResumeFailed(session: CastSession, error: Int) {
                    castLog("SESSION_RESUME_FAILED error=$error")
                }
            }
            sessionManager?.addSessionManagerListener(sessionListener!!, CastSession::class.java)
        } catch (e: Exception) {
            // Google Play Services may not be available
        }
    }

    fun isConnected(): Boolean = castSession?.isConnected == true

    internal fun registerGrowingCastFile(
        file: java.io.File
    ): CastRelayServer.GrowingLocalFile {
        vodRelay.ensureStarted()
        return vodRelay.registerGrowingLocalFile(file)
    }

    /**
     * Determine MIME type from a stream URL extension.
     */
    private fun guessContentType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") || lower.contains(".m3u") -> "application/x-mpegURL"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".ts") -> "video/mp2t"
            lower.contains(".mp3") -> "audio/mpeg"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".mkv") -> "video/x-matroska"
            // HLS-style query params or dash
            lower.contains("dash") || lower.contains(".mpd") -> "application/dash+xml"
            else -> "application/x-mpegURL" // best guess for IPTV
        }
    }

    /**
     * Cast a channel to the connected device.
     */
    internal fun castChannel(
        channel: Channel,
        title: String? = null,
        playbackUrl: String? = null,
        requestHeaders: Map<String, String>? = null,
        userAgent: String? = null,
        localFile: java.io.File? = null,
        growingLocalFile: CastRelayServer.GrowingLocalFile? = null,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        val session = castSession
        if (session == null || !session.isConnected) {
            onResult(false, "Cast session is not connected")
            return
        }

        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            onResult(false, "Cast receiver is not ready")
            return
        }

        val url = playbackUrl?.takeIf { it.isNotBlank() } ?: channel.url
        if (url.isBlank()) {
            onResult(false, "This item has no direct stream URL")
            return
        }

        val lowerUrl = url.lowercase()
        if (
            lowerUrl.startsWith("file:") ||
            lowerUrl.contains("127.0.0.1") ||
            lowerUrl.contains("localhost")
        ) {
            onResult(false, "This stream only exists on this phone and cannot be fetched by Cast")
            return
        }

        // Live TV already casts correctly and must stay on the existing direct path.
        //
        // VOD goes through the phone relay. This keeps the upstream request on the
        // same network origin as phone playback and lets us preserve Referer/UA/etc.
        val relayMedia =
            if (channel.mediaType == MediaType.LIVE) {
                null
            } else {
                try {
                    vodRelay.ensureStarted()

                    castLog("CAST_SOURCE relay upstream")
                    vodRelay.register(
                        upstreamUrl = url,
                        headers = requestHeaders ?: channel.streamHeaders,
                        userAgent = userAgent ?: channel.streamUserAgent
                    )
                } catch (e: Exception) {
                    android.util.Log.e("CastManager", "Couldn't start VOD relay", e)
                    onResult(false, e.message ?: "Couldn't start Cast relay")
                    return
                }
            }

        val castUrl =
            if (channel.mediaType == MediaType.LIVE) {
                url
            } else {
                relayMedia!!.url
            }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title ?: channel.name)
            channel.logoUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }

        val streamType = if (channel.mediaType == MediaType.LIVE)
            MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED

        val contentType =
            if (channel.mediaType == MediaType.LIVE) {
                // Known-good Live TV behavior remains exactly unchanged.
                guessContentType(url)
            } else {
                relayMedia!!.contentType
            }

        val mediaInfo = MediaInfo.Builder(castUrl)
            .setStreamType(streamType)
            .setContentType(contentType)
            .setMetadata(metadata)
            .build()

        val loadOptions = MediaLoadOptions.Builder()
            .setAutoplay(true)
            .build()

        try {
            android.util.Log.d(
                "CastManager",
                "Submitting Cast load: $castUrl upstream=$url (type=$contentType, stream=$streamType)"
            )

            var finished = false

            // True only after THIS newly submitted media has actually entered
            // LOADING or BUFFERING on the receiver.
            //
            // A successful remoteMediaClient.load() result only means Google
            // accepted the command. When switching away from live TV, the
            // receiver can briefly report IDLE while tearing the live stream
            // down before the new VOD begins loading. That transitional IDLE
            // must not be treated as a movie failure.
            var receiverSawNewLoadActivity = false

            val handler = Handler(Looper.getMainLooper())

            lateinit var callback: RemoteMediaClient.Callback

            fun finish(success: Boolean, message: String?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                remoteMediaClient.unregisterCallback(callback)
                onResult(success, message)
            }

            callback = object : RemoteMediaClient.Callback() {
                override fun onStatusUpdated() {
                    val state = remoteMediaClient.playerState

                    android.util.Log.d(
                        "CastManager",
                        "Receiver state=$state idleReason=${remoteMediaClient.idleReason}"
                    )

                    when (state) {
                        MediaStatus.PLAYER_STATE_PLAYING -> {
                            android.util.Log.d(
                                "CastManager",
                                "Cast receiver is PLAYING: $url"
                            )
                            finish(true, null)
                        }

                        MediaStatus.PLAYER_STATE_LOADING -> {
                            receiverSawNewLoadActivity = true

                            castLog(
                                "NEW_MEDIA_LOADING url=$url"
                            )

                            android.util.Log.d(
                                "CastManager",
                                "Cast receiver is LOADING: $url"
                            )
                        }

                        MediaStatus.PLAYER_STATE_BUFFERING -> {
                            receiverSawNewLoadActivity = true

                            castLog(
                                "NEW_MEDIA_BUFFERING url=$url"
                            )

                            android.util.Log.d(
                                "CastManager",
                                "Cast receiver is BUFFERING: $url"
                            )
                        }

                        MediaStatus.PLAYER_STATE_IDLE -> {
                            val idleReason = remoteMediaClient.idleReason

                            if (receiverSawNewLoadActivity) {
                                castLog(
                                    "NEW_MEDIA_IDLE_AFTER_ACTIVITY reason=$idleReason url=$url"
                                )

                                finish(
                                    false,
                                    "Receiver went idle ($idleReason)"
                                )
                            } else {
                                // Expected when replacing an existing Cast item,
                                // especially a never-ending live TV stream.
                                //
                                // The old item can become IDLE after our new LOAD
                                // command has already been accepted but before the
                                // new movie reports LOADING/BUFFERING.
                                castLog(
                                    "TRANSITIONAL_IDLE_IGNORED reason=$idleReason url=$url"
                                )
                            }
                        }
                    }
                }
            }

            remoteMediaClient.registerCallback(callback)

            handler.postDelayed({
                val state = remoteMediaClient.playerState

                // LOADING (5) and BUFFERING (4) both mean the receiver is actively
                // working on this load. Neither is grounds for declaring Cast dead.
                if (
                    state == MediaStatus.PLAYER_STATE_LOADING ||
                    state == MediaStatus.PLAYER_STATE_BUFFERING
                ) {
                    android.util.Log.d(
                        "CastManager",
                        "Cast still starting after startup window: state=$state url=$url"
                    )
                    return@postDelayed
                }

                finish(
                    false,
                    "Receiver never started playback (state=$state)"
                )
            }, 45_000L)

            remoteMediaClient.load(mediaInfo, loadOptions)
                .setResultCallback { result ->
                    val status = result.status

                    if (!status.isSuccess) {
                        val message = status.statusMessage
                            ?: "Cast load failed (${status.statusCode})"

                        android.util.Log.e(
                            "CastManager",
                            "Cast receiver rejected load: code=${status.statusCode}, message=$message, url=$url"
                        )

                        finish(false, message)
                    } else {
                        castLog(
                            "LOAD_COMMAND_ACCEPTED waitingForNewMediaActivity url=$url"
                        )

                        android.util.Log.d(
                            "CastManager",
                            "Cast LOAD accepted; waiting for receiver activity: $url"
                        )

                        // PLAYING can arrive between LOAD completing and this callback.
                        if (remoteMediaClient.playerState == MediaStatus.PLAYER_STATE_PLAYING) {
                            finish(true, null)
                        }
                    }
                }
        } catch (e: Exception) {
            android.util.Log.e("CastManager", "Failed to submit Cast load", e)
            onResult(false, e.message ?: "Couldn't send media to Cast receiver")
        }
    }

    fun stopCasting() {
        val session = castSession ?: return
        session.remoteMediaClient?.stop()
    }

    fun release() {
        try {
            castContext?.sessionManager?.removeSessionManagerListener(
                sessionListener!!, CastSession::class.java
            )
        } catch (_: Exception) {}
        castSession = null
        castContext = null
        if (vodRelay.isAlive) {
            vodRelay.stop()
        }
    }
}

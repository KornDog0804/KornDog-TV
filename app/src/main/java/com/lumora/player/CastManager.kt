package com.lumora.player

import android.content.Context
import android.widget.Toast
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import android.net.Uri

/**
 * Manages Google Cast (Chromecast) playback.
 * Handles session lifecycle and media loading to cast devices.
 */
class CastManager(private val context: Context) {

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
                    castSession = session
                    onCastSessionConnected?.invoke(session)
                }
                override fun onSessionEnded(session: CastSession, error: Int) {
                    onCastSessionDisconnected?.invoke()
                    castSession = null
                }
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                    castSession = session
                    onCastSessionConnected?.invoke(session)
                }
                override fun onSessionSuspended(session: CastSession, reason: Int) {}
                override fun onSessionStarting(session: CastSession) {}
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    castSession = null
                }
                override fun onSessionEnding(session: CastSession) {}
                override fun onSessionResuming(session: CastSession, sessionId: String) {}
                override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            }
            sessionManager?.addSessionManagerListener(sessionListener!!, CastSession::class.java)
        } catch (e: Exception) {
            // Google Play Services may not be available
        }
    }

    fun isConnected(): Boolean = castSession?.isConnected == true

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
    fun castChannel(
        channel: Channel,
        title: String? = null,
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

        val url = channel.url
        if (url.isBlank()) {
            onResult(false, "This item has no direct stream URL")
            return
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title ?: channel.name)
            channel.logoUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }

        val streamType = if (channel.mediaType == MediaType.LIVE)
            MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED

        val contentType = guessContentType(url)

        val mediaInfo = MediaInfo.Builder(url)
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
                "Submitting Cast load: $url (type=$contentType, stream=$streamType)"
            )

            remoteMediaClient.load(mediaInfo, loadOptions)
                .setResultCallback { result ->
                    val status = result.status

                    if (status.isSuccess) {
                        android.util.Log.d(
                            "CastManager",
                            "Cast receiver accepted load: $url"
                        )
                        onResult(true, null)
                    } else {
                        val message = status.statusMessage
                            ?: "Cast load failed (${status.statusCode})"

                        android.util.Log.e(
                            "CastManager",
                            "Cast receiver rejected load: code=${status.statusCode}, message=$message, url=$url"
                        )

                        onResult(false, message)
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
    }
}

import re, shutil, datetime, sys

path = "app/src/main/java/com/lumora/player/CastManager.kt"
ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
backup = f"{path}.before-castprepare-{ts}"
shutil.copy(path, backup)
print(f"Backed up to {backup}")

with open(path, "r", encoding="utf-8") as f:
    src = f.read()

start_m = re.search(r"internal fun castChannel\(", src)
end_m = re.search(r"fun stopCasting\(\)\s*\{", src)

if not start_m or not end_m or end_m.start() < start_m.start():
    print("FAILED: could not locate castChannel function boundaries. Aborting, no changes written.")
    sys.exit(1)

new_function = r'''internal fun castChannel(
        channel: Channel,
        title: String? = null,
        playbackUrl: String? = null,
        requestHeaders: Map<String, String>? = null,
        userAgent: String? = null,
        localFileProvider: (() -> java.io.File?)? = null,
        onPreparing: (() -> Unit)? = null,
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

        val pollHandler = Handler(Looper.getMainLooper())

        // Everything below here is the original load path, unchanged. It is now
        // wrapped in a local function so the VOD-readiness gate further down can
        // poll and call into it once (and only once) a completed transcode file
        // is actually available - Live TV always calls this immediately with a
        // null local file, exactly as it always has.
        fun proceedWithLocalFile(localFile: java.io.File?) {
            // Live TV already casts correctly and must stay on the existing direct path.
            //
            // VOD goes through the phone relay. This keeps the upstream request on the
            // same network origin as phone playback and lets us preserve Referer/UA/etc.
            val localMedia =
                if (
                    channel.mediaType != MediaType.LIVE &&
                    localFile != null &&
                    localFile.isFile &&
                    localFile.length() > 0L
                ) {
                    try {
                        vodRelay.ensureStarted()
                        castLog(
                            "CAST_SOURCE completed local mp4 bytes=${localFile.length()}"
                        )
                        vodRelay.registerLocalFile(localFile)
                    } catch (e: Exception) {
                        android.util.Log.e(
                            "CastManager",
                            "Couldn't register completed local Cast file",
                            e
                        )
                        null
                    }
                } else {
                    null
                }

            val relayMedia =
                if (
                    channel.mediaType == MediaType.LIVE ||
                    localMedia != null
                ) {
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
                        android.util.Log.e(
                            "CastManager",
                            "Couldn't start VOD relay",
                            e
                        )
                        onResult(
                            false,
                            e.message ?: "Couldn't start Cast relay"
                        )
                        return
                    }
                }

            val castUrl =
                when {
                    channel.mediaType == MediaType.LIVE -> {
                        url
                    }

                    localMedia != null -> {
                        localMedia.url
                    }

                    else -> {
                        relayMedia!!.url
                    }
                }

            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, title ?: channel.name)
                channel.logoUrl?.let { addImage(WebImage(Uri.parse(it))) }
            }

            val streamType = if (channel.mediaType == MediaType.LIVE)
                MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED

            val contentType =
                when {
                    channel.mediaType == MediaType.LIVE -> {
                        // Known-good Live TV behavior remains exactly unchanged.
                        guessContentType(url)
                    }

                    localMedia != null -> {
                        localMedia.contentType
                    }

                    else -> {
                        relayMedia!!.contentType
                    }
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

                var receiverSawNewLoadActivity = false

                val handler = Handler(Looper.getMainLooper())

                lateinit var callback: RemoteMediaClient.Callback
                lateinit var thisCastCancel: () -> Unit

                fun finish(success: Boolean, message: String?) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    remoteMediaClient.unregisterCallback(callback)
                    if (activeCastCancel === thisCastCancel) activeCastCancel = null
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
                                    castLog(
                                        "TRANSITIONAL_IDLE_IGNORED reason=$idleReason url=$url"
                                    )
                                }
                            }
                        }
                    }
                }

                thisCastCancel = { finish(false, "Cast stopped") }
                activeCastCancel = thisCastCancel

                remoteMediaClient.registerCallback(callback)

                handler.postDelayed({
                    val state = remoteMediaClient.playerState

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

        // Smart-display class receivers (Nest Hub, Home Hub) commonly lack HEVC 10-bit
        // decode support, and raw-relaying an unsupported audio track (e.g. EAC3/AC3) is
        // silently accepted by standard Chromecast/Google TV receivers too - video plays,
        // audio does not. The background transcode (castTranscodeFile) already re-encodes
        // to Cast-safe AAC/H264 for every VOD title, so ALL non-live Cast attempts wait for
        // it rather than falling through to the raw relay's original audio track.
        //
        // The transcode runs in the background and is frequently still in progress the
        // instant Cast is pressed. Instead of failing immediately, poll for it (bounded
        // budget) and proceed automatically the moment it's ready - the user should not
        // have to press Cast again themselves.
        if (channel.mediaType != MediaType.LIVE) {
            val initialFile = localFileProvider?.invoke()
            val alreadyReady = initialFile != null && initialFile.isFile && initialFile.length() > 0L

            if (!alreadyReady) {
                onPreparing?.invoke()

                var elapsedMs = 0L
                val pollIntervalMs = 1000L
                val budgetMs = 60_000L

                lateinit var poll: () -> Unit
                poll = {
                    val candidate = localFileProvider?.invoke()
                    if (candidate != null && candidate.isFile && candidate.length() > 0L) {
                        proceedWithLocalFile(candidate)
                    } else {
                        elapsedMs += pollIntervalMs
                        if (elapsedMs >= budgetMs) {
                            onResult(false, "Video preparation timed out — try again")
                        } else {
                            pollHandler.postDelayed({ poll() }, pollIntervalMs)
                        }
                    }
                }
                pollHandler.postDelayed({ poll() }, pollIntervalMs)
                return
            }

            proceedWithLocalFile(initialFile)
            return
        }

        proceedWithLocalFile(null)
    }

    '''

src = src[:start_m.start()] + new_function + src[end_m.start():]

with open(path, "w", encoding="utf-8") as f:
    f.write(src)

print("Patched castChannel successfully.")

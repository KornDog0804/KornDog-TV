package com.lumora

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.lumora.data.remote.concerts.ConcertCatalogClient
import com.lumora.model.Channel
import kotlinx.coroutines.launch

internal fun MainActivity.playConcertItem(item: Channel) {
    val sourceQueue = concertQueues[item.id]
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(item)

    val playableQueue = sourceQueue.mapNotNull { track ->
        youtubeVideoId(track.url)?.let { key ->
            track to key
        }
    }

    if (playableQueue.isEmpty()) {
        android.widget.Toast.makeText(
            this,
            "That concert could not be opened.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        return
    }

    showConcertQueuePlayer(
        title = item.name,
        tracks = playableQueue
    )
}

private fun MainActivity.showConcertQueuePlayer(
    title: String,
    tracks: List<Pair<Channel, String>>
) {
    val density = resources.displayMetrics.density

    val trackTitle = android.widget.TextView(this).apply {
        setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this@showConcertQueuePlayer,
                R.color.text_primary
            )
        )
        textSize = 16f
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(
            (16 * density).toInt(),
            (10 * density).toInt(),
            (16 * density).toInt(),
            (8 * density).toInt()
        )
    }

    val webView = android.webkit.WebView(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false

        webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                return request.isForMainFrame
            }
        }
    }

    val dialog = android.app.Dialog(
        this,
        android.R.style.Theme_Black_NoTitleBar_Fullscreen
    )

    class ConcertBridge {
        @android.webkit.JavascriptInterface
        fun onTrackChanged(index: Int) {
            runOnUiThread {
                val channel = tracks.getOrNull(index)?.first
                    ?: return@runOnUiThread

                trackTitle.text =
                    "${index + 1} / ${tracks.size}  ·  ${channel.name}"
            }
        }

        @android.webkit.JavascriptInterface
        fun onQueueFinished() {
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@showConcertQueuePlayer,
                    "Stitched set finished.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    webView.addJavascriptInterface(
        ConcertBridge(),
        "ConcertBridge"
    )

    fun controlButton(
        label: String,
        action: () -> Unit
    ) = android.widget.Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding(
            (6 * density).toInt(),
            (8 * density).toInt(),
            (6 * density).toInt(),
            (8 * density).toInt()
        )
        layoutParams = android.widget.LinearLayout.LayoutParams(
            0,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginStart = (3 * density).toInt()
            marginEnd = (3 * density).toInt()
        }
        setOnClickListener { action() }
    }

    val previous = controlButton("Prev") {
        webView.evaluateJavascript(
            "window.korndogPrevious && window.korndogPrevious();",
            null
        )
    }

    val playPause = controlButton("Pause") {
        webView.evaluateJavascript(
            "window.korndogToggle && window.korndogToggle();",
            null
        )
    }

    val next = controlButton("Next") {
        webView.evaluateJavascript(
            "window.korndogNext && window.korndogNext();",
            null
        )
    }

    val youtube = controlButton("YouTube") {
        webView.evaluateJavascript(
            "window.korndogCurrentIndex ? window.korndogCurrentIndex() : '0';"
        ) { raw ->
            val index = raw
                .trim()
                .trim('"')
                .toIntOrNull()
                ?: 0

            val track = tracks.getOrNull(index)
                ?: return@evaluateJavascript

            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(track.first.url)
            ).apply {
                setPackage("com.google.android.youtube")
            }

            runCatching {
                startActivity(intent)
            }.getOrElse {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(track.first.url)
                    )
                )
            }
        }
    }

    val close = controlButton("Close") {
        dialog.dismiss()
    }

    val controls = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setPadding(
            (8 * density).toInt(),
            (8 * density).toInt(),
            (8 * density).toInt(),
            (30 * density).toInt()
        )

        addView(previous)
        addView(playPause)
        addView(next)
        addView(youtube)
        addView(close)
    }

    val root = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setBackgroundColor(android.graphics.Color.BLACK)

        addView(trackTitle)
        addView(webView)
        addView(controls)
    }

    val idsJson = org.json.JSONArray().apply {
        tracks.forEach { (_, key) ->
            put(key)
        }
    }.toString()

    val html = """
        <!doctype html>
        <html>
        <head>
            <meta name="viewport"
                  content="width=device-width,initial-scale=1,maximum-scale=1">
            <style>
                html, body, #player {
                    width: 100%;
                    height: 100%;
                    margin: 0;
                    padding: 0;
                    overflow: hidden;
                    background: #000;
                }
            </style>
        </head>
        <body>
            <div id="player"></div>

            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
                const queue = $idsJson;
                let index = 0;
                let player = null;

                function notifyTrack() {
                    if (window.ConcertBridge) {
                        ConcertBridge.onTrackChanged(index);
                    }
                }

                function loadCurrent() {
                    if (!player || !queue[index]) return;

                    player.loadVideoById(queue[index]);
                    notifyTrack();
                }

                window.onYouTubeIframeAPIReady = function() {
                    player = new YT.Player('player', {
                        width: '100%',
                        height: '100%',
                        videoId: queue[0],
                        playerVars: {
                            autoplay: 1,
                            playsinline: 1,
                            rel: 0,
                            modestbranding: 1
                        },
                        events: {
                            onReady: function() {
                                notifyTrack();
                                player.playVideo();
                            },
                            onStateChange: function(event) {
                                if (event.data === YT.PlayerState.ENDED) {
                                    if (index + 1 < queue.length) {
                                        index += 1;
                                        loadCurrent();
                                    } else if (window.ConcertBridge) {
                                        ConcertBridge.onQueueFinished();
                                    }
                                }
                            }
                        }
                    });
                };

                window.korndogPrevious = function() {
                    if (index > 0) {
                        index -= 1;
                        loadCurrent();
                    } else if (player) {
                        player.seekTo(0, true);
                        player.playVideo();
                    }
                };

                window.korndogNext = function() {
                    if (index + 1 < queue.length) {
                        index += 1;
                        loadCurrent();
                    }
                };

                window.korndogCurrentIndex = function() {
                    return index;
                };

                window.korndogToggle = function() {
                    if (!player) return;

                    const state = player.getPlayerState();

                    if (state === YT.PlayerState.PLAYING) {
                        player.pauseVideo();
                    } else {
                        player.playVideo();
                    }
                };
            </script>
        </body>
        </html>
    """.trimIndent()

    dialog.setContentView(root)

    dialog.setOnDismissListener {
        runCatching {
            webView.evaluateJavascript(
                "if (window.player) player.stopVideo();",
                null
            )
        }

        webView.removeJavascriptInterface("ConcertBridge")
        webView.stopLoading()
        webView.destroy()
    }

    webView.loadDataWithBaseURL(
        "https://www.youtube-nocookie.com",
        html,
        "text/html",
        "utf-8",
        null
    )

    dialog.show()
    previous.requestFocus()
}

private fun youtubeVideoId(url: String): String? {
    val value = url.trim()

    val key = when {
        "youtu.be/" in value ->
            value.substringAfter("youtu.be/")
                .substringBefore("?")
                .substringBefore("&")
                .substringBefore("/")

        "youtube.com/watch" in value ->
            value.substringAfter("v=", "")
                .substringBefore("&")
                .substringBefore("#")

        "youtube.com/embed/" in value ->
            value.substringAfter("youtube.com/embed/")
                .substringBefore("?")
                .substringBefore("&")
                .substringBefore("/")

        else -> null
    }

    return key
        ?.takeIf {
            it.matches(
                Regex("[A-Za-z0-9_-]{6,20}")
            )
        }
}

internal fun MainActivity.setupConcertCorner() {
    binding.concertContent.layoutManager =
        LinearLayoutManager(this)

    binding.concertContent.adapter =
        concertShelfAdapter
}

internal fun MainActivity.selectConcertCorner() {
    activeSettingsOverlay?.dismiss()
    activeSearchOverlay?.dismiss()

    showingHome = false
    showingDownloads = false
    showingDiscover = false

    hideCatchup()
    releaseLivePreview()

    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    binding.discoverContent.visibility = View.GONE
    binding.contentRow.visibility = View.VISIBLE

    applySidebarVisibility(tabWantsSidebar = false)

    binding.liveRow.visibility = View.GONE
    binding.seriesContent.visibility = View.GONE
    binding.filmsContent.visibility = View.GONE
    binding.downloadsContent.visibility = View.GONE
    binding.downloadsEmptyText.visibility = View.GONE
    binding.concertContent.visibility = View.VISIBLE

    updateTabStyles(binding.tabConcerts)

    if (concertShelves.isEmpty()) {
        setStatus("Loading Concert Corner…", visible = true)
        loadConcertCorner()
    } else {
        concertShelfAdapter.submitList(concertShelves)
        setStatus("", visible = false)
    }

    applyStatus()
}

internal fun MainActivity.loadConcertCorner() {
    scope.launch {
        val result = ConcertCatalogClient().loadCatalog()

        val catalog = result.getOrElse {
            if (
                binding.concertContent.visibility == View.VISIBLE
            ) {
                setStatus(
                    "Couldn't load Concert Corner.",
                    visible = true
                )
            }
            return@launch
        }

        concertShelves = catalog.shelves
        concertQueues = catalog.queues

        val shelves = catalog.shelves

        if (
            binding.concertContent.visibility == View.VISIBLE
        ) {
            concertShelfAdapter.submitList(shelves)
            binding.concertContent.scrollToPosition(0)

            setStatus(
                if (shelves.isEmpty()) {
                    "Concert Corner is empty."
                } else {
                    ""
                },
                visible = shelves.isEmpty()
            )

            applyStatus()
        }
    }
}

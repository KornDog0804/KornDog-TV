package com.lumora

import android.app.AlertDialog
import android.app.Dialog
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.IptvProviderConfig
import com.lumora.data.IptvProviderStore
import com.lumora.data.remote.stremio.StremioAddonClient
import com.lumora.data.remote.stremio.StremioAddonStore
import com.lumora.plugin.DiscoveredProvider
import com.lumora.plugin.DiscoveryResult
import com.lumora.plugin.ResolveResult
import com.lumora.plugin.SearchResult
import com.lumora.plugin.js.PluginScript
import com.lumora.plugin.js.PluginScriptManager
import com.lumora.plugin.js.PluginStore
import com.lumora.plugin.js.PluginStoreManager
import com.lumora.plugin.js.StoreScript
import com.lumora.torrent.TorrentEngine
import com.lumora.torrent.TorrentForegroundService
import com.lumora.plugin.TorrentResult
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

// ── Plugins & stream-search ──
//
// Extracted from MainActivity.kt; see that file's header.
/** Parses a Discover [Channel.id] of the form "tmdb:movie:123" / "tmdb:tv:123". */
internal fun MainActivity.tmdbTypeAndId(id: String): Pair<String, Int>? {
    val parts = id.split(":")
    if (parts.size != 3 || parts[0] != "tmdb") return null
    return parts[1] to (parts[2].toIntOrNull() ?: return null)
}

/** Looks up and plays a TMDB trailer for a Discover item (id already carries the TMDB id). */
internal fun MainActivity.showTrailerForDiscoverItem(item: Channel) {
    val (type, id) = tmdbTypeAndId(item.id) ?: run {
        android.util.Log.d("TrailerPlayer", "showTrailerForDiscoverItem: '${item.id}' not a tmdb id")
        return
    }
    scope.launch {
        val key = try {
            tmdbClient.trailerKey(type, id)
        } catch (e: Exception) {
            android.util.Log.e("TrailerPlayer", "trailerKey($type,$id) threw", e)
            null
        }
        android.util.Log.d("TrailerPlayer", "trailerKey($type,$id) = $key")
        if (key == null) {
            Toast.makeText(this@showTrailerForDiscoverItem, "No trailer found.", Toast.LENGTH_SHORT).show()
        } else {
            showTrailerPlayer(key)
        }
    }
}

/** Shows/hides the detail screen's Trailer button, resolving a catalog item to a TMDB id
 *  by title/year search since provider/Jellyfin content carries no TMDB id of its own. */
internal fun MainActivity.wireTrailerButton(item: Channel) {
    val button = binding.detailTrailerButton
    button.visibility = View.GONE
    button.setOnClickListener(null)
    if (!tmdbClient.hasKey()) {
        android.util.Log.d("TrailerPlayer", "wireTrailerButton: no TMDB key configured")
        return
    }
    scope.launch {
        try {
            val direct = tmdbTypeAndId(item.id)
            val resolved = direct ?: tmdbClient.resolveId(item.name, item.year, item.mediaType == MediaType.SERIES)
            android.util.Log.d("TrailerPlayer", "wireTrailerButton('${item.name}', year=${item.year}): resolved=$resolved (direct=${direct != null})")
            val (type, id) = resolved ?: return@launch
            val key = tmdbClient.trailerKey(type, id)
            android.util.Log.d("TrailerPlayer", "wireTrailerButton('${item.name}'): trailerKey=$key")
            if (key == null) return@launch
            button.visibility = View.VISIBLE
            button.setOnClickListener { showTrailerPlayer(key) }

            // Find Stream wins when present. If this title has no stream-search
            // action, Trailer becomes the first usable detail action instead
            // of leaving focus stranded on the X button.
            button.post {
                if (
                    isContentDetailVisible &&
                    binding.detailBackButton.isFocused &&
                    binding.detailFindStreamButton.visibility != View.VISIBLE &&
                    button.isShown
                ) {
                    button.requestFocus()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TrailerPlayer", "wireTrailerButton('${item.name}') threw", e)
        }
    }
}

/** Plays a YouTube trailer in-app, fullscreen, via the standard /embed player - loaded
 *  directly (no hand-built HTML wrapper: that rendered blank with no logged error). */
internal fun MainActivity.showTrailerPlayer(youtubeKey: String) {
    val density = resources.displayMetrics.density
    val webView = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true // YouTube's iframe player needs this or it stays blank with no error
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            // YouTube's watch/embed page top-navigates to plain youtube.com/ as a fallback
            // when an internal resource (e.g. the doubleclick ad request) fails to load -
            // seen on networks that block ad domains. Refuse every main-frame navigation
            // outright: this player never legitimately needs to leave the embed URL.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && !request.url.toString().contains("/embed/")) {
                    android.util.Log.d("TrailerPlayer", "blocked main-frame navigation to ${request.url}")
                    return true
                }
                return false
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                android.util.Log.e(
                    "TrailerPlayer",
                    "onReceivedError url=${request.url} code=${error.errorCode} desc=${error.description}"
                )
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                android.util.Log.e(
                    "TrailerPlayer",
                    "onReceivedHttpError url=${request.url} status=${response.statusCode}"
                )
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                android.util.Log.d("TrailerPlayer", "onPageStarted url=$url")
            }
            override fun onPageFinished(view: WebView, url: String?) {
                android.util.Log.d("TrailerPlayer", "onPageFinished url=$url")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                android.util.Log.d("TrailerPlayer", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
    }
    val closeButton = Button(this).apply {
        text = "Close"
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = (16 * density).toInt()
            rightMargin = (16 * density).toInt()
        }
    }
    val root = FrameLayout(this).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(webView)
        addView(closeButton)
    }
    val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    dialog.setContentView(root)
    closeButton.setOnClickListener { dialog.dismiss() }
    dialog.setOnDismissListener { webView.destroy() }
    // A raw loadUrl only sends the Referer header on the very first request, not on the
    // player's own follow-up calls - got as far as fixing error 153 but still hit 152.
    // Giving the WebView's document itself a youtube-nocookie.com origin (via
    // loadDataWithBaseURL) plus an explicit iframe referrerpolicy covers those too.
    val html = """
        <html><body style="margin:0;padding:0;background:#000;">
        <iframe width="100%" height="100%"
            src="https://www.youtube-nocookie.com/embed/$youtubeKey?autoplay=1&playsinline=1"
            frameborder="0" referrerpolicy="strict-origin-when-cross-origin"
            allow="autoplay; encrypted-media" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
    webView.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
    dialog.show()
    closeButton.requestFocus()
}

internal fun MainActivity.wireFindStreamButton(item: Channel) {
    val button = binding.detailFindStreamButton
    val plugin = enabledStreamSearchPlugin(item)
    val hasStremio = StremioAddonStore.load(prefs).any { it.enabled }

    val eligible =
        (plugin != null || hasStremio) &&
            (item.mediaType == MediaType.MOVIE ||
                item.mediaType == MediaType.SERIES)

    button.visibility = if (eligible) View.VISIBLE else View.GONE

    if (!eligible) {
        button.setOnClickListener(null)
        return
    }

    // The detail page initially parks focus on Back while its actions are
    // being wired. PLAY used to steal that focus afterward; now that the
    // broken PLAY action is intentionally gone, Find Stream is the primary
    // action. Only take focus while Back still owns it so an async refresh
    // can never yank the remote away from something the user selected.
    button.post {
        if (
            isContentDetailVisible &&
            binding.detailBackButton.isFocused &&
            button.isShown
        ) {
            button.requestFocus()
        }
    }

    button.setOnClickListener {
        if (item.mediaType == MediaType.SERIES) {
            showSeriesEpisodePicker(plugin, item)
        } else {
            showStreamSearchDialog(plugin, item)
        }
    }
}

/**
 * Resolves a magnet token via the native [TorrentEngine] instead of a JS plugin's own
 * `resolve()` - see [PluginScript.resolvesNatively]. Unlike a JS-resolved plain http(s) URL,
 * this one is served by a local HTTP server this engine instance owns, so it's kept alive in
 * [activeTorrentSession] for the life of playback (see [hidePlayer]/`onDestroy`).
 *
 * [activeTorrentSession] is set *before* the blocking [TorrentEngine.start] call, not after -
 * `start()` can take minutes (metadata fetch + buffering), and [TorrentEngine] only stops
 * that wait when its own `cancelled` flag is set by [TorrentEngine.stop] (coroutine
 * cancellation alone doesn't interrupt it - see [TorrentEngine]'s kdoc). Setting the field
 * early lets a caller that cancels mid-resolve (e.g. the Find Stream dialog's cancel
 * listener) actually reach and stop this engine instead of it finishing unattended minutes
 * later and popping up playback for a stream the user already backed out of.
 */
internal suspend fun MainActivity.resolveTorrentStream(
    magnet: String,
    season: Int?,
    episode: Int?,
    onProgress: (String) -> Unit
): ResolveResult {
    activeTorrentSession?.let { old -> Thread { runCatching { old.stop() } }.start() }
    TorrentForegroundService.start(this)
    val engine = TorrentEngine(this)
    activeTorrentSession = engine
    return try {
        val url = withContext(Dispatchers.IO) { engine.start(magnet, season, episode, onProgress) }
        ResolveResult.Ready(url)
    } catch (e: Exception) {
        if (activeTorrentSession === engine) activeTorrentSession = null
        withContext(Dispatchers.IO) { runCatching { engine.stop() } }
        TorrentForegroundService.stop(this)
        ResolveResult.Failed(e.message ?: "Could not resolve stream")
    }
}

/**
 * The enabled `stream_search` plugin to use for [item], if any. With more than one enabled
 * (e.g. an anime plugin and a general torrent plugin) this picks by declared
 * [PluginScript.contentTypes] instead of an arbitrary one - without [item] (existence-only
 * checks: is *any* stream_search plugin enabled, at all, for gating tabs/chrome) it just
 * returns the first. Anime catalog items carry the "anime:" id prefix set by
 * [fetchAnimeChannels] - the only signal Lumora itself has for "this title is anime",
 * entirely independent of which plugin (if any) declares itself able to handle that.
 */
/** Stable identity for a plugin-resolved stream. Everything that keys off a channel id -
 *  the saved playback position above all - needs this to come out the same for the same
 *  episode on a later launch, so it's derived from the plugin + token + episode rather than
 *  anything about the particular resolve that produced the URL. */
internal fun MainActivity.pluginChannelId(plugin: PluginScript, token: String, episode: Int?): String =
    "plugin:${plugin.id}:$token" + (episode?.let { ":e$it" } ?: "")

internal fun MainActivity.enabledStreamSearchPlugin(item: Channel? = null): PluginScript? {
    val candidates = pluginScriptManager.getDiscoveredScripts().filter { it.enabled && it.supportsStreamSearch }
    if (item == null) return candidates.firstOrNull()
    val isAnime = item.id.startsWith("anime:")
    return candidates.firstOrNull { isAnime == it.contentTypes.contains("anime") } ?: candidates.firstOrNull()
}

/**
 * Runs a plugin stream search for [item], lists what comes back, and on a pick resolves it
 * to a playable URL and starts the player. Unlike the old Messenger plugins, a JS script has
 * no process of its own to keep bound during playback - `resolve()` just returns a plain
 * http(s) URL the player hits directly, so there's nothing to hold open past the pick.
 */
/**
 * Re-runs enabled Stremio add-ons for a persisted Find Stream item and returns
 * the best fresh direct URL. The saved Channel id is deliberately preserved so
 * PlaybackPositionStore still finds the existing resume position even though
 * the disposable CDN URL changed.
 */
internal suspend fun MainActivity.refreshSavedStreamSearch(channel: Channel): Channel? {
    // New saves carry the durable TMDB/search identity. Older Continue Watching
    // entries do not, so recover series/episode identity from the display name
    // rather than ever falling back to yesterday's signed CDN URL.
    val episodeMatch = Regex("""\s+S(\d{1,2})E(\d{1,2})$""", RegexOption.IGNORE_CASE)
        .find(channel.name)

    val season =
        channel.streamSearchSeason
            ?: episodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

    val episode =
        channel.episodeNum
            ?: episodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

    val isSeries = season != null && episode != null

    val cleanName =
        episodeMatch?.let { channel.name.removeRange(it.range).trim() }
            ?: channel.name

    val savedItemId = channel.streamSearchItemId

    val catalogItem =
        savedItemId?.let { id ->
            allChannels.firstOrNull { it.id == id }
        } ?: allChannels
            .filter {
                it.mediaType == if (isSeries) MediaType.SERIES else MediaType.MOVIE
            }
            .minByOrNull {
                kotlin.math.abs(it.name.length - cleanName.length) +
                    if (it.name.equals(cleanName, ignoreCase = true)) -1000 else 0
            }
            ?.takeIf { it.name.equals(cleanName, ignoreCase = true) }

    val tmdb =
        savedItemId?.let(::tmdbTypeAndId)
            ?: catalogItem?.let { item ->
                tmdbTypeAndId(item.id)
                    ?: tmdbClient.resolveId(
                        item.name,
                        item.year,
                        isSeries
                    )
            }
            ?: tmdbClient.resolveId(
                cleanName,
                channel.year,
                isSeries
            )
            ?: return null

    val imdbId =
        tmdbClient.imdbId(tmdb.first, tmdb.second)
            ?: return null

    val type = if (isSeries) "series" else "movie"

    val contentId = when {
        type == "movie" ->
            imdbId

        season != null && episode != null ->
            "$imdbId:$season:$episode"

        else ->
            return null
    }

    val addons =
        StremioAddonStore.load(prefs)
            .filter { it.enabled }

    if (addons.isEmpty()) return null

    val stremioClient = StremioAddonClient()

    val streams = coroutineScope {
        addons.map { addon ->
            async {
                val manifest =
                    stremioClient.fetchManifest(addon.manifestUrl)
                        .getOrNull()
                        ?: return@async emptyList()

                stremioClient.streams(
                    manifest = manifest,
                    type = type,
                    contentId = contentId
                ).getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    val fresh = streams
        .filter { !it.url.isNullOrBlank() }
        .distinctBy { it.url }
        .sortedByDescending { stream ->
            when {
                stream.title.contains("2160p", true) ||
                    stream.title.contains("4k", true) -> 4

                stream.title.contains("1080p", true) -> 3
                stream.title.contains("720p", true) -> 2
                else -> 1
            }
        }
        .firstOrNull()
        ?: return null

    val durableId =
        savedItemId
            ?: catalogItem?.id
            ?: "tmdb:${tmdb.first}:${tmdb.second}"

    return channel.copy(
        url = fresh.url!!,
        mediaType = if (isSeries) MediaType.SERIES else MediaType.MOVIE,
        episodeNum = episode,
        streamHeaders = fresh.requestHeaders.ifEmpty { null },

        // This result is already a fresh Stremio direct stream. Do not carry an
        // old JS-plugin token into showPlayerFor(), or it can resolve again and
        // replace this fresh URL with an unrelated/expired one.
        pluginToken = null,
        pluginId = null,

        streamSearchItemId = durableId,
        streamSearchSeason = season
    )
}

internal suspend fun MainActivity.stremioSubtitlesFor(
    item: Channel,
    season: Int? = null,
    episode: Int? = null
): List<com.lumora.plugin.PluginSubtitle> {
    val addons = StremioAddonStore
        .load(prefs)
        .filter { it.enabled }

    if (addons.isEmpty()) return emptyList()

    val directTmdb = tmdbTypeAndId(item.id)
    val resolvedTmdb = directTmdb ?: tmdbClient.resolveId(
        item.name,
        item.year,
        item.mediaType == MediaType.SERIES
    )

    val tmdb = resolvedTmdb ?: return emptyList()

    val imdbId = tmdbClient.imdbId(
        tmdb.first,
        tmdb.second
    ) ?: return emptyList()

    val type =
        if (item.mediaType == MediaType.SERIES) "series"
        else "movie"

    val contentId = when {
        type == "movie" -> imdbId

        season != null && episode != null ->
            "$imdbId:$season:$episode"

        else -> return emptyList()
    }

    val client = StremioAddonClient()

    return coroutineScope {
        addons.map { addon ->
            async {
                val manifest =
                    client.fetchManifest(addon.manifestUrl)
                        .getOrNull()
                        ?: return@async emptyList()

                if ("subtitles" !in manifest.resources) {
                    return@async emptyList()
                }

                client.subtitles(
                    manifest = manifest,
                    type = type,
                    contentId = contentId
                )
                    .getOrDefault(emptyList())
                    .map { subtitle ->
                        com.lumora.plugin.PluginSubtitle(
                            url = subtitle.url,
                            label = buildString {
                                subtitle.label?.let { append(it) }

                                if (!subtitle.source.isNullOrBlank()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(subtitle.source)
                                }
                            }.ifBlank { subtitle.lang ?: "Subtitle" },
                            language = subtitle.lang,
                            isDefault = false
                        )
                    }
            }
        }
            .awaitAll()
            .flatten()
            .distinctBy { subtitle ->
                subtitle.url
                    .substringBefore('?')
                    .lowercase()
            }
    }
}

internal fun MainActivity.showStreamSearchDialog(
    plugin: PluginScript?,
    item: Channel,
    season: Int? = null,
    episode: Int? = null,
    autoPlayBest: Boolean = false
) {

    // Continue Watching restores an episode Channel directly. Its caller may
    // not have the original detail-page season/episode arguments anymore, but
    // the persisted stream-search identity still does.
    val effectiveSeason = season ?: item.streamSearchSeason
    val effectiveEpisode = episode ?: item.episodeNum

    data class StreamEntry(
        val result: TorrentResult,
        val resolver: String,
        val headers: Map<String, String> = emptyMap()
    )

    val epTag =
        if (effectiveSeason != null && effectiveEpisode != null) {
            " S%02dE%02d".format(effectiveSeason, effectiveEpisode)
        } else {
            ""
        }

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad =
            (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    val status = TextView(this).apply {
        text = "Searching…"
        setTextColor(
            ContextCompat.getColor(
                this@showStreamSearchDialog,
                R.color.text_secondary
            )
        )
    }

    val resultsHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    val scroll = ScrollView(this).apply {
        isFillViewport = true
        addView(resultsHost)
    }

    container.addView(status)
    container.addView(
        scroll,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
    )

    val dialog = AlertDialog.Builder(this)
        .setTitle("Find Stream — ${item.name}$epTag")
        .setView(container)
        .setNegativeButton("Cancel", null)
        .create()

    val pluginSource =
        plugin?.let { pluginScriptManager.readSource(it) }

    val results = mutableListOf<StreamEntry>()
    val stremioClient = StremioAddonClient()
    var currentQualityFilter = "All"

    // ── Autoplay guard ─────────────────────────────
    // Guards against playing more than one source at once and lets both the
    // decision-window timer and the "search fully finished" fallback race
    // safely - whichever fires first wins, the other is a no-op.
    var autoPlayCommitted = false
    var autoPlayDecisionJob: Job? = null

    // Manual Find Stream should always land on the first actual stream result.
    // Addons answer asynchronously, so layout changes can otherwise push focus
    // down to the quality filters / Cancel button after the first row appears.
    var initialStreamFocusClaimed = false

    // Results are ranked while addons are still answering. A row that was
    // first when it arrived may end up 150 rows down after better sources
    // are inserted ahead of it. Track whether the user has actually moved
    // so we can safely land on the FINAL #1 result once scraping finishes.
    var manualStreamUserMoved = false

    fun qualityTierOf(title: String): String = when {
        title.contains("2160") || title.contains("4K", true) -> "4K"
        title.contains("1080") -> "1080p"
        title.contains("720") -> "720p"
        else -> "Other"
    }
    val qualityFilterRow = LinearLayout(this@showStreamSearchDialog).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 8, 0, 8)
    }
    fun applyQualityFilter() {
        for (i in 0 until resultsHost.childCount) {
            val child = resultsHost.getChildAt(i)
            val tier = child.tag as? String ?: "Other"
            child.visibility = if (currentQualityFilter == "All" || currentQualityFilter == tier) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
    listOf("All", "4K", "1080p", "720p").forEach { label ->
        val btn = TextView(this@showStreamSearchDialog).apply {
            text = label
            textSize = 13f
            setPadding(24, 12, 24, 12)

            // These are real controls, not labels. Without focusability Android TV /
            // Fire TV DPAD navigation skips straight over 4K/1080p/720p.
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true

            setOnClickListener {
                currentQualityFilter = label
                applyQualityFilter()

                // Filtering can remove the currently-focused result row. Keep focus
                // anchored on the selected quality chip instead of letting it vanish.
                requestFocus()
            }
        }
        qualityFilterRow.addView(btn)
    }

    container.addView(qualityFilterRow)

    fun stableId(entry: StreamEntry): String {
        val hash = entry.result.token.hashCode()
            .toUInt()
            .toString(16)

        return "stream:${entry.resolver}:$hash" +
            (effectiveEpisode?.let { ":e$it" } ?: "")
    }

    val attemptedStreamTokens = mutableSetOf<String>()

    /*
     * Rank Find Stream results by practical playback usefulness rather than
     * whichever addon happened to answer first.
     *
     * Nothing is discarded here. This only changes ordering, and therefore
     * also improves the order used by automatic source failover.
     */
    fun streamRank(entry: StreamEntry): Int {
        val title = entry.result.title.lowercase()
        val source = entry.result.source.orEmpty().lowercase()

        var score = 0

        // Debrid/cache hints. Real-Debrid is currently the most reliable
        // path on this install, followed by TorBox and Premiumize.
        when {
            "[rd" in title || "real-debrid" in title -> score += 10000
            "[tb" in title || "torbox" in title -> score += 9000
            "[pm" in title || "premiumize" in title -> score += 8000
        }

        // AIO is useful as an aggregator, but a magnet result avoids stale
        // direct wrapper/session URLs and can be resolved by Lumora itself.
        if ("aiostreams" in source) score += 1200

        // Ready HTTPS debrid/cache URLs are the fastest path into Media3.
        // Magnets remain fully available, but they require another resolution
        // hop before playback can begin.
        if (entry.resolver == "direct") {
            score += 1600
        } else if (entry.resolver == "torrent") {
            score += 500
        }

        // Prefer normal English/multi-audio releases.
        if (
            Regex(
                """\b(english|eng|multi(?:[- ]?audio)?|dual[- ]?audio)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(title)
        ) {
            score += 700
        }

        // Push clearly foreign-only releases down.
        if (
            Regex(
                """\b(french|fre|fra|german|ger|deu|russian|rus|italian|ita|spanish|spa|latino|hindi|hin)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(title) &&
            !Regex(
                """\b(english|eng|multi|dual[- ]?audio)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(title)
        ) {
            score -= 2500
        }

        // Resolution preference. 1080p first for fast/reliable TV playback,
        // then 4K, then 720p.
        when {
            "1080p" in title -> score += 600
            "2160p" in title || Regex("""\b4k\b""").containsMatchIn(title) ->
                score += 500
            "720p" in title -> score += 350
            "480p" in title -> score += 100
        }

        // Useful source preferences without excluding anything.
        when {
            "elfcache" in title -> score += 350
            "torrentio" in source -> score += 250
            "mediafusion" in source -> score += 150
            "comet" in source -> score += 50
        }

        return score
    }

    fun playResult(entry: StreamEntry) {
        val result = entry.result
        attemptedStreamTokens += result.token

        // Keep the whole merged Find Stream result set alive as a fallback pool.
        // If Media3 rejects this source, onPlayerError invokes this callback and
        // we resolve/play the next source that hasn't already been tried.
        streamSearchFailover = fallback@{
            val next = results.firstOrNull {
                it.result.token !in attemptedStreamTokens
            } ?: run {
                streamSearchFailover = null
                return@fallback false
            }

            attemptedStreamTokens += next.result.token

            runOnUiThread {
                // Failover can happen after the chooser/detail window has already
                // been dismissed. Do not attach transient UI to that old window.
                android.util.Log.i(
                    "LumoraPlayback",
                    "Find Stream failover: trying ${next.result.source ?: "another source"}"
                )

                playResult(next)
            }

            true
        }

        status.text = "Loading ${result.title}…"
        resultsHost.removeAllViews()

        scope.launch {
            // Sidecar subtitle discovery runs beside stream resolution instead
            // of after it. A slow subtitle addon gets a very small budget and
            // cannot add several seconds to video startup. Embedded/resolver
            // subtitles remain untouched.
            val addonSubtitlesDeferred = async {
                withTimeoutOrNull(350L) {
                    stremioSubtitlesFor(
                        item = item,
                        season = effectiveSeason,
                        episode = effectiveEpisode
                    )
                } ?: emptyList()
            }

            val resolved = when (entry.resolver) {
                "direct" -> {
                    ResolveResult.Ready(
                        url = result.token,
                        headers = entry.headers
                    )
                }

                "torrent" -> {
                    resolveTorrentStream(
                        result.token,
                        effectiveSeason,
                        effectiveEpisode
                    ) { line ->
                        runOnUiThread {
                            status.text = line
                        }
                    }
                }

                else -> {
                    when {
                        plugin == null || pluginSource == null ->
                            ResolveResult.Failed(
                                "The source plugin is unavailable"
                            )

                        plugin.resolvesNatively ->
                            resolveTorrentStream(
                                result.token,
                                effectiveSeason,
                                effectiveEpisode
                            ) { line ->
                                runOnUiThread {
                                    status.text = line
                                }
                            }

                        else ->
                            jsPluginEngine.resolve(
                                pluginSource,
                                result.token,
                                effectiveSeason,
                                effectiveEpisode
                            )
                    }
                }
            }

            when (resolved) {
                is ResolveResult.Ready -> {
                    dialog.dismiss()
                    hideContentDetail()

                    // This normally completed while the stream itself was
                    // resolving. If the addon was slow it already timed out,
                    // so playback is never stuck waiting on subtitle discovery.
                    val addonSubtitles = addonSubtitlesDeferred.await()
                    val mergedSubtitles =
                        (resolved.subtitles + addonSubtitles)
                            .distinctBy {
                                it.url
                                    .substringBefore('?')
                                    .substringBefore('#')
                                    .lowercase()
                            }

                    showPlayerFor(
                        Channel(
                            id = stableId(entry),
                            name = item.name + epTag,
                            url = resolved.url,
                            posterUrl = item.posterUrl,
                            logoUrl = item.logoUrl,
                            group = item.group,
                            categoryName = item.categoryName,
                            mediaType = item.mediaType,
                            episodeNum = effectiveEpisode,
                            streamSearchItemId = item.id,
                            streamSearchSeason = effectiveSeason,
                            streamHeaders =
                                resolved.headers.ifEmpty { null },
                            pluginToken =
                                if (entry.resolver == "plugin") {
                                    result.token
                                } else {
                                    null
                                },
                            pluginId =
                                if (entry.resolver == "plugin") {
                                    plugin?.id
                                } else {
                                    null
                                }
                        ),
                        externalSubtitles =
                            mergedSubtitles.map(
                                ::externalSubtitleFor
                            ),
                        pluginStreamAlreadyResolved = true,
                        audio = result.audio
                    )

                    detailReturnItem = item
                }

                is ResolveResult.Failed -> {
                    val fallbackStarted =
                        streamSearchFailover?.invoke() == true

                    if (!fallbackStarted) {
                        streamSearchFailover = null
                        Toast.makeText(
                            this@showStreamSearchDialog,
                            resolved.message,
                            Toast.LENGTH_LONG
                        ).show()

                        // Autoplay ran out of usable sources - fall back to letting the
                        // user browse the chooser manually instead of a bare toast with
                        // nothing else on screen.
                        if (autoPlayBest) {
                            dialog.show()
                        } else {
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
    }

    // Committing to autoplay is guarded so the 1-2s decision-window timer and
    // the "search fully finished" fallback below can never both fire.
    fun commitAutoPlay(entry: StreamEntry) {
        if (autoPlayCommitted) return
        autoPlayCommitted = true
        autoPlayDecisionJob?.cancel()
        autoPlayDecisionJob = null
        status.text = "Best source: ${entry.result.source ?: "stream"} · starting…"
        playResult(entry)
    }

    fun addResult(
        entry: StreamEntry,
        atFront: Boolean = false
    ) {
        if (results.any {
                it.result.token == entry.result.token
            }
        ) {
            return
        }

        val insertIndex =
            if (atFront) {
                0
            } else {
                val rank = streamRank(entry)

                results.indexOfFirst {
                    streamRank(it) < rank
                }.takeIf { it >= 0 }
                    ?: results.size
            }

        results.add(insertIndex, entry)

        val row = layoutInflater.inflate(
            R.layout.item_stream_result,
            resultsHost,
            false
        )

        row.findViewById<TextView>(
            R.id.streamTitle
        ).text = entry.result.title
        row.tag = qualityTierOf(entry.result.title)
        row.visibility = if (currentQualityFilter == "All" || currentQualityFilter == row.tag) android.view.View.VISIBLE else android.view.View.GONE

        val displayTitle = entry.result.title

        val derivedQuality =
            entry.result.quality
                ?: Regex(
                    "(2160p|4k|1080p|720p|480p)",
                    RegexOption.IGNORE_CASE
                ).find(displayTitle)?.value

        val codec =
            Regex(
                "(HEVC|H\\.?265|H\\.?264|AV1|x265|x264)",
                RegexOption.IGNORE_CASE
            ).find(displayTitle)?.value

        val hdr =
            Regex(
                "(HDR10\\+?|HDR|DV|Dolby Vision)",
                RegexOption.IGNORE_CASE
            ).find(displayTitle)?.value

        val audio =
            Regex(
                "(Atmos|Dolby Digital Plus|DDP?\\+?|AAC|DTS(?:-HD)?|TrueHD)",
                RegexOption.IGNORE_CASE
            ).find(displayTitle)?.value

        val sizeFromTitle =
            Regex(
                "(\\d+(?:\\.\\d+)?\\s?(?:GB|MB))",
                RegexOption.IGNORE_CASE
            ).find(displayTitle)?.value

        // Surface audio/language hints directly in Find Stream.
        // Stremio add-ons commonly encode these in the stream title rather
        // than a dedicated field, so keep this display-only and conservative.
        val language = entry.result.language ?: when {
            Regex(
                "\\b(english|eng|en)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(displayTitle) -> "English"

            Regex(
                "\\b(japanese|jpn|ja)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(displayTitle) -> "Japanese"

            Regex(
                "\\b(spanish|spa|es)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(displayTitle) -> "Spanish"

            Regex(
                "\\b(french|fre|fra|fr)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(displayTitle) -> "French"

            Regex(
                "\\b(german|ger|deu|de)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(displayTitle) -> "German"

            Regex(
                "\\b(italian|ita|it)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(displayTitle) -> "Italian"

            Regex(
                "\\b(korean|kor|ko)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(displayTitle) -> "Korean"

            Regex(
                "\\b(chinese|chi|zho|zh)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(displayTitle) -> "Chinese"

            Regex(
                "\\b(multi(?:[- ]?audio)?|dual[- ]?audio|multi[- ]?lang)\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(displayTitle) -> "Multi"

            entry.result.audio.equals("dub", true) -> "Dub"
            entry.result.audio.equals("sub", true) -> "Sub"

            !entry.result.audio.isNullOrBlank() ->
                entry.result.audio

            else -> null
        }

        row.findViewById<TextView>(
            R.id.streamMeta
        ).text = listOfNotNull(
            derivedQuality,
            language,
            codec,
            hdr,
            audio,
            entry.result.seeders?.let { "$it seeders" },
            entry.result.size ?: sizeFromTitle,
            entry.result.source
        )
            .distinct()
            .joinToString("  ·  ")

        row.setOnKeyListener { _, keyCode, event ->
            if (
                event.action == android.view.KeyEvent.ACTION_DOWN &&
                keyCode in listOf(
                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                )
            ) {
                manualStreamUserMoved = true
            }
            false
        }

        row.setOnClickListener {
            playResult(entry)
        }

        if (atFront) {
            resultsHost.addView(row, 0)
        } else {
            resultsHost.addView(
                row,
                insertIndex.coerceIn(0, resultsHost.childCount)
            )
        }

        status.text = "${results.size} result(s)"

        if (!autoPlayBest && !initialStreamFocusClaimed) {
            initialStreamFocusClaimed = true

            row.post {
                row.requestFocus()
                scroll.scrollTo(0, 0)
            }
        }

        // Playback intent: arm a short decision window off the FIRST result that
        // lands, not the last. This gives late/slow addons ~1.5s to beat whatever's
        // currently ranked #1 without making the user wait for the full 200-400
        // result sweep across every addon.
        if (autoPlayBest && !autoPlayCommitted && autoPlayDecisionJob == null) {
            autoPlayDecisionJob = scope.launch {
                delay(1500L)
                if (!autoPlayCommitted) {
                    results.firstOrNull()?.let { commitAutoPlay(it) }
                }
            }
        }
    }

    val searchJob = scope.launch {
        coroutineScope {
            if (plugin != null && pluginSource != null) {
                launch {
                    jsPluginEngine.runSearch(
                        source = pluginSource,
                        query = item.name,
                        year = item.year?.toIntOrNull(),
                        season = effectiveSeason,
                        episode = effectiveEpisode,
                        onProgress = {
                            if (results.isEmpty()) {
                                status.text = it
                            }
                        },
                        onResult = { result ->
                            val atFront =
                                prefs.getBoolean(
                                    PREF_PREFER_DUB_AUDIO,
                                    false
                                ) &&
                                    result.audio == "dub"

                            addResult(
                                StreamEntry(
                                    result = result,
                                    resolver = "plugin"
                                ),
                                atFront
                            )
                        }
                    )
                }
            }

            launch {
                val addons = StremioAddonStore
                    .load(prefs)
                    .filter { it.enabled }

                if (addons.isEmpty()) {
                    return@launch
                }

                runCatching {
                    java.io.File("/sdcard/Download/streamidentity.log")
                        .appendText(
                            "${System.currentTimeMillis()}: START " +
                                "name=${item.name} id=${item.id} year=${item.year} " +
                                "type=${item.mediaType} season=$effectiveSeason " +
                                "episode=$effectiveEpisode addons=${addons.size}\n"
                        )
                }

                val directTmdb = tmdbTypeAndId(item.id)
                val resolvedTmdb = directTmdb ?: tmdbClient.resolveId(
                    item.name,
                    item.year,
                    item.mediaType == MediaType.SERIES
                )

                runCatching {
                    java.io.File("/sdcard/Download/streamidentity.log")
                        .appendText(
                            "${System.currentTimeMillis()}: TMDB " +
                                "direct=$directTmdb resolved=$resolvedTmdb\n"
                        )
                }

                val tmdb = resolvedTmdb ?: run {
                    runCatching {
                        java.io.File("/sdcard/Download/streamidentity.log")
                            .appendText("${System.currentTimeMillis()}: ABORT no TMDB match\n")
                    }
                    return@launch
                }

                val imdbId = tmdbClient.imdbId(
                    tmdb.first,
                    tmdb.second
                )

                runCatching {
                    java.io.File("/sdcard/Download/streamidentity.log")
                        .appendText(
                            "${System.currentTimeMillis()}: IMDB id=$imdbId " +
                                "tmdbType=${tmdb.first} tmdbId=${tmdb.second}\n"
                        )
                }

                if (imdbId == null) {
                    runCatching {
                        java.io.File("/sdcard/Download/streamidentity.log")
                            .appendText("${System.currentTimeMillis()}: ABORT no IMDB id\n")
                    }
                    return@launch
                }

                val type =
                    if (item.mediaType == MediaType.SERIES) {
                        "series"
                    } else {
                        "movie"
                    }

                val contentId = when {
                    type == "movie" -> imdbId

                    effectiveSeason != null && effectiveEpisode != null ->
                        "$imdbId:$effectiveSeason:$effectiveEpisode"

                    else -> return@launch
                }

                val seenStreamKeys = mutableSetOf<String>()

                coroutineScope {
                    addons.forEach { addon ->
                        launch {
                            val manifestResult =
                                stremioClient.fetchManifest(
                                    addon.manifestUrl
                                )

                            val manifest = manifestResult.getOrNull()

                            if (manifest == null) {
                                status.text = "${addon.name}: manifest failed"
                                return@launch
                            }

                            val streamsResult =
                                stremioClient.streams(
                                    manifest = manifest,
                                    type = type,
                                    contentId = contentId
                                )

                            val addonStreams =
                                streamsResult.getOrElse {
                                    status.text =
                                        "${manifest.name}: stream request failed"
                                    emptyList()
                                }

                            status.text =
                                "${manifest.name}: ${addonStreams.size} result(s)"

                            addonStreams.forEach { stream ->
                                val key =
                                    stream.url
                                        ?: stream.magnet
                                        ?: stream.title

                                if (!seenStreamKeys.add(key)) {
                                    return@forEach
                                }

                                val token =
                                    stream.url
                                        ?: stream.magnet
                                        ?: return@forEach

                                addResult(
                                    StreamEntry(
                                        result = TorrentResult(
                                            title = stream.title,
                                            token = token,
                                            seeders = null,
                                            size = null,
                                            quality = Regex(
                                                "(2160p|4k|1080p|720p|480p)",
                                                RegexOption.IGNORE_CASE
                                            ).find(stream.title)?.value,
                                            source = stream.source,
                                            audio = null
                                        ),
                                        resolver =
                                            if (stream.url != null) {
                                                "direct"
                                            } else {
                                                "torrent"
                                            },
                                        headers = stream.requestHeaders
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Episode-row and Series Play/Resume requests are intent to PLAY, not intent
        // to browse hundreds of sources. If the decision-window timer in addResult()
        // already committed to a source, do nothing further here - this is only the
        // fallback for a search that finished before that 1.5s window elapsed, or
        // found nothing at all.
        //
        // Manual Find Stream keeps autoPlayBest=false and still shows the full
        // chooser exactly as before.
        if (autoPlayBest) {
            if (!autoPlayCommitted) {
                if (results.isNotEmpty()) {
                    commitAutoPlay(results.first())
                } else {
                    status.text = "No streams found"
                    dialog.show()
                }
            }
            return@launch
        }

        if (results.isEmpty()) {
            status.text = "No streams found"
        } else if (!manualStreamUserMoved) {
            // Results have finished ranking. The view currently holding focus
            // may have been pushed far down by later higher-ranked inserts.
            // Land on the actual top result and put the viewport at y=0.
            resultsHost.post {
                val topResult = resultsHost.getChildAt(0)
                if (topResult != null) {
                    scroll.scrollTo(0, 0)
                    topResult.requestFocus()
                }
            }
        }
    }

    dialog.setOnCancelListener {
        searchJob.cancel()
        autoPlayDecisionJob?.cancel()

        activeTorrentSession?.let { engine ->
            Thread {
                runCatching { engine.stop() }
            }.start()
        }

        activeTorrentSession = null
        TorrentForegroundService.stop(this)
    }

    // Manual Find Stream shows the chooser immediately, same as before.
    // Playback intent (autoPlayBest) keeps it hidden unless/until autoplay
    // has exhausted its options - see commitAutoPlay's Failed branch and the
    // no-results fallback above.
    if (!autoPlayBest) {
        dialog.show()
    }
}

// ── Plugins ────────────────────────────────────

/**
 * Settings > Plugins. Lists the user's installed JS plugin scripts, lets them switch one on,
 * run its discovery job, and add whatever it proposes.
 *
 * A deliberate gate, because a script's output is still untrusted input proposing servers
 * and credentials to point this app at: no proposal is written to the provider list without
 * a per-item confirmation naming which plugin it came from. [com.lumora.plugin.js.JsHostImpl]
 * does the field validation before any of this sees a candidate.
 */
internal fun MainActivity.wirePluginsPane(dialogView: View, onProviderAdded: () -> Unit = {}) {
    val listContainer = dialogView.findViewById<LinearLayout>(R.id.settingsPluginList)
    val listEmpty = dialogView.findViewById<View>(R.id.settingsPluginListEmpty)
    val manager = pluginScriptManager

    val detailPane = dialogView.findViewById<View>(R.id.panePluginDetail)
    val listPane = dialogView.findViewById<View>(R.id.panePlugins)
    val detailBack = dialogView.findViewById<View>(R.id.pluginDetailBack)
    val detailTitle = dialogView.findViewById<TextView>(R.id.pluginDetailTitle)
    val detailDescription = dialogView.findViewById<TextView>(R.id.pluginDetailDescription)
    val detailMeta = dialogView.findViewById<TextView>(R.id.pluginDetailMeta)
    val detailEnabledRow = dialogView.findViewById<View>(R.id.pluginDetailEnabledRow)
    val detailEnabledBox = dialogView.findViewById<CheckBox>(R.id.pluginDetailEnabled)
    val detailRunButton = dialogView.findViewById<View>(R.id.pluginDetailRunButton)
    val detailRunLabel = dialogView.findViewById<TextView>(R.id.pluginDetailRunLabel)
    val detailUpdateButton = dialogView.findViewById<View>(R.id.pluginDetailUpdateButton)
    val detailUpdateLabel = dialogView.findViewById<TextView>(R.id.pluginDetailUpdateLabel)
    val detailRemoveButton = dialogView.findViewById<View>(R.id.pluginDetailRemoveButton)
    val detailResults = dialogView.findViewById<View>(R.id.pluginDetailResults)
    val detailProgress = dialogView.findViewById<View>(R.id.pluginDetailProgress)
    val detailStatus = dialogView.findViewById<TextView>(R.id.pluginDetailStatus)
    val detailCandidateList = dialogView.findViewById<LinearLayout>(R.id.pluginDetailCandidateList)

    lateinit var renderPluginList: () -> Unit
    lateinit var renderPluginDetail: () -> Unit

    fun openPluginPage(id: String) {
        openPluginId = id
        // Reachable straight from the nav rail's plugin dropdown, bypassing selectSection() -
        // so whichever section pane (e.g. EPG) was showing before has to be hidden here too,
        // or it stays visible underneath this page.
        listOf(
            R.id.paneProviders, R.id.panePlayback, R.id.paneFilters, R.id.panePrivacy,
            R.id.paneBackup, R.id.paneEpg, R.id.paneDownloads, R.id.paneGeneral, R.id.paneAbout
        ).forEach { dialogView.findViewById<View>(it)?.visibility = View.GONE }
        listPane.visibility = View.GONE
        detailPane.visibility = View.VISIBLE
        // Landing on Back rather than nowhere: the page is rebuilt asynchronously, so
        // without this the D-pad has no starting point until the render lands.
        detailBack.requestFocus()
        renderPluginDetail()
    }

    fun closePluginPage() {
        openPluginId = null
        detailPane.visibility = View.GONE
        listPane.visibility = View.VISIBLE
        liveDiscoveryStatusView = null
        liveDiscoveryCandidateList = null
        renderPluginList()
    }
    // Settings always opens on the list, never on whichever plugin was last looked at.
    openPluginId = null
    detailPane.visibility = View.GONE

    fun fetchAndAddPluginScript(url: String) {
        val scheme = url.substringBefore("://", "").lowercase(Locale.US)
        if (url.isBlank() || (scheme != "http" && scheme != "https")) {
            Toast.makeText(this, "Enter a valid http(s) link", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val text: String? = try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    OkHttpClient().newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (text.isNullOrBlank()) {
                Toast.makeText(this@wirePluginsPane, "Couldn't fetch that script", Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (val result = manager.installScript(text)) {
                is PluginScriptManager.InstallResult.Installed -> {
                    // Says so explicitly, because installing no longer switches it on and a
                    // plugin that is installed but does nothing is otherwise a puzzle.
                    val message = if (result.script.enabled) "Added ${result.script.label}"
                        else "Added ${result.script.label} - enable it to use it"
                    Toast.makeText(this@wirePluginsPane, message, Toast.LENGTH_LONG).show()
                    renderPluginList()
                }
                is PluginScriptManager.InstallResult.Rejected ->
                    Toast.makeText(this@wirePluginsPane, result.reason, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun showAddPluginScriptFromUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/my-plugin.js"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Add plugin script from URL")
            .setMessage("Enter the link to a Lumora plugin script (.js).")
            .setView(container)
            .setPositiveButton("Add") { _, _ -> fetchAndAddPluginScript(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    dialogView.findViewById<View>(R.id.settingsPluginInstallUrl)?.setOnClickListener {
        showAddPluginScriptFromUrlDialog()
    }
    wirePluginStoresSection(dialogView, manager) { renderPluginList() }

    val stremioList =
        dialogView.findViewById<LinearLayout>(
            R.id.settingsStremioAddonList
        )

    val stremioEmpty =
        dialogView.findViewById<TextView>(
            R.id.settingsStremioAddonListEmpty
        )

    val stremioAddButton =
        dialogView.findViewById<View>(
            R.id.settingsStremioAddonAdd
        )

    val stremioClient = StremioAddonClient()

    lateinit var renderStremioAddons: () -> Unit

    fun showAddStremioAddonDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/manifest.json"
            inputType =
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }

        val pad =
            (20 * resources.displayMetrics.density).toInt()

        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        val addDialog = AlertDialog.Builder(this)
            .setTitle("Add Stremio addon")
            .setMessage(
                "Enter the addon's manifest.json URL."
            )
            .setView(container)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        addDialog.setOnShowListener {
            addDialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val url =
                        input.text.toString().trim()

                    if (
                        !url.startsWith("https://") &&
                        !url.startsWith("http://")
                    ) {
                        input.error =
                            "Enter a valid HTTP or HTTPS URL"
                        return@setOnClickListener
                    }

                    input.isEnabled = false

                    scope.launch {
                        val result =
                            stremioClient.fetchManifest(url)

                        val manifest =
                            result.getOrElse { error ->
                                input.isEnabled = true
                                Toast.makeText(
                                    this@wirePluginsPane,
                                    error.message
                                        ?: "Could not load addon",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }

                        if (
                            "stream" !in manifest.resources &&
                            "subtitles" !in manifest.resources
                        ) {
                            input.isEnabled = true
                            Toast.makeText(
                                this@wirePluginsPane,
                                "${manifest.name} provides neither streams nor subtitles",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }

                        StremioAddonStore.add(
                            prefs = prefs,
                            name = manifest.name,
                            manifestUrl = url
                        )

                        renderStremioAddons()
                        addDialog.dismiss()

                        Toast.makeText(
                            this@wirePluginsPane,
                            "Added ${manifest.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
        }

        addDialog.show()
    }

    renderStremioAddons = {
        stremioList.removeAllViews()

        val addons =
            StremioAddonStore.load(prefs)

        stremioEmpty.visibility =
            if (addons.isEmpty()) View.VISIBLE
            else View.GONE

        addons.forEach { addon ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL

                val verticalPadding =
                    (12 * resources.displayMetrics.density)
                        .toInt()

                setPadding(
                    0,
                    verticalPadding,
                    0,
                    verticalPadding
                )
            }

            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val title = TextView(this).apply {
                text = addon.name
                setTextColor(
                    ContextCompat.getColor(
                        this@wirePluginsPane,
                        R.color.text_primary
                    )
                )
                textSize = 16f
            }

            val urlText = TextView(this).apply {
                text = addon.manifestUrl
                setTextColor(
                    ContextCompat.getColor(
                        this@wirePluginsPane,
                        R.color.text_secondary
                    )
                )
                textSize = 12f
                maxLines = 2
            }

            labels.addView(title)
            labels.addView(urlText)

            row.addView(
                labels,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            val enabled = CheckBox(this).apply {
                isChecked = addon.enabled
                contentDescription =
                    "Enable ${addon.name}"

                setOnCheckedChangeListener { _, checked ->
                    StremioAddonStore.setEnabled(
                        prefs,
                        addon.id,
                        checked
                    )
                }
            }

            val remove = Button(this).apply {
                text = "Remove"

                setOnClickListener {
                    AlertDialog.Builder(
                        this@wirePluginsPane
                    )
                        .setTitle(
                            "Remove ${addon.name}?"
                        )
                        .setMessage(
                            "This removes the saved addon URL."
                        )
                        .setPositiveButton("Remove") { _, _ ->
                            StremioAddonStore.remove(
                                prefs,
                                addon.id
                            )
                            renderStremioAddons()
                        }
                        .setNegativeButton(
                            "Cancel",
                            null
                        )
                        .show()
                }
            }

            row.addView(enabled)
            row.addView(remove)
            stremioList.addView(row)
        }
    }

    stremioAddButton.setOnClickListener {
        showAddStremioAddonDialog()
    }

    renderStremioAddons()

    fun addCandidateRow(
        candidateList: LinearLayout,
        plugin: PluginScript,
        candidate: DiscoveredProvider
    ) {
        val row = layoutInflater.inflate(R.layout.item_plugin_candidate_row, candidateList, false)
        val typeLabel = when (candidate.type) {
            "xtream" -> "Xtream"
            "stalker" -> "Stalker Portal"
            else -> "M3U/M3U8"
        }
        row.findViewById<TextView>(R.id.candidateName).text = candidate.label
        row.findViewById<TextView>(R.id.candidateDetail).text =
            listOfNotNull("$typeLabel · ${candidate.url}", candidate.detail).joinToString("\n")
        // The plugin's own claim that it tested this, labelled as such - the host hasn't
        // verified anything at this point.
        row.findViewById<View>(R.id.candidateVerified).visibility =
            if (candidate.verified) View.VISIBLE else View.GONE
        val addButton = row.findViewById<View>(R.id.candidateAddButton)
        val addLabel = row.findViewById<TextView>(R.id.candidateAddLabel)
        // Survives the re-render that follows every discovery progress line - the button is
        // a fresh view each time, but the fact it was already used is not.
        if (candidate.url in pluginDiscoveryAdded) {
            addLabel.text = "Added"
            addButton.isEnabled = false
            addButton.isFocusable = false
        }
        addButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Add ${candidate.label}?")
                .setMessage(
                    "${plugin.label} found this $typeLabel provider:\n\n${candidate.url}\n\n" +
                        "Adding it saves those details as a provider in Lumora."
                )
                .setPositiveButton("Add") { _, _ ->
                    IptvProviderStore.upsert(
                        prefs,
                        IptvProviderConfig(
                            id = IptvProviderStore.newId(),
                            type = candidate.type,
                            name = candidate.label,
                            enabled = true,
                            url = candidate.url,
                            username = candidate.username,
                            password = candidate.password,
                            // Stalker's MAC and M3U's custom UA share this slot everywhere
                            // else in the app (see loadAllConfiguredProviders).
                            userAgent = candidate.userAgent
                        )
                    )
                    pluginDiscoveryAdded.add(candidate.url)
                    addLabel.text = "Added"
                    addButton.isEnabled = false
                    addButton.isFocusable = false
                    // Rebuild the provider list in the same settings screen so the newly
                    // added provider shows up immediately instead of only after reopening.
                    refreshIptvProviderList.invoke()
                    try {
                        loadAllConfiguredProviders(forceRefresh = true)
                    } catch (_: Exception) {
                        // A malformed candidate (blank URL, missing credentials) can crash
                        // the provider load. The upsert already succeeded; don't let the
                        // crash abort the UI navigation that shows the user where it landed.
                    }
                    // The user was on this plugin's page when they tapped Add; the providers
                    // list they actually want to see is in the Providers pane, so jump there
                    // rather than leaving them staring at the now-empty "Added" button.
                    onProviderAdded()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        candidateList.addView(row)
    }

    fun runDiscovery(plugin: PluginScript) {
        pluginDiscoveryJob?.cancel()
        // A run owns the results area, so anything the previous plugin left there goes -
        // two plugins' candidates in one list would be unattributable.
        pluginDiscoveryPluginId = plugin.id
        pluginDiscoveryCandidates.clear()
        pluginDiscoveryAdded.clear()
        pluginDiscoveryStatus = "Starting ${plugin.label}…"
        liveDiscoveryStatusView = null
        liveDiscoveryCandidateList = null
        liveDiscoveryPlugin = null
        // Run is only reachable from the plugin's own page, and that page is where the
        // results render - so it is already open. Redraw it to show the run starting.
        renderPluginDetail()
        pluginDiscoveryJob = scope.launch {
            val source = manager.readSource(plugin)
            val result = jsPluginEngine.runDiscovery(
                source,
                onProgress = { line ->
                    pluginDiscoveryStatus = line
                    liveDiscoveryStatusView?.text = line
                },
                onCandidate = { candidate ->
                    pluginDiscoveryCandidates.add(candidate)
                    // Appended to the live list where one exists; otherwise it's still held
                    // in the list above and the render at the end of the run puts it there.
                    liveDiscoveryCandidateList?.let { list ->
                        addCandidateRow(list, liveDiscoveryPlugin ?: plugin, candidate)
                    }
                }
            )
            val found = pluginDiscoveryCandidates.size
            pluginDiscoveryStatus = when (result) {
                is DiscoveryResult.Finished ->
                    result.message ?: if (found == 0) "Nothing found" else "Found $found"
                is DiscoveryResult.Failed -> result.message
            }
            pluginDiscoveryJob = null
            liveDiscoveryStatusView = null
            liveDiscoveryCandidateList = null
            liveDiscoveryPlugin = null
            // The page shows the run; the list behind it shows its outcome in the summary
            // line, so both are redrawn.
            renderPluginDetail()
            renderPluginList()
        }
    }

    // ── The plugin list, and one plugin's own page ──

    fun openPluginDetail(id: String) {
        openPluginPage(id)
    }

    renderPluginList = {
        scope.launch {
            val plugins = manager.discoverScripts()
            listContainer.removeAllViews()
            listEmpty.visibility = if (plugins.isEmpty()) View.VISIBLE else View.GONE
            for (plugin in plugins) {
                val row = layoutInflater.inflate(R.layout.item_plugin_row, listContainer, false)
                row.findViewById<TextView>(R.id.pluginName).text = plugin.label
                row.findViewById<TextView>(R.id.pluginSummary).text = listOfNotNull(
                    if (plugin.enabled) "Enabled" else "Disabled",
                    pluginDiscoveryStatus.takeIf { plugin.id == pluginDiscoveryPluginId }
                ).joinToString("  ·  ")
                row.setOnClickListener { openPluginDetail(plugin.id) }
                listContainer.addView(row)

                if (plugin.id == pluginFocusRequestId) {
                    pluginFocusRequestId = null
                    pluginFocusRequestViewId = View.NO_ID
                    row.post { row.requestFocus() }
                }
            }
        }
        Unit
    }

    // Wires the dedicated plugin page against whichever plugin is currently open. Rebuilt
    // rather than bound once: enabling, updating and running all change what it should say,
    // and a discovery run rewrites its results as it goes.
    renderPluginDetail = {
        val id = openPluginId
        if (id != null) scope.launch {
            val plugin = manager.discoverScripts().firstOrNull { it.id == id }
            if (plugin == null) {
                // Removed from under us - the list is the only sensible place to land.
                closePluginPage()
            } else {
                val running = pluginDiscoveryJob?.isActive == true
                val isRunningPlugin = plugin.id == pluginDiscoveryPluginId

                detailTitle.text = plugin.label
                detailDescription.text = plugin.description.orEmpty()
                detailDescription.visibility =
                    if (plugin.description.isNullOrBlank()) View.GONE else View.VISIBLE
                detailMeta.text = buildList {
                    if (plugin.supportsDiscovery) add("Provider discovery")
                    if (plugin.supportsStreamSearch) add("Stream search")
                    addAll(plugin.contentTypes)
                }.joinToString("  ·  ").uppercase(Locale.US)

                detailEnabledBox.isChecked = plugin.enabled
                detailEnabledRow.setOnClickListener {
                    manager.setEnabled(plugin.id, !plugin.enabled)
                    pluginFocusRequestViewId = R.id.pluginDetailEnabledRow
                    renderPluginDetail()
                    renderPluginList()
                    refreshPluginNavRows?.invoke()
                    if (plugin.supportsStreamSearch) loadAllConfiguredProviders(forceRefresh = true)
                }

                // Run only applies to discovery plugins; a stream_search plugin is driven
                // from a title's "Find stream" instead.
                if (plugin.supportsDiscovery) {
                    detailRunButton.visibility = View.VISIBLE
                    detailRunLabel.text = if (running && isRunningPlugin) "Running…" else "Run"
                    // Dimmed but still focusable when it can't be used: setEnabled(false)
                    // takes a View out of focus search entirely, and Run is exactly what the
                    // user is heading for after enabling a plugin, so it has to stay on the
                    // path. The click explains itself instead.
                    detailRunButton.alpha = if (plugin.enabled && !running) 1f else 0.4f
                    detailRunButton.setOnClickListener {
                        when {
                            running -> Toast.makeText(
                                this@wirePluginsPane, "A plugin is already running", Toast.LENGTH_SHORT
                            ).show()
                            !plugin.enabled -> Toast.makeText(
                                this@wirePluginsPane, "Enable ${plugin.label} first", Toast.LENGTH_SHORT
                            ).show()
                            else -> runDiscovery(plugin)
                        }
                    }
                } else {
                    detailRunButton.visibility = View.GONE
                    detailRunButton.setOnClickListener(null)
                }

                detailUpdateLabel.text = getString(R.string.update)
                detailUpdateButton.setOnClickListener {
                    detailUpdateLabel.text = "Updating…"
                    scope.launch {
                        val message = updatePluginFromStore(plugin)
                        Toast.makeText(this@wirePluginsPane, message, Toast.LENGTH_LONG).show()
                        pluginFocusRequestViewId = R.id.pluginDetailUpdateButton
                        renderPluginDetail()
                        renderPluginList()
                        refreshPluginNavRows?.invoke()
                    }
                }

                detailRemoveButton.setOnClickListener {
                    AlertDialog.Builder(this@wirePluginsPane)
                        .setTitle("Remove ${plugin.label}?")
                        .setMessage("This deletes the installed script. You can reinstall it later from a plugin store or its URL.")
                        .setPositiveButton("Remove") { _, _ ->
                            manager.setEnabled(plugin.id, false)
                            manager.removeUserScript(plugin.fileName)
                            closePluginPage()
                            renderPluginList()
                            refreshPluginNavRows?.invoke()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }

                // Results are this plugin's own, rebuilt from the state rather than from
                // whatever views survived - this runs again on every interaction, and a run
                // may still be in flight while it does.
                if (isRunningPlugin && pluginDiscoveryStatus != null) {
                    detailResults.visibility = View.VISIBLE
                    detailProgress.visibility = if (running) View.VISIBLE else View.GONE
                    detailStatus.text = pluginDiscoveryStatus
                    detailCandidateList.removeAllViews()
                    for (candidate in pluginDiscoveryCandidates) {
                        addCandidateRow(detailCandidateList, plugin, candidate)
                    }
                    // While a run is live these are what each progress line and candidate is
                    // written into directly - re-rendering the page per line would rebuild
                    // every focusable view under the user.
                    if (running) {
                        liveDiscoveryStatusView = detailStatus
                        liveDiscoveryCandidateList = detailCandidateList
                        liveDiscoveryPlugin = plugin
                    }
                } else {
                    detailResults.visibility = View.GONE
                }

                if (pluginFocusRequestViewId != View.NO_ID) {
                    val target = dialogView.findViewById<View>(pluginFocusRequestViewId)
                    pluginFocusRequestViewId = View.NO_ID
                    target?.post { target.requestFocus() }
                }
            }
        }
        Unit
    }

    detailBack.setOnClickListener { closePluginPage() }
    closeOpenPluginPage = { closePluginPage() }

    // Lets the nav rail's plugin rows open a plugin's page - see wirePluginNavRows.
    revealPluginInPane = { id -> openPluginDetail(id) }
    renderPluginList()
}

/**
 * Re-installs [plugin] from whichever configured store lists its id, and reports what
 * happened as a message for the caller to show.
 *
 * Matched on the manifest id rather than the file name: a store is free to rename its file,
 * and the id is what [PluginScriptManager.installScript] overwrites on, so those two have to
 * agree or an "update" would install a second copy alongside the old one.
 */
internal suspend fun MainActivity.updatePluginFromStore(plugin: PluginScript): String {
    val stores = pluginStoreManager.storeUrls()
    for (store in stores) {
        val catalog = pluginStoreManager.fetchCatalog(store.url).getOrNull() ?: continue
        val entry = catalog.firstOrNull { it.id == plugin.id } ?: continue
        val text = pluginStoreManager.fetchScriptText(entry.fileUrl)
            ?: return "Couldn't download ${plugin.label}"
        // installScript() preserves the stored enabled state, so an update can't switch a
        // plugin the user had turned off back on.
        return when (val result = pluginScriptManager.installScript(text)) {
            is PluginScriptManager.InstallResult.Installed -> "Updated ${result.script.label}"
            is PluginScriptManager.InstallResult.Rejected -> "Update rejected: ${result.reason}"
        }
    }
    return "${plugin.label} isn't in any configured plugin store"
}

/**
 * Makes the nav rail's Plugins row a dropdown over the installed plugins. Each child opens
 * the Plugins pane with that plugin's section already expanded and focused, which is where
 * it can be updated or enabled/disabled - the rail itself is navigation, so a child row only
 * reports the enabled state rather than being another place that changes it.
 *
 * This is the reason a discovery plugin is reachable at all on a long list: the Reddit
 * scanner sits near the bottom of the installed plugins, which is several screens down a
 * pane that also holds the install-from-URL card and the store list above it.
 */
internal fun MainActivity.wirePluginNavRows(dialogView: View, openPluginsPane: () -> Unit) {
    val parentRow = dialogView.findViewById<View>(R.id.navPlugins)
    val caret = dialogView.findViewById<TextView>(R.id.navPluginsCaret)
    val children = dialogView.findViewById<LinearLayout>(R.id.navPluginChildren)

    fun render() {
        scope.launch {
            val plugins = pluginScriptManager.discoverScripts()
            children.removeAllViews()
            for (plugin in plugins) {
                val row = layoutInflater.inflate(R.layout.item_plugin_nav_row, children, false)
                row.findViewById<TextView>(R.id.pluginNavLabel).text = plugin.label
                row.findViewById<TextView>(R.id.pluginNavState).text =
                    if (plugin.enabled) "✓" else "○"
                row.setOnClickListener {
                    openPluginsPane()
                    revealPluginInPane?.invoke(plugin.id)
                }
                children.addView(row)
            }
            val hasPlugins = plugins.isNotEmpty()
            children.visibility = if (navPluginsExpanded && hasPlugins) View.VISIBLE else View.GONE
            caret.visibility = if (hasPlugins) View.VISIBLE else View.GONE
            caret.text = if (navPluginsExpanded) "▾" else "▸"
        }
        Unit
    }
    refreshPluginNavRows = { render() }

    // Selecting the parent does both jobs: it opens the pane (what every other rail row
    // does, so the row doesn't behave differently from its neighbours) and expands the list.
    parentRow.setOnClickListener {
        openPluginsPane()
        navPluginsExpanded = !navPluginsExpanded
        render()
    }
    render()
}

/**
 * Settings > Plugins > Plugin Stores. A store is a small JSON catalog listing scripts a user
 * can install with one tap - see [PluginStoreManager]'s kdoc for the schema. The default
 * store (Lumora's own plugin repo) is always present; users can add more (a community repo,
 * their own fork, ...) and remove any they added. [onInstalled] refreshes the plain
 * installed-plugin list above once something new lands.
 */
internal fun MainActivity.wirePluginStoresSection(dialogView: View, manager: PluginScriptManager, onInstalled: () -> Unit) {
    val listContainer = dialogView.findViewById<LinearLayout>(R.id.settingsPluginStoreList)
    val listEmpty = dialogView.findViewById<View>(R.id.settingsPluginStoreListEmpty)

    fun installFromStore(storeScript: StoreScript, onDone: (PluginScriptManager.InstallResult) -> Unit) {
        scope.launch {
            val text = pluginStoreManager.fetchScriptText(storeScript.fileUrl)
            if (text.isNullOrBlank()) {
                onDone(PluginScriptManager.InstallResult.Rejected("Couldn't download that script"))
                return@launch
            }
            // No enabled-state juggling here: installScript() leaves it alone, so an update
            // keeps whatever the user had chosen and a first install lands switched off.
            onDone(manager.installScript(text))
        }
    }

    fun showBrowseStoreDialog(store: PluginStore) {
        val status = TextView(this).apply {
            text = "Loading…"
            setTextColor(ContextCompat.getColor(this@wirePluginStoresSection, R.color.text_secondary))
        }
        val resultsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(status)
            addView(resultsHost)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(store.name ?: store.url)
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Close", null)
            .create()
        dialog.show()

        scope.launch {
            val installedIds = manager.discoverScripts().map { it.id }.toSet()
            val result = pluginStoreManager.fetchCatalog(store.url)
            val catalog = result.getOrNull()
            if (catalog == null) {
                status.text = "Couldn't load this store"
                return@launch
            }
            if (catalog.isEmpty()) {
                status.text = "No scripts listed"
                return@launch
            }
            status.text = "${catalog.size} script${if (catalog.size == 1) "" else "s"}"
            for (storeScript in catalog) {
                val row = layoutInflater.inflate(R.layout.item_plugin_candidate_row, resultsHost, false)
                row.findViewById<TextView>(R.id.candidateName).text = storeScript.label
                row.findViewById<TextView>(R.id.candidateDetail).text = listOfNotNull(
                    storeScript.capabilities.joinToString(", ").takeIf { it.isNotBlank() },
                    storeScript.description
                ).joinToString("\n")
                row.findViewById<View>(R.id.candidateVerified).visibility = View.GONE
                val installButton = row.findViewById<View>(R.id.candidateAddButton)
                val installLabel = row.findViewById<TextView>(R.id.candidateAddLabel)
                // Already installed doesn't mean "nothing to do" - re-installing overwrites
                // in place (see PluginScriptManager.installScript), which is exactly how you
                // pick up a store update. Stays clickable either way, just relabeled.
                val alreadyInstalled = storeScript.id in installedIds
                val idleLabel = if (alreadyInstalled) "Update" else "Install"
                installLabel.text = idleLabel
                installButton.setOnClickListener {
                    installButton.isEnabled = false
                    installLabel.text = if (alreadyInstalled) "Updating…" else "Installing…"
                    installFromStore(storeScript) { outcome ->
                        when (outcome) {
                            is PluginScriptManager.InstallResult.Installed -> {
                                installLabel.text = if (alreadyInstalled) "Updated" else "Installed"
                                installButton.isEnabled = true
                                // Same rule as the add-from-URL path: installing puts the
                                // script on the device but does not switch it on. Enabling
                                // is a separate, visible act on the plugin's own page - a
                                // stream_search plugin that is on starts answering Find
                                // Stream and pulls its catalogue into the Series tab, so a
                                // store Install tap must not silently do that.
                                Toast.makeText(
                                    this@wirePluginStoresSection,
                                    if (outcome.script.enabled) "${storeScript.label} installed"
                                    else "${storeScript.label} installed - enable it to use it",
                                    Toast.LENGTH_LONG
                                ).show()
                                onInstalled()
                            }
                            is PluginScriptManager.InstallResult.Rejected -> {
                                installLabel.text = idleLabel
                                installButton.isEnabled = true
                                Toast.makeText(this@wirePluginStoresSection, outcome.reason, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                resultsHost.addView(row)
            }
        }
    }

    lateinit var renderStoreList: () -> Unit

    fun showAddStoreDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/plugins/index.json"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Add plugin store")
            .setMessage("Enter the link to a plugin store's catalog (a small JSON file listing its scripts).")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                val scheme = url.substringBefore("://", "").lowercase(Locale.US)
                if (url.isBlank() || (scheme != "http" && scheme != "https")) {
                    Toast.makeText(this, "Enter a valid http(s) link", Toast.LENGTH_SHORT).show()
                } else {
                    pluginStoreManager.addStore(url)
                    renderStoreList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    renderStoreList = {
        listContainer.removeAllViews()
        val stores = pluginStoreManager.storeUrls()
        listEmpty.visibility = if (stores.isEmpty()) View.VISIBLE else View.GONE
        for (store in stores) {
            val row = layoutInflater.inflate(R.layout.item_plugin_store_row, listContainer, false)
            row.findViewById<TextView>(R.id.storeName).text = store.name ?: store.url
            row.findViewById<TextView>(R.id.storeUrl).text = store.url
            row.findViewById<View>(R.id.storeBrowseButton).setOnClickListener { showBrowseStoreDialog(store) }
            val removeButton = row.findViewById<View>(R.id.storeRemoveButton)
            if (store.removable) {
                removeButton.visibility = View.VISIBLE
                removeButton.setOnClickListener {
                    pluginStoreManager.removeStore(store.url)
                    renderStoreList()
                }
            } else {
                removeButton.visibility = View.GONE
            }
            listContainer.addView(row)
            // Fetch the store's self-declared name in the background and fill it in once
            // known - showing the URL immediately means the row isn't empty while loading.
            if (store.name == null) {
                scope.launch {
                    pluginStoreManager.fetchStoreName(store.url)?.let { name ->
                        row.findViewById<TextView>(R.id.storeName).text = name
                    }
                }
            }
        }
    }
    dialogView.findViewById<View>(R.id.settingsPluginAddStore)?.setOnClickListener { showAddStoreDialog() }
    renderStoreList()
}

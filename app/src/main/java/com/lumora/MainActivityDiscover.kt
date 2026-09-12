package com.lumora

import android.app.AlertDialog
import androidx.core.content.ContextCompat
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.lumora.cache.FavoritesStore
import com.lumora.cache.PlaybackPositionStore
import com.lumora.cache.RecentlyPlayedStore
import com.lumora.model.Channel
import com.lumora.model.ContentShelf
import com.lumora.model.MediaType
import com.lumora.plugin.js.PluginScript
import com.lumora.data.remote.stremio.StremioAddonStore
import com.lumora.parser.XtreamClient
import com.lumora.util.isAdultCategory
import kotlinx.coroutines.*
import java.util.Locale

// ── Discover (TMDB browse) & Home shelves ──
//
// Extracted from MainActivity.kt; see that file's header.
internal fun MainActivity.setupDiscover() {
    setGridSpan(binding.discoverGrid, discoverGridAdapter, R.id.tabDiscover)
    binding.discoverGrid.adapter = discoverGridAdapter

    // Explicit pagination replaces endless scrolling.
    binding.discoverPrevPage.setOnClickListener {
        if (
            discoverCurrentQuery == null &&
            !discoverLoadingMore &&
            discoverPage > 1
        ) {
            loadDiscoverPage(discoverPage - 1)
        }
    }

    binding.discoverNextPage.setOnClickListener {
        if (
            discoverCurrentQuery == null &&
            !discoverLoadingMore &&
            discoverHasMore &&
            discoverPage < 10
        ) {
            loadDiscoverPage(discoverPage + 1)
        }
    }
}

/** Discover is its own pane (like Downloads): browse/search TMDB, no category sidebar. */
internal fun MainActivity.selectDiscover() {
    hideCatchup()
    activeSettingsOverlay?.dismiss()
    activeSearchOverlay?.dismiss()
    showingHome = false
    showingDownloads = false
    showingFavorites = false
    showingDiscover = true

    // Favorites and Discover deliberately share this RecyclerView.
    // Restore Discover's adapter whenever its tab is entered.
    if (binding.discoverGrid.adapter !== discoverGridAdapter) {
        binding.discoverGrid.adapter = discoverGridAdapter
    }
    binding.discoverPrevPage.visibility = View.VISIBLE
    binding.discoverNextPage.visibility = View.VISIBLE
    releaseLivePreview()
    binding.contentRow.visibility = View.GONE
    binding.homeDashboard.visibility = View.GONE
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    binding.concertContent.visibility = View.GONE
    binding.discoverContent.visibility = View.VISIBLE
    updateTabStyles(binding.tabDiscover)
    // Recompute span now the pane is on-screen and actually has a width.
    binding.discoverGrid.post { setGridSpan(binding.discoverGrid, discoverGridAdapter, R.id.tabDiscover) }
    if (!tmdbClient.hasKey()) {
        setDiscoverStatus("Discover is unavailable (no TMDB key configured).")
    } else if (discoverGridAdapter.itemCount == 0) {
        loadDiscover(null)
    }
    applyStatus()
}


/**
 * One Favorites destination for everything the user has starred.
 *
 * The existing stores remain authoritative:
 * - FavoritesStore.favorite_channel_ids = Live TV
 * - FavoritesStore.favorite_series_ids = provider Movies + Series
 * - DiscoverFavoritesStore = TMDB / Discover items
 *
 * Nothing is copied into a new database.
 */
internal fun MainActivity.favoriteItemsForTab(): List<Channel> {
    val liveIds =
        com.lumora.cache.FavoritesStore.getFavoriteChannelIds(this)

    val vodIds =
        com.lumora.cache.FavoritesStore.getFavoriteSeriesIds(this)

    val liveFavorites =
        liveChannels
            .filter { it.id in liveIds }
            .filterNot(::isAdultHomeItem)

    val providerFavorites =
        (seriesList + filmList)
            .filter { it.id in vodIds }
            .filterNot(::isAdultHomeItem)

    val discoverFavorites =
        com.lumora.cache.DiscoverFavoritesStore
            .getAll(this)
            .filterNot(::isAdultHomeItem)

    return (
        liveFavorites +
            discoverFavorites +
            providerFavorites
        )
        .distinctBy { item ->
            item.id.ifBlank { item.url }
        }
}

/**
 * Rebuild the visible Favorites page directly from the authoritative stores.
 * This makes add/remove operations appear immediately without reloading
 * providers or rebuilding the whole catalog.
 */
internal fun MainActivity.refreshFavoritesTab() {
    if (!showingFavorites) return

    val items = favoriteItemsForTab()

    if (binding.discoverGrid.adapter !== favoritesGridAdapter) {
        binding.discoverGrid.adapter = favoritesGridAdapter
    }

    favoritesGridAdapter.submitList(items)

    if (items.isEmpty()) {
        setDiscoverStatus("No favorites yet. Star something and it will show up here.")
    } else {
        setDiscoverStatus("")
    }

    binding.discoverGrid.post {
        setGridSpan(
            binding.discoverGrid,
            favoritesGridAdapter,
            R.id.tabFavorites
        )

        if (items.isNotEmpty()) {
            binding.discoverGrid.scrollToPosition(0)
        }
    }
}

/**
 * Favorites is a standalone destination like Discover / Downloads.
 * It deliberately does not consume activeTab = 3 because activeTab's
 * 0/1/2 contract is deeply shared by Live / Series / Movies.
 */
internal fun MainActivity.selectFavorites() {
    hideCatchup()
    activeSettingsOverlay?.dismiss()
    activeSearchOverlay?.dismiss()

    showingHome = false
    showingDownloads = false
    showingDiscover = false
    showingFavorites = true

    releaseLivePreview()

    binding.contentRow.visibility = View.GONE
    binding.homeDashboard.visibility = View.GONE
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    binding.concertContent.visibility = View.GONE

    // Favorites reuses Discover's poster canvas.
    binding.discoverContent.visibility = View.VISIBLE
    binding.discoverPrevPage.visibility = View.GONE
    binding.discoverNextPage.visibility = View.GONE

    if (binding.discoverGrid.adapter !== favoritesGridAdapter) {
        binding.discoverGrid.adapter = favoritesGridAdapter
    }

    updateTabStyles(binding.tabFavorites)
    refreshFavoritesTab()
    applyStatus()
}

internal fun MainActivity.loadTmdbVodCatalog() {
    if (!tmdbClient.hasKey()) return

    scope.launch {
        val loaded = coroutineScope {
            val trendingMovies =
                async { tmdbClient.trendingMovies() }
            val popularMovies =
                async { tmdbClient.popularMovies(3) }
            val nowPlayingMovies =
                async { tmdbClient.nowPlayingMovies() }
            val topRatedMovies =
                async { tmdbClient.topRatedMovies() }
            val upcomingMovies =
                async { tmdbClient.upcomingMovies() }

            val trendingSeries =
                async { tmdbClient.trendingSeries() }
            val popularSeries =
                async { tmdbClient.popularSeries(3) }
            val onTheAirSeries =
                async { tmdbClient.onTheAirSeries() }
            val airingTodaySeries =
                async { tmdbClient.airingTodaySeries() }
            val topRatedSeries =
                async { tmdbClient.topRatedSeries() }

            fun uniqueShelves(
                entries: List<Pair<String, List<Channel>>>
            ): List<ContentShelf> {
                val used = mutableSetOf<String>()

                return entries.mapNotNull { (title, items) ->
                    val unique = items.filter { item ->
                        val key = item.id.ifBlank { item.url }

                        if (key.isBlank() || key in used) {
                            false
                        } else {
                            used += key
                            true
                        }
                    }

                    unique
                        .takeIf { it.isNotEmpty() }
                        ?.let { ContentShelf(title, it) }
                }
            }

            val movieShelves = uniqueShelves(
                listOf(
                    "Trending Movies" to trendingMovies.await(),
                    "Popular Movies" to popularMovies.await(),
                    "Now Playing" to nowPlayingMovies.await(),
                    "Top Rated Movies" to topRatedMovies.await(),
                    "Upcoming" to upcomingMovies.await()
                )
            )

            val seriesShelves = uniqueShelves(
                listOf(
                    "Trending Series" to trendingSeries.await(),
                    "Popular Series" to popularSeries.await(),
                    "On The Air" to onTheAirSeries.await(),
                    "Airing Today" to airingTodaySeries.await(),
                    "Top Rated Series" to topRatedSeries.await()
                )
            )

            movieShelves to seriesShelves
        }

        tmdbMovieShelves = loaded.first
        tmdbSeriesShelves = loaded.second

        tmdbVodCatalog =
            (tmdbMovieShelves.flatMap { it.items } +
                tmdbSeriesShelves.flatMap { it.items })
                .distinctBy { it.id }

        // Refresh the current TMDB tab as soon as network loading finishes.
        when {
            activeTab == 1 &&
                !showingHome &&
                !showingFavorites &&
                !showingDiscover -> showTmdbSeriesTab()

            activeTab == 2 &&
                !showingHome &&
                !showingFavorites &&
                !showingDiscover -> showTmdbMoviesTab()
        }
    }
}

internal fun MainActivity.hasAnyStreamSource(): Boolean =
    enabledStreamSearchPlugin() != null ||
        StremioAddonStore.load(prefs).any { it.enabled }

/** Loads trending (null query) or search results into the Discover grid. */
internal fun MainActivity.loadDiscover(query: String?) {
    if (!tmdbClient.hasKey()) return

    discoverSearchJob?.cancel()

    discoverCurrentQuery = query
    discoverPage = 1
    discoverLoadingMore = false
    discoverHasMore = query == null

    setDiscoverStatus(
        if (query == null) {
            "Loading Discover…"
        } else {
            "Searching \"$query\"…"
        }
    )

    discoverSearchJob = scope.launch {
        val results =
            if (query == null) {
                tmdbClient.discoverPage(1)
            } else {
                tmdbClient.search(query)
            }

        val pluginEnabled = hasAnyStreamSource()

        val visible =
            if (pluginEnabled) {
                results
            } else {
                withContext(Dispatchers.Default) {
                    results.filter {
                        findCatalogMatch(it) != null
                    }
                }
            }

        discoverGridAdapter.replaceAll(visible)

        // Search results do not use pagination. Normal Discover starts
        // explicitly on page 1.
        if (query == null) {
            discoverPage = 1
            discoverHasMore = results.size >= 20
        } else {
            discoverHasMore = false
        }

        updateDiscoverPagination()

        setDiscoverStatus(
            when {
                visible.isNotEmpty() -> null

                results.isEmpty() ->
                    if (query == null) {
                        "Couldn't load titles. Check your connection."
                    } else {
                        "No results for \"$query\"."
                    }

                else ->
                    "Enable a stream plugin to browse titles outside your library."
            }
        )
    }
}

internal fun MainActivity.updateDiscoverPagination() {
    val visible = discoverCurrentQuery == null

    binding.discoverPagination.visibility =
        if (visible) View.VISIBLE else View.GONE

    if (!visible) return

    binding.discoverPageLabel.text = "Page $discoverPage of 10"

    val canGoBack = discoverPage > 1 && !discoverLoadingMore
    val canGoForward =
        discoverHasMore &&
        discoverPage < 10 &&
        !discoverLoadingMore

    binding.discoverPrevPage.isEnabled = canGoBack
    binding.discoverPrevPage.alpha =
        if (canGoBack) 1f else 0.35f

    binding.discoverNextPage.isEnabled = canGoForward
    binding.discoverNextPage.alpha =
        if (canGoForward) 1f else 0.35f
}

internal fun MainActivity.loadDiscoverPage(page: Int) {
    if (
        discoverLoadingMore ||
        discoverCurrentQuery != null ||
        page < 1 ||
        page > 10
    ) {
        return
    }

    discoverLoadingMore = true
    updateDiscoverPagination()
    setDiscoverStatus("Loading page $page…")

    scope.launch {
        try {
            val results = tmdbClient.discoverPage(page)

            val pluginEnabled = hasAnyStreamSource()

            val visible =
                if (pluginEnabled) {
                    results
                } else {
                    withContext(Dispatchers.Default) {
                        results.filter {
                            findCatalogMatch(it) != null
                        }
                    }
                }

            discoverGridAdapter.replaceAll(visible)

            discoverPage = page
            discoverHasMore =
                results.size >= 20 && page < 10

            binding.discoverGrid.scrollToPosition(0)

            setDiscoverStatus(
                when {
                    visible.isNotEmpty() -> null

                    results.isEmpty() ->
                        "No more titles on page $page."

                    else ->
                        "Enable a stream plugin to browse titles outside your library."
                }
            )
        } finally {
            discoverLoadingMore = false
            updateDiscoverPagination()
        }
    }
}

internal fun MainActivity.setDiscoverStatus(text: String?) {
    binding.discoverStatus.text = text ?: ""
    binding.discoverStatus.visibility = if (text == null) View.GONE else View.VISIBLE
}

/** Discover pick opens an info screen: overview + poster, then either play a matching catalog
 *  item (if this title is already served by a provider) or find a torrent stream for it. */
internal fun MainActivity.onDiscoverItemClick(item: Channel) {
    val match = findCatalogMatch(item)

    val density = resources.displayMetrics.density
    val pad = (20 * density).toInt()
    val backdrop = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (200 * density).toInt()
        )
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    loadDetailImage(item.backdropUrl ?: item.posterUrl, backdrop)

    val meta = listOfNotNull(
        if (item.mediaType == MediaType.SERIES) "Series" else "Movie",
        item.year,
        item.rating?.let { "★ $it" }
    ).joinToString("   ·   ")

    fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@onDiscoverItemClick, R.color.text_secondary))
        setPadding(0, (6 * density).toInt(), 0, 0)
    }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
        addView(TextView(this@onDiscoverItemClick).apply {
            text = item.name
            setTextColor(ContextCompat.getColor(this@onDiscoverItemClick, R.color.text_primary))
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        addView(label(meta))
        item.description?.let { addView(label(it)) }
        match?.let {
            addView(label("✓ Available in your ${if (it.isJellyfin) "Jellyfin" else "provider"} library"))
        }
    }
    val buttonRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.END
        setPadding(pad, (12 * density).toInt(), pad, pad)
    }
    fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (8 * density).toInt() }
        setOnClickListener { onClick() }
    }

    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(backdrop)
        addView(content)
        addView(buttonRow)
    }
    val scroll = ScrollView(this).apply { addView(body) }

    val dialog = AlertDialog.Builder(this).setView(scroll).create()
    // Prefer the already-owned copy; the torrent path is offered too, but only when a
    // stream-search plugin is actually enabled to serve it.
    if (match != null) {
        buttonRow.addView(actionButton("Play") {
            dialog.dismiss()
            showContentDetail(match)
        })
    }
    if (hasAnyStreamSource()) {
        buttonRow.addView(actionButton("Find stream") {
            dialog.dismiss()
            startDiscoverStreamSearch(item)
        })
    }
    if (tmdbClient.hasKey()) {
        buttonRow.addView(actionButton("Trailer") {
            dialog.dismiss()
            showTrailerForDiscoverItem(item)
        })
    }
    buttonRow.addView(actionButton("Close") { dialog.dismiss() })
    dialog.show()
}

/** Kicks off a stream-search plugin for a Discover title (episode picker for series). */
internal fun MainActivity.startDiscoverStreamSearch(item: Channel) {
    val plugin = enabledStreamSearchPlugin(item)
    val hasStremio = StremioAddonStore.load(prefs).any { it.enabled }

    if (plugin == null && !hasStremio) {
        Toast.makeText(
            this,
            "Enable a stream plugin or Stremio addon in Settings → Plugins.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    if (item.mediaType == MediaType.SERIES) {
        showSeriesEpisodePicker(plugin, item)
    } else {
        // Discover movie playback is intent to PLAY.
        // Let the ranked source engine choose the best candidate automatically.
        showStreamSearchDialog(
            plugin,
            item
        )
    }
}

/** Finds an already-configured provider item matching a Discover (TMDB) title, if any. */
internal fun MainActivity.findCatalogMatch(item: Channel): Channel? {
    val target = normalizeMatchTitle(item.name)
    if (target.isBlank()) return null
    return allChannels.firstOrNull { c ->
        c.mediaType == item.mediaType && run {
            val name = normalizeMatchTitle(c.name)
            (name == target || name.startsWith("$target ") || name.contains(target)) &&
                (item.year == null || c.year == null || c.year == item.year)
        }
    }
}

internal fun MainActivity.normalizeMatchTitle(title: String): String =
    title.lowercase(Locale.US).replace(Regex("\\(\\d{4}\\)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

/** Fetches the show's seasons from TMDB, then lets the user pick season → episode to search. */
internal fun MainActivity.showSeriesEpisodePicker(plugin: PluginScript?, item: Channel) {
    val tvId = item.id.substringAfterLast(':').toIntOrNull()
    if (tvId == null) { showStreamSearchDialog(plugin, item); return }
    val loading = AlertDialog.Builder(this)
        .setTitle(item.name)
        .setMessage("Loading episodes…")
        .setNegativeButton("Cancel", null)
        .create()
    loading.show()
    scope.launch {
        val seasons = tmdbClient.tvSeasons(tvId)
        loading.dismiss()
        if (seasons.isEmpty()) {
            // No season data - fall back to searching the title as a whole.
            showStreamSearchDialog(plugin, item)
            return@launch
        }
        val seasonLabels = seasons.map { "${it.name} (${it.episodeCount} eps)" }.toTypedArray()
        AlertDialog.Builder(this@showSeriesEpisodePicker)
            .setTitle("${item.name} — choose a season")
            .setItems(seasonLabels) { _, si ->
                val season = seasons[si]

                val episodeLoading = AlertDialog.Builder(
                    this@showSeriesEpisodePicker
                )
                    .setTitle(season.name)
                    .setMessage("Loading episodes…")
                    .setNegativeButton("Cancel", null)
                    .create()

                episodeLoading.show()

                scope.launch {
                    val episodes = tmdbClient.tvEpisodes(
                        tvId,
                        season.number
                    )

                    episodeLoading.dismiss()

                    // Rich TV episode picker.
                    //
                    // TMDB already gives us episode number, title, air date and overview.
                    // The old setItems() picker threw most of that away and forced the
                    // viewer to choose episodes nearly blind. Keep the exact same
                    // playback/search path, but make the selection screen useful.
                    val density = resources.displayMetrics.density
                    val pad = (16 * density).toInt()
                    val rowPad = (12 * density).toInt()

                    val episodeContainer = LinearLayout(
                        this@showSeriesEpisodePicker
                    ).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(pad, pad / 2, pad, pad / 2)
                    }

                    val episodeScroll = ScrollView(
                        this@showSeriesEpisodePicker
                    ).apply {
                        isFillViewport = true
                        addView(episodeContainer)
                    }

                    val episodeDialog = AlertDialog.Builder(
                        this@showSeriesEpisodePicker
                    )
                        .setTitle(season.name)
                        .setView(episodeScroll)
                        .setNegativeButton("Back") { _, _ ->
                            showSeriesEpisodePicker(plugin, item)
                        }
                        .create()

                    val episodeRows =
                        if (episodes.isNotEmpty()) {
                            episodes.mapIndexed { index, ep ->
                                Triple(
                                    ep.number,
                                    ep.name ?: "Episode ${ep.number}",
                                    ep
                                )
                            }
                        } else {
                            (1..season.episodeCount).map { number ->
                                Triple(
                                    number,
                                    "Episode $number",
                                    null
                                )
                            }
                        }

                    var firstEpisodeRow: View? = null

                    episodeRows.forEachIndexed { index, (episodeNumber, episodeName, ep) ->

                        val row = LinearLayout(
                            this@showSeriesEpisodePicker
                        ).apply {
                            orientation = LinearLayout.VERTICAL
                            isClickable = true
                            isFocusable = true
                            isFocusableInTouchMode = true

                            setPadding(
                                rowPad,
                                rowPad,
                                rowPad,
                                rowPad
                            )

                            background = ContextCompat.getDrawable(
                                this@showSeriesEpisodePicker,
                                R.drawable.bg_select_item
                            )

                            setOnClickListener {
                                episodeDialog.dismiss()

                                showStreamSearchDialog(
                                    plugin,
                                    item,
                                    season = season.number,
                                    episode = episodeNumber,
                                    autoPlayBest = true
                                )
                            }
                        }

                        val title = TextView(
                            this@showSeriesEpisodePicker
                        ).apply {
                            text = buildString {
                                append("E")
                                append(
                                    episodeNumber
                                        .toString()
                                        .padStart(2, '0')
                                )
                                append("  ·  ")
                                append(episodeName)

                                ep?.airDate
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        append("  ·  ")
                                        append(it)
                                    }
                            }

                            textSize = 17f

                            setTextColor(
                                ContextCompat.getColor(
                                    this@showSeriesEpisodePicker,
                                    R.color.text_primary
                                )
                            )
                        }

                        row.addView(title)

                        ep?.overview
                            ?.takeIf { it.isNotBlank() }
                            ?.let { overview ->
                                val description = TextView(
                                    this@showSeriesEpisodePicker
                                ).apply {
                                    text = overview
                                    textSize = 14f
                                    maxLines = 3
                                    ellipsize =
                                        android.text.TextUtils.TruncateAt.END

                                    setTextColor(
                                        ContextCompat.getColor(
                                            this@showSeriesEpisodePicker,
                                            R.color.text_secondary
                                        )
                                    )

                                    setPadding(
                                        0,
                                        (6 * density).toInt(),
                                        0,
                                        0
                                    )
                                }

                                row.addView(description)
                            }

                        episodeContainer.addView(
                            row,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin =
                                    (8 * density).toInt()
                            }
                        )

                        if (index == 0) {
                            firstEpisodeRow = row
                        }
                    }

                    episodeDialog.setOnShowListener {
                        firstEpisodeRow?.post {
                            firstEpisodeRow?.requestFocus()
                            episodeScroll.scrollTo(0, 0)
                        }
                    }

                    episodeDialog.show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/**
 * Refreshes a persisted Continue Watching episode from its current provider when possible.
 * Old snapshots may be missing provider playback fields, while plugin/Jellyfin URLs may
 * simply have expired. If reconstruction fails, keep the snapshot and let showPlayerFor()
 * use its normal provider/plugin/Jellyfin fallbacks.
 */
internal suspend fun MainActivity.refreshHomeEpisodeSnapshot(channel: Channel): Channel {
    if (channel.mediaType != MediaType.SERIES || channel.episodeNum == null) return channel

    val series = resolveHomeTileSeries(channel) ?: return channel

    return runCatching {
        val (_, seasons) = loadSeriesContent(series)
        val candidates = seasons.flatMap { it.second }

        candidates.firstOrNull { fresh ->
            fresh.id.isNotBlank() && fresh.id == channel.id
        } ?: candidates.firstOrNull { fresh ->
            fresh.episodeNum == channel.episodeNum &&
                fresh.sourceProviderId == channel.sourceProviderId
        } ?: channel
    }.getOrDefault(channel)
}

internal fun MainActivity.onHomeItemClick(channel: Channel) {
    // User-initiated play - see playItem for why the suppression flag is cleared here.
    skipResumePrompt = false
    when (channel.mediaType) {
        MediaType.LIVE -> playItem(channel)
        MediaType.MOVIE -> {
            currentIndex = filmList.indexOf(channel)

            if (channel.streamSearchItemId != null) {
                scope.launch {
                    val playable = refreshSavedStreamSearch(channel)
                    if (playable != null) {
                        showPlayerFor(
                        playable,
                        pluginStreamAlreadyResolved = true
                    )
                    } else {
                        Toast.makeText(
                            this@onHomeItemClick,
                            "Couldn't refresh this stream. Use Find Stream again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                showPlayerFor(channel)
            }

            // Back out to the film's own poster, same as playing it from its detail page.
            // Not for plugin/stream-search resolved entries: their ids are playback identities,
            // not stable catalog items.
            if (channel.pluginToken == null && channel.streamSearchItemId == null) {
                detailReturnItem = channel
            }
        }
        MediaType.SERIES -> {
            // An up-next tile (synthesized for a series whose watched trail is complete)
            // plays the next episode directly, queue included - one click continues the
            // show. Identified by its episode id being registered in upNextQueues.
            val upNextQueue = upNextQueues[channel.id.ifBlank { channel.url }]
            if (upNextQueue != null) {
                val index = upNextQueue.indexOfFirst {
                    it.id.ifBlank { it.url } == channel.id.ifBlank { channel.url }
                }
                showPlayerFor(channel)
                currentEpisodeQueue = upNextQueue
                currentEpisodeQueueIndex = if (index >= 0) index else 0
                return
            }
            // An episode tile (Continue Watching) carries an episode number; clicking it
            // should land on the series' detail page - the season chip lands on the
            // episode's season and the Play button already points at the next-unwatched
            // episode - rather than resuming the episode directly. A top-level series
            // entry (Favorites, category grids) has no episode number and goes to the
            // detail page as normal. url is NOT a reliable discriminator - catalog
            // series items can carry one. If the episode's series can't be resolved,
            // fall back to resuming the episode directly.
            if (channel.episodeNum != null) {
                scope.launch {
                    // Find Stream / Stremio results need a completely fresh search because
                    // their CDN URLs can expire. IPTV/Jellyfin episodes instead rebuild from
                    // provider metadata as before.
                    val playable =
                        if (
                            channel.streamSearchItemId != null ||
                            channel.id.startsWith("stream:")
                        ) {
                            refreshSavedStreamSearch(channel)
                        } else {
                            refreshHomeEpisodeSnapshot(channel)
                        }

                    if (playable != null) {
                        showPlayerFor(
                        playable,
                        pluginStreamAlreadyResolved = true
                    )

                        // IPTV episode snapshots can rebuild an auto-advance queue.
                        if (playable.streamSearchItemId == null) {
                            populateHomeTileEpisodeQueue(playable)
                        }
                    } else {
                        Toast.makeText(
                            this@onHomeItemClick,
                            "Couldn't refresh this episode. Use Find Stream again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                showContentDetail(channel)
            }
        }
        else -> {}
    }
}

/** Resolves the series a Home-tile episode belongs to: exact categoryId (the series id
 *  Xtream parseEpisode and Jellyfin toChannel both stamp on episodes) match through the
 *  catalog first, then the "{series} · {episode}" name-prefix fallback for snapshots that
 *  predate categoryId. Null if unresolvable - callers fall back to direct play. */
internal fun MainActivity.resolveHomeTileSeries(channel: Channel): Channel? {
    // Exact series-id match. Ids are provider-scoped (Xtream series id, Jellyfin item
    // id), so cross-matching is impossible - isJellyfin is the only guard needed, with
    // sourceProviderId compared only when the snapshot carries one (older saves don't).
    channel.categoryId?.takeIf { it.isNotBlank() }?.let { id ->
        allChannels.firstOrNull {
            it.mediaType == MediaType.SERIES && it.id == id && it.isJellyfin == channel.isJellyfin &&
                (channel.sourceProviderId == null || it.sourceProviderId == channel.sourceProviderId)
        }?.let { return it }
    }
    // Name-prefix fallback for old snapshots: "Series Name · S01E02 · Title", longest
    // name wins. Same-provider guard only when the snapshot knows its provider.
    return allChannels
        .filter {
            it.mediaType == MediaType.SERIES && it.isJellyfin == channel.isJellyfin &&
                (channel.sourceProviderId == null || it.sourceProviderId == channel.sourceProviderId)
        }
        .filter { it.name.isNotBlank() && channel.name.startsWith(it.name + " · ") }
        .maxByOrNull { it.name.length }
}

/** A Home tile can be one episode standing alone (Continue Watching, Jellyfin Next Up),
 *  played with no queue - so when it ends nothing auto-advances. Back-fill the series'
 *  full episode chain (all seasons, season-major then episode-major, the order the detail
 *  page plays) and index it from the played episode. Any failure leaves the queue empty,
 *  which is exactly what happened before this existed. */
internal fun MainActivity.populateHomeTileEpisodeQueue(channel: Channel) {
    val playedId = channel.id
    if (playedId.isBlank()) return
    // Jellyfin's chain comes from the server (getEpisodes/getSeasons), not Xtream
    // getSeriesFull - and its tiles now resolve to the series detail page anyway, so
    // this fallback never needs to build a Jellyfin queue.
    if (channel.isJellyfin) return
    scope.launch {
        val ordered = withContext(Dispatchers.IO) {
            val seriesId = channel.categoryId ?: return@withContext emptyList<Channel>()
            val client = XtreamClient(BaseApplication.instance.okHttpClient)
            // Seasons arrive season-major already; sort each season's episodes by
            // episode number, then flatten into the cross-season chain.
            client.getSeriesFull(xtreamProviderFor(channel) ?: provider, seriesId).seasons
                .flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
        }
        // Don't clobber a queue belonging to whatever is playing now if the user moved on
        // while the fetch was in flight.
        if (nowPlayingChannel?.id != playedId) return@launch
        val index = ordered.indexOfFirst { it.id == playedId }
        if (index >= 0) {
            currentEpisodeQueue = ordered
            currentEpisodeQueueIndex = index
        }
    }
}

internal fun MainActivity.getHiddenHomeShelves(): MutableSet<String> =
    prefs.getStringSet("hidden_home_shelves", emptySet())?.toMutableSet() ?: mutableSetOf()

internal fun MainActivity.toggleHiddenHomeShelf(title: String) {
    val hidden = getHiddenHomeShelves()
    if (!hidden.remove(title)) hidden.add(title)
    prefs.edit().putStringSet("hidden_home_shelves", hidden).apply()
    homeShelfAdapter.submitList(buildHomeShelves())
}

/** X on the "Continue Watching" shelf clears the resume data itself, not just hides the
 *  shelf on the tab it was pressed on. Home, Series and Films all read the same store, so
 *  one clear empties the row everywhere. Jellyfin resume lives on the server, so those
 *  entries are dropped there too (best effort) and removed from memory immediately. Also
 *  un-hides the CW shelf so future watching isn't stuck behind a stale hide flag. */
internal fun MainActivity.clearContinueWatching() {
    PlaybackPositionStore.clearAll(this)
    clearUpNextMemo()
    val serverIds = jellyfinResumeItems.map { it.id }.toList()
    jellyfinResumeItems = emptyList()
    val client = jellyfinClient
    if (client != null && serverIds.isNotEmpty()) {
        scope.launch(Dispatchers.IO) {
            serverIds.forEach { id -> runCatching { client.clearUserData(id) } }
        }
    }
    getHiddenHomeShelves().let { if (it.remove("Continue Watching")) prefs.edit().putStringSet("hidden_home_shelves", it).apply() }
    getHiddenCategories(1).let { if (it.remove("Continue Watching")) prefs.edit().putStringSet(hiddenCategoriesPrefsKey(1), it).apply() }
    getHiddenCategories(2).let { if (it.remove("Continue Watching")) prefs.edit().putStringSet(hiddenCategoriesPrefsKey(2), it).apply() }
    homeShelfAdapter.submitList(buildHomeShelves())
    if (!showingHome && !showingFavorites && activeTab != 0) scope.launch { classifyAndShow() }
}

/** Adult content never reaches a Home shelf, regardless of the "Hide adult categories"
 *  setting. That setting governs *browsing* - somebody who unlocks it with the PIN is
 *  choosing to go and look. Continue Watching and Recently Played are different: they
 *  render unprompted on the first screen after launch, in front of whoever happens to
 *  be in the room, so they stay filtered either way.
 *
 *  Three signals, in order of trust: the catalog entry for this id (authoritative, but
 *  only for items still in the catalog - a series episode never is), the category/group
 *  snapshotted at save time, then the title itself as a last resort for entries written
 *  before that snapshot existed. The title check can over-match a legitimate film with
 *  "adult" in its name, which is the right way round to be wrong here. */
internal fun MainActivity.isAdultHomeItem(item: Channel): Boolean {
    val catalog = item.id.takeIf { it.isNotBlank() }?.let { id -> allChannels.firstOrNull { it.id == id } }
    return isAdultCategory(catalog?.categoryName ?: item.categoryName, catalog?.group ?: item.group) ||
        isAdultCategory(item.name)
}

/** First unwatched episode of a series in play order (season-major, then episode
 *  number) - the same ordering the detail page and auto-advance use. Null when the
 *  whole series is watched: a completed series gets no up-next tile. */
internal fun MainActivity.nextEpisodeFor(seasons: List<Pair<String, List<Channel>>>): Channel? {
    val ordered = seasons.flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
    return ordered.firstOrNull { ep ->
        val key = ep.id.ifBlank { ep.url }
        key.isNotBlank() && PlaybackPositionStore.get(this, key)?.isNearComplete != true
    }
}

/** Builds an up-next tile's display name: "Series · S01E05 · Title". Episode titles often
 *  already carry the series name (Xtream bakes it in), so a leading series-name
 *  occurrence and the "SxxEyy · " marker are peeled from the title before the series
 *  prefix is added - otherwise the series reads twice. */
internal fun MainActivity.upNextTileName(seriesName: String, episodeName: String): String {
    val sMark = Regex("""^S\d+E\d+""").find(episodeName)?.value
    val title = episodeName
        .replaceFirst(Regex("^" + Regex.escape(seriesName) + """\s*[·-]\s*"""), "")
        .replaceFirst(Regex("""^S\d+E\d+\s*·\s*"""), "")
        .replaceFirst(Regex("^" + Regex.escape(seriesName) + """\s*-\s*"""), "")
    return listOfNotNull(seriesName, sMark, title.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** Continue Watching extension: a series whose watched trail ends at a completed
 *  episode has nothing in Continue Watching (it only keeps in-progress entries), so its
 *  next episode would be unreachable from Home. Resolve those lazily - return whatever
 *  next-episode tiles are already memoized, and kick an async bounded fetch for the
 *  rest. Cheap when everything's resolved: just a store read + memo lookups. */
internal fun MainActivity.buildUpNextSeriesTiles(): List<Channel> {
    // Home-only feature: other tabs' shelf builds (clear/watch toggle paths) shouldn't
    // kick six network fetches for a row that isn't visible.
    if (!showingHome) return emptyList()
    val trails = PlaybackPositionStore.getCompletedSeriesTrails(this)
    val pending = trails
        .filterNot { it.isJellyfin } // server-side "Next Up" shelf already covers Jellyfin
        .mapNotNull { it.categoryId?.takeIf { id -> id !in upNextTiles && id !in upNextFetching } }
        .take(MAX_UP_NEXT_SERIES)
    if (pending.isNotEmpty()) fetchUpNextSeries(pending)
    // Trail order = most recently completed first; present the memoized tiles in that
    // order (LinkedHashMap insertion order is fetch-completion order, which is arbitrary).
    return trails.mapNotNull { t -> upNextTiles[t.categoryId]?.takeIf { it != null } }
}

/** Fetches the episode lists for up to [MAX_UP_NEXT_SERIES] series (one network call
 *  each, Xtream-only because Jellyfin has its own Next Up), computes each series' next
 *  unwatched episode, and rebuilds the Home shelves once. Results commit atomically only
 *  if the memo epoch hasn't moved (see [clearUpNextMemo]) - a fetch that outlives a
 *  watched-state change must not write pre-change tiles.
 *  Only a *resolved* "no next episode" (fully watched / genuinely empty seasons) is
 *  memoized as no-tile; catalog misses and network failures stay unresolved so the next
 *  Home rebuild retries them. */
internal fun MainActivity.fetchUpNextSeries(seriesIds: List<String>) {
    val epoch = upNextEpoch
    upNextFetching.addAll(seriesIds)
    scope.launch {
        val resolved = HashMap<String, Channel?>()
        val queues = HashMap<String, List<Channel>>()
        for (seriesId in seriesIds) {
            if (epoch != upNextEpoch) break
            val series = allChannels.firstOrNull {
                it.mediaType == MediaType.SERIES && it.id == seriesId
            } ?: continue // not in catalog yet - leave unresolved, retry next build
            val seasons = withContext(Dispatchers.IO) {
                runCatching { loadSeriesContent(series).second }.getOrNull()
            } ?: continue // network failure - leave unresolved, retry next build
            val next = nextEpisodeFor(seasons)
            if (next == null) {
                // Resolved: fully watched (or no playable episodes) - no tile, ever.
                resolved[seriesId] = null
                continue
            }
            val chain = seasons.flatMap { (_, eps) -> eps.sortedBy { it.episodeNum ?: Int.MAX_VALUE } }
            queues[next.id.ifBlank { next.url }] = chain
            // Prefix the series name so a bare "S02E03 · Title" tile reads as the show
            // it belongs to - but peel any series-name occurrence already baked into the
            // episode title first (Xtream titles often read "Clarkson's Farm (2021) -
            // Tractoring"), or the series shows twice.
            resolved[seriesId] = next.copy(name = upNextTileName(series.name, next.name))
        }
        // Commit only if no watched-state change invalidated the memo mid-fetch. No
        // upNextFetching cleanup here: the clear already wiped the set, and removing
        // ids now could yank a *newer* epoch's in-flight claim for the same series.
        if (epoch != upNextEpoch) return@launch
        val foundAny = resolved.values.any { it != null }
        upNextTiles.putAll(resolved)
        upNextQueues.putAll(queues)
        seriesIds.forEach { upNextFetching.remove(it) }
        if (foundAny && showingHome) homeShelfAdapter.submitList(buildHomeShelves())
    }
}

internal fun MainActivity.buildHomeShelves(): List<ContentShelf> {
    val shelves = mutableListOf<ContentShelf>()
    val hidden = getHiddenHomeShelves()

    // ============================================================
    // FAVORITES
    // Home's primary shelf. Merge Live, provider-backed VOD,
    // and Discover favorites into one horizontally scrolling row.
    // ============================================================
    val favChannelIds = FavoritesStore.getFavoriteChannelIds(this)
    val favoriteLive =
        liveChannels
            .filter { it.id in favChannelIds }
            .filterNot(::isAdultHomeItem)

    // Films and Series share the VOD favorites store.
    val favVodIds = FavoritesStore.getFavoriteSeriesIds(this)
    val providerFavorites =
        (seriesList + filmList)
            .filter { it.id in favVodIds }
            .filterNot(::isAdultHomeItem)

    val discoverFavorites =
        com.lumora.cache.DiscoverFavoritesStore
            .getAll(this)
            .filterNot(::isAdultHomeItem)

    val favoriteItems =
        (favoriteLive + discoverFavorites + providerFavorites)
            .distinctBy { it.id.ifBlank { it.url } }

    if (favoriteItems.isNotEmpty()) {
        shelves.add(
            ContentShelf(
                "Favorites",
                favoriteItems
            )
        )
    }

    // ============================================================
    // CONTINUE WATCHING
    // Secondary to Favorites. Jellyfin server state leads local
    // resume state because it can reflect playback on other clients.
    // ============================================================
    val localContinue = PlaybackPositionStore.getAllInProgress(this)
    val serverContinue = jellyfinResumeItems
    val serverIds = serverContinue.map { it.id }.toSet()

    // Series whose previous episode was completed can still surface
    // their next episode here.
    val upNext = buildUpNextSeriesTiles().filterNot(::isAdultHomeItem)

    val continueItems =
        (serverContinue +
            localContinue.filterNot { it.id in serverIds } +
            upNext)
            .distinctBy { it.id.ifBlank { it.url } }
            .filterNot(::isAdultHomeItem)

    if (continueItems.isNotEmpty()) {
        shelves.add(
            ContentShelf(
                "Continue Watching",
                continueItems
            )
        )
    }

    // Jellyfin server-side next episode suggestions.
    val nextUpItems = jellyfinNextUpItems.filterNot(::isAdultHomeItem)
    if (nextUpItems.isNotEmpty()) {
        shelves.add(ContentShelf("Next Up", nextUpItems))
    }

    // Recently played LIVE channels.
    val recentItems =
        RecentlyPlayedStore.getRecentIds(this)
            .mapNotNull { id ->
                liveChannels.firstOrNull { it.id == id }
            }
            .filterNot(::isAdultHomeItem)

    if (recentItems.isNotEmpty()) {
        shelves.add(ContentShelf("Recently Played", recentItems))
    }

    return shelves.filter { it.title !in hidden }
}

/** Series-only Continue Watching for the Series tab - same merge as the Home shelf
 *  (server resume list first, then local in-progress entries minus anything the server
 *  already covered), filtered down to series and adult-dropped. Shared by the Series
 *  sidebar row, its content grid, and the Series poster shelf. */
internal fun MainActivity.seriesContinueItems(): List<Channel> {
    val local = PlaybackPositionStore.getAllInProgress(this).filter { it.mediaType == MediaType.SERIES }
    val server = jellyfinResumeItems.filter { it.mediaType == MediaType.SERIES }
    val serverIds = server.map { it.id }.toSet()
    return (server + local.filterNot { it.id in serverIds }).filterNot(::isAdultHomeItem)
}

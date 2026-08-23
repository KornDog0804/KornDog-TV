import re, shutil, datetime, sys

path = "app/src/main/java/com/lumora/MainActivityPlugins.kt"
ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
backup = f"{path}.before-autoplay-{ts}"
shutil.copy(path, backup)
print(f"Backed up to {backup}")

with open(path, "r", encoding="utf-8") as f:
    src = f.read()

start_m = re.search(r"internal fun MainActivity\.showStreamSearchDialog\(", src)
end_m = re.search(r"//\s*──\s*Plugins\s*──", src)

if not start_m or not end_m or end_m.start() < start_m.start():
    print("FAILED: could not locate showStreamSearchDialog function boundaries. Aborting, no changes written.")
    sys.exit(1)

new_function = r'''internal fun MainActivity.showStreamSearchDialog(
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
                Toast.makeText(
                    this@showStreamSearchDialog,
                    "Trying ${next.result.source ?: "another source"}…",
                    Toast.LENGTH_SHORT
                ).show()

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
        val language = when {
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

        if (resultsHost.childCount == 1) {
            row.post { row.requestFocus() }
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

                addons.map { addon ->
                    async {
                        val manifestResult =
                            stremioClient.fetchManifest(
                                addon.manifestUrl
                            )

                        val manifest = manifestResult.getOrNull()

                        if (manifest == null) {
                            runOnUiThread {
                                status.text = "${addon.name}: manifest failed"
                            }
                            return@async emptyList()
                        }

                        val streamsResult =
                            stremioClient.streams(
                                manifest = manifest,
                                type = type,
                                contentId = contentId
                            )

                        val addonStreams =
                            streamsResult.getOrElse {
                                runOnUiThread {
                                    status.text = "${manifest.name}: stream request failed"
                                }
                                emptyList()
                            }

                        runOnUiThread {
                            status.text =
                                "${manifest.name}: ${addonStreams.size} result(s)"
                        }

                        addonStreams
                    }
                }
                    .awaitAll()
                    .flatten()
                    // Diagnostic mode: expose every unique result returned by every
                    // enabled Stremio addon. Do not discard 4K or cap quality tiers.
                    .distinctBy { it.url ?: it.magnet ?: it.title }
                    .forEach { stream ->
                        val token =
                            stream.url ?: stream.magnet
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

'''

src = src[:start_m.start()] + new_function + src[end_m.start():]

with open(path, "w", encoding="utf-8") as f:
    f.write(src)

print("Patched showStreamSearchDialog successfully.")

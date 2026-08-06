package com.lumora

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.lumora.data.remote.concerts.ConcertCatalogClient
import com.lumora.model.Channel

internal fun MainActivity.playConcertItem(item: Channel) {
    val key = youtubeVideoId(item.url)

    if (key == null) {
        android.widget.Toast.makeText(
            this,
            "That concert link could not be opened.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        return
    }

    showTrailerPlayer(key)
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
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
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
        val result = ConcertCatalogClient().loadShelves()

        val shelves = result.getOrElse {
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

        concertShelves = shelves

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

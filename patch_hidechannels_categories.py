import re, shutil, datetime, sys

path = "app/src/main/java/com/lumora/MainActivityCategories.kt"
ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
backup = f"{path}.before-hidechannels-{ts}"
shutil.copy(path, backup)
print(f"Backed up to {backup}")

with open(path, "r", encoding="utf-8") as f:
    src = f.read()

anchor = re.compile(
    r"(if \(activeTab == 0\) liveAdapter\.notifyDataSetChanged\(\)\s*\})",
    re.DOTALL
)
matches = anchor.findall(src)
if len(matches) != 1:
    print(f"FAILED: expected exactly 1 match for toggleFavoriteChannel's closing block, found {len(matches)}. Aborting.")
    sys.exit(1)

new_functions = '''

/** Long-press menu for a Live TV channel tile/row: favourite, hide, or jump straight to
 *  managing the current hidden list. Replaces the old single-action favourite-only
 *  long-press - favourite is still one tap away, just behind this chooser instead of
 *  being the only option. */
internal fun MainActivity.showChannelContextMenu(channel: Channel) {
    if (channel.id.isBlank()) return
    val isFav = FavoritesStore.isFavoriteChannel(this, channel.id)
    val isHidden = com.lumora.cache.HiddenChannelsStore.isHidden(this, channel.id)
    val options = arrayOf(
        if (isFav) "Remove from Favourites" else "Add to Favourites",
        if (isHidden) "Unhide this channel" else "Hide this channel",
        "Manage Hidden Channels\\u2026"
    )
    AlertDialog.Builder(this)
        .setTitle(channel.name)
        .setItems(options) { _, which ->
            when (which) {
                0 -> toggleFavoriteChannel(channel)
                1 -> toggleHiddenLiveChannel(channel)
                2 -> showManageHiddenChannelsDialog()
            }
        }
        .show()
}

/** Hides/unhides a single Live TV channel by id. Rebuilds the live list (which re-derives
 *  liveChannels with the hidden set applied - see the rawLive filter in
 *  MainActivityCatalog.kt) rather than just the sidebar, since the channel itself must
 *  disappear from the guide, not just from whichever category it's filed under. */
internal fun MainActivity.toggleHiddenLiveChannel(channel: Channel) {
    if (channel.id.isBlank()) return
    val nowHidden = com.lumora.cache.HiddenChannelsStore.toggleHidden(this, channel.id)
    Toast.makeText(
        this,
        if (nowHidden) "Hidden \\"${channel.name}\\"" else "Unhidden \\"${channel.name}\\"",
        Toast.LENGTH_SHORT
    ).show()
    if (activeTab == 0) scope.launch { classifyAndShow() }
}

/** Lists every currently-hidden Live TV channel by name, tapping one unhides it. Reads
 *  from allChannels (the full unfiltered catalog) rather than liveChannels, since by the
 *  time this runs liveChannels has already had every hidden channel filtered out of it. */
internal fun MainActivity.showManageHiddenChannelsDialog() {
    val hiddenIds = com.lumora.cache.HiddenChannelsStore.getHiddenChannelIds(this)
    if (hiddenIds.isEmpty()) {
        Toast.makeText(this, "No hidden channels", Toast.LENGTH_SHORT).show()
        return
    }
    val hiddenChannels = allChannels
        .filter { it.mediaType == MediaType.LIVE && it.id in hiddenIds }
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
    if (hiddenChannels.isEmpty()) {
        Toast.makeText(this, "No hidden channels", Toast.LENGTH_SHORT).show()
        return
    }
    val names = hiddenChannels.map { it.name }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Hidden Channels (${hiddenChannels.size})")
        .setItems(names) { _, which ->
            toggleHiddenLiveChannel(hiddenChannels[which])
        }
        .setNegativeButton("Close", null)
        .show()
}'''

src = anchor.sub(lambda m: m.group(1) + new_functions, src, count=1)

with open(path, "w", encoding="utf-8") as f:
    f.write(src)

print("Added showChannelContextMenu / toggleHiddenLiveChannel / showManageHiddenChannelsDialog.")

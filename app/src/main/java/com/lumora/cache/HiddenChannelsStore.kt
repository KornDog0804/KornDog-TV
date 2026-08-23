package com.lumora.cache

import android.content.Context

private const val PREFS_NAME = "iptv_prefs"
private const val KEY_HIDDEN_CHANNELS = "hidden_channel_ids"

/** Per-channel hide list for Live TV - separate from category-level hiding
 *  (MainActivityCategories' hidden_categories_* prefs), which hides whole
 *  provider categories. This hides individual channels within categories
 *  that are otherwise still shown, e.g. keeping "USA" visible but hiding a
 *  handful of channels inside it. */
object HiddenChannelsStore {
    private val lock = Any()

    fun isHidden(context: Context, id: String): Boolean = id in getHiddenChannelIds(context)

    fun getHiddenChannelIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_CHANNELS, emptySet())?.toSet() ?: emptySet()

    /** Returns the new hidden state (true = now hidden). */
    fun toggleHidden(context: Context, id: String): Boolean = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getHiddenChannelIds(context).toMutableSet()
        val nowHidden = if (!current.remove(id)) { current.add(id); true } else false
        prefs.edit().putStringSet(KEY_HIDDEN_CHANNELS, current).apply()
        nowHidden
    }
}

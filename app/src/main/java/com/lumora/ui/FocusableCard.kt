package com.lumora.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.lumora.R

/**
 * KornDog-TV design system: shared focusable tile for poster art, live
 * channel tiles, and similar card-shaped content (Phase 1 foundation - see
 * plan doc §2.1).
 *
 * This is a NEW component, built in isolation. It is not yet used by any
 * existing adapter (PosterGridAdapter, LiveGuideAdapter, CategoryAdapter,
 * shelf adapters, etc.) - those migrate to it one at a time in later
 * phases, each its own verified build, per the locked "no mass adapter
 * rewrite" rule. Existing screens are completely unaffected by this file's
 * existence until a specific migration step wires it in.
 *
 * Handles, once adopted by a screen:
 *  - Consistent focus scale (1.08x) + elevation via kdtv_focus_scale
 *    animator, applied through stateListAnimator so it fires automatically
 *    on focus change with no per-instance wiring needed.
 *  - Consistent focus glow ring via kdtv_card_bg_selector.
 *  - Poster (2:3) vs. live-tile (16:9) aspect ratio via [Variant].
 *  - Optional micro badge (NEW / 4K / LIVE) and optional subtitle line.
 *
 * Does NOT handle: click/long-click wiring, image loading (callers still
 * own PosterLoader/whatever image pipeline they already use and set the
 * bitmap directly), or nextFocus contract (see FocusHelper for that,
 * wired in by the adapter that owns the RecyclerView, not by the card
 * itself - a single card doesn't know its neighbors).
 */
class FocusableCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    enum class Variant { POSTER, LIVE_TILE }

    val imageView: ImageView
    val initialView: TextView
    val badgeView: TextView
    val titleView: TextView
    val subtitleView: TextView

    private val artContainer: View

    init {
        LayoutInflater.from(context).inflate(R.layout.kdtv_focusable_card, this, true)

        imageView = findViewById(R.id.kdtvCardImage)
        initialView = findViewById(R.id.kdtvCardInitial)
        badgeView = findViewById(R.id.kdtvCardBadge)
        titleView = findViewById(R.id.kdtvCardTitle)
        subtitleView = findViewById(R.id.kdtvCardSubtitle)
        artContainer = findViewById(R.id.kdtvCardArt)

        isFocusable = true
        isFocusableInTouchMode = true
        background = androidx.core.content.ContextCompat.getDrawable(
            context, R.drawable.kdtv_card_bg_selector
        )
        stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
            context, R.animator.kdtv_focus_scale
        )
    }

    /** Sets the poster (2:3) or live-tile (16:9) aspect ratio for the art area. */
    fun setVariant(variant: Variant) {
        val lp = artContainer.layoutParams as? LayoutParams ?: return
        lp.dimensionRatio = when (variant) {
            Variant.POSTER -> "2:3"
            Variant.LIVE_TILE -> "16:9"
        }
        artContainer.layoutParams = lp
    }

    /** Shows the letter-initial fallback (no artwork available) instead of the ImageView. */
    fun showInitialFallback(letter: String) {
        initialView.text = letter
        initialView.visibility = View.VISIBLE
        imageView.visibility = View.GONE
    }

    fun showImage() {
        initialView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
    }

    /** Sets the micro badge (e.g. "4K", "NEW", "LIVE") or hides it if [text] is null/blank. */
    fun setBadge(text: String?) {
        if (text.isNullOrBlank()) {
            badgeView.visibility = View.GONE
        } else {
            badgeView.text = text
            badgeView.visibility = View.VISIBLE
        }
    }

    fun setTitle(text: String) {
        titleView.text = text
    }

    /** Sets the caption line (e.g. now/next for live, year/rating for posters), or hides it. */
    fun setSubtitle(text: String?) {
        if (text.isNullOrBlank()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.text = text
            subtitleView.visibility = View.VISIBLE
        }
    }
}

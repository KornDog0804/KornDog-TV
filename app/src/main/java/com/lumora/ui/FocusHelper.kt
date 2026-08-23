package com.lumora.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * KornDog-TV design system: shared D-pad focus resolution for grid-based
 * screens (Phase 1 foundation - see KornDog-TV-Redesign-Plan.md).
 *
 * The existing codebase hand-rolls nextFocus wiring per-adapter (see the
 * guide's channel-star LEFT/RIGHT listeners, the category rail's star
 * LEFT/RIGHT listeners). Each one is individually correct, but every screen
 * re-derives its own logic, which is exactly the kind of drift that produces
 * inconsistent, "clunky" D-pad behavior across the app - a fix in one place
 * doesn't help anywhere else, and a subtle mistake in one screen's version
 * doesn't get caught by another screen behaving correctly.
 *
 * This is NOT wired into anything yet. Phase 1 builds it in isolation;
 * migration happens one adapter at a time in later phases, per the "no mass
 * adapter rewrite" rule.
 */
object FocusHelper {

    /**
     * Attaches a key listener to [view] (a poster/tile/card inside a
     * GridLayoutManager-backed RecyclerView) that resolves UP from the top
     * row to [upTarget] explicitly, rather than relying on Android's default
     * 2D focus search - which is the most common source of "UP does nothing"
     * or "UP jumps somewhere unrelated" bugs in a TV grid, because default
     * focus search picks the geometrically nearest focusable view on screen,
     * which is not reliably the tab bar/sidebar once a RecyclerView has
     * scrolled or when neighboring rows exist above the grid's bounding box.
     *
     * Safe to call on a view with no existing key listener; does nothing to
     * views for non-top-row positions (the RecyclerView's own layout still
     * governs UP for every row below the first).
     */
    fun wireTopRowFocusUp(
        recyclerView: RecyclerView,
        view: View,
        adapterPosition: Int,
        spanCount: Int,
        upTarget: View
    ) {
        val isTopRow = adapterPosition in 0 until spanCount
        if (!isTopRow) return

        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                upTarget.requestFocus()
                true
            } else {
                false
            }
        }
    }

    /**
     * When a view gains focus inside a horizontally or vertically scrolling
     * container, ensures it's fully visible rather than clipped at the
     * scroll edge - the "half-clipped focused card" symptom named in the
     * Phase 1 success criteria. Call from a view's OnFocusChangeListener.
     */
    fun scrollIntoView(scrollContainer: RecyclerView, view: View) {
        val lp = view.layoutParams as? RecyclerView.LayoutParams ?: return
        val position = scrollContainer.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        scrollContainer.smoothScrollToPosition(position)
    }

    /**
     * Correct span-relative column index for a GridLayoutManager position -
     * needed by callers that want to know "is this the leftmost/rightmost
     * tile in its row" without re-deriving the math per screen.
     */
    fun columnIndex(recyclerView: RecyclerView, adapterPosition: Int): Int {
        val lm = recyclerView.layoutManager as? GridLayoutManager ?: return 0
        return adapterPosition % lm.spanCount
    }
}

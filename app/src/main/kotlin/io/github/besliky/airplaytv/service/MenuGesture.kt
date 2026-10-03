package io.github.besliky.airplaytv.service

/**
 * Tells a single press, a double press and a hold of the Menu key apart. With neither the double press nor the hold wanted a press
 * acts the moment the key goes down, as it always did; with the double press wanted a single press waits [DOUBLE_MS] to see whether a
 * second one follows; with the hold wanted a key held for [HOLD_MS] acts and its release does nothing. A press that finds something
 * open (the panel, the TV's menu) closes it at once and nothing else.
 *
 * It knows nothing of Android: the key events and the clock come from the caller ([post] runs something later, [cancel] takes it back).
 */
class MenuGesture(
    private val post: (Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val doublePressWanted: () -> Boolean,
    private val holdWanted: () -> Boolean,
    private val closeOpen: () -> Boolean,
    private val onAction: (Action) -> Unit,
) {
    enum class Action { SINGLE, DOUBLE, HOLD }

    private var ignoreUp = false
    private var holdFired = false
    private var singlePending = false

    private val singleRunnable = Runnable {
        singlePending = false
        onAction(Action.SINGLE)
    }

    private val holdRunnable = Runnable {
        holdFired = true
        singlePending = false
        cancel(singleRunnable)
        onAction(Action.HOLD)
    }

    /** The key went down; [repeat] is the event's repeat count (a held key sends more events, which mean nothing here). */
    fun onDown(repeat: Int) {
        if (repeat > 0) return
        if (closeOpen()) {
            ignoreUp = true
            return
        }
        if (!doublePressWanted() && !holdWanted()) {
            ignoreUp = true
            onAction(Action.SINGLE)
            return
        }
        ignoreUp = false
        holdFired = false
        // the second press of a double press has begun: the single press no longer fires by itself, the release of this press decides
        if (singlePending) cancel(singleRunnable)
        if (holdWanted()) post(HOLD_MS, holdRunnable)
    }

    fun onUp() {
        if (ignoreUp) {
            ignoreUp = false
            return
        }
        cancel(holdRunnable)
        if (holdFired) {
            holdFired = false
            return
        }
        if (!doublePressWanted()) {
            onAction(Action.SINGLE)
            return
        }
        if (singlePending) {
            singlePending = false
            cancel(singleRunnable)
            onAction(Action.DOUBLE)
        } else {
            singlePending = true
            post(DOUBLE_MS, singleRunnable)
        }
    }

    companion object {
        const val HOLD_MS = 800L
        const val DOUBLE_MS = 300L
    }
}

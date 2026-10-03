package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuGestureTest {

    /** A clock and a scheduler by hand: what was posted runs when the test moves time on. */
    private class Rig(var double: Boolean, var hold: Boolean, var open: Boolean = false) {
        val actions = ArrayList<MenuGesture.Action>()
        var closed = 0
        var now = 0L
        private val queue = ArrayList<Pair<Long, Runnable>>()

        val gesture = MenuGesture(
            post = { delay, run -> queue.add(now + delay to run) },
            cancel = { run -> queue.removeAll { it.second === run } },
            doublePressWanted = { double },
            holdWanted = { hold },
            closeOpen = {
                if (open) {
                    open = false
                    closed++
                    true
                } else {
                    false
                }
            },
            onAction = { actions.add(it) },
        )

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = queue.filter { it.first <= until }.minByOrNull { it.first } ?: break
                queue.remove(next)
                now = next.first
                next.second.run()
            }
            now = until
        }

        fun press(heldMs: Long = 50) {
            gesture.onDown(0)
            advance(heldMs)
            gesture.onUp()
        }
    }

    @Test
    fun `with nothing extra wanted a press acts the moment the key goes down`() {
        val rig = Rig(double = false, hold = false)
        rig.gesture.onDown(0)
        assertEquals(listOf(MenuGesture.Action.SINGLE), rig.actions)
        rig.gesture.onUp()
        assertEquals(listOf(MenuGesture.Action.SINGLE), rig.actions)
    }

    @Test
    fun `a single press waits for a second one and then acts alone`() {
        val rig = Rig(double = true, hold = false)
        rig.press()
        assertEquals(emptyList<MenuGesture.Action>(), rig.actions)
        rig.advance(MenuGesture.DOUBLE_MS + 10)
        assertEquals(listOf(MenuGesture.Action.SINGLE), rig.actions)
    }

    @Test
    fun `two quick presses are a double press and no single`() {
        val rig = Rig(double = true, hold = false)
        rig.press()
        rig.advance(120)
        rig.press()
        rig.advance(1000)
        assertEquals(listOf(MenuGesture.Action.DOUBLE), rig.actions)
    }

    @Test
    fun `two slow presses are two single presses`() {
        val rig = Rig(double = true, hold = false)
        rig.press()
        rig.advance(MenuGesture.DOUBLE_MS + 100)
        rig.press()
        rig.advance(MenuGesture.DOUBLE_MS + 100)
        assertEquals(listOf(MenuGesture.Action.SINGLE, MenuGesture.Action.SINGLE), rig.actions)
    }

    @Test
    fun `a held key acts once and its release does nothing`() {
        val rig = Rig(double = false, hold = true)
        rig.press(heldMs = MenuGesture.HOLD_MS + 200)
        rig.advance(1000)
        assertEquals(listOf(MenuGesture.Action.HOLD), rig.actions)
    }

    @Test
    fun `a short press with the hold wanted acts at release`() {
        val rig = Rig(double = false, hold = true)
        rig.gesture.onDown(0)
        rig.advance(100)
        assertEquals(emptyList<MenuGesture.Action>(), rig.actions)
        rig.gesture.onUp()
        assertEquals(listOf(MenuGesture.Action.SINGLE), rig.actions)
    }

    @Test
    fun `a press that finds something open closes it and does nothing else`() {
        val rig = Rig(double = true, hold = true, open = true)
        rig.press()
        rig.advance(2000)
        assertEquals(1, rig.closed)
        assertEquals(emptyList<MenuGesture.Action>(), rig.actions)
        // the next press is an ordinary one again
        rig.press()
        rig.advance(MenuGesture.DOUBLE_MS + 10)
        assertEquals(listOf(MenuGesture.Action.SINGLE), rig.actions)
    }

    @Test
    fun `the repeats of a held key mean nothing`() {
        val rig = Rig(double = false, hold = false)
        rig.gesture.onDown(0)
        rig.gesture.onDown(1)
        rig.gesture.onDown(2)
        assertEquals(listOf(MenuGesture.Action.SINGLE), rig.actions)
    }

    @Test
    fun `a second press that has begun keeps the first from acting alone`() {
        val rig = Rig(double = true, hold = false)
        rig.press()
        rig.advance(100)
        rig.gesture.onDown(0)
        rig.advance(500) // longer than the wait for a second press: the single press must not fire while the second one is down
        assertEquals(emptyList<MenuGesture.Action>(), rig.actions)
        rig.gesture.onUp()
        assertEquals(listOf(MenuGesture.Action.DOUBLE), rig.actions)
    }

    @Test
    fun `a double press whose second press is held gives the hold and no single`() {
        val rig = Rig(double = true, hold = true)
        rig.press()
        rig.advance(100)
        rig.press(heldMs = MenuGesture.HOLD_MS + 100)
        rig.advance(2000)
        assertEquals(listOf(MenuGesture.Action.HOLD), rig.actions)
    }
}

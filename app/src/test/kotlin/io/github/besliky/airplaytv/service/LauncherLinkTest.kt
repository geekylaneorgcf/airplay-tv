package io.github.besliky.airplaytv.service

import io.github.besliky.airplaytv.service.LauncherLink.Kind
import io.github.besliky.airplaytv.service.ReceiverState.Snapshot
import io.github.besliky.airplaytv.service.ReceiverState.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherLinkTest {

    @Test
    fun `music playing is audio with its title and artist`() {
        val s = Snapshot(status = Status.CONNECTED, audioActive = true, title = "Dil Lagiyan", artist = "Navaan Sandhu")
        assertEquals(LauncherLink.State(Kind.AUDIO, "Dil Lagiyan", "Navaan Sandhu"), LauncherLink.stateOf(s))
    }

    @Test
    fun `paused music is still audio, and says it is paused`() {
        val s = Snapshot(status = Status.CONNECTED, audioActive = true, title = "Dil Lagiyan", artist = "Navaan Sandhu", playing = false)
        assertEquals(LauncherLink.State(Kind.AUDIO, "Dil Lagiyan", "Navaan Sandhu", playing = false), LauncherLink.stateOf(s))
        // a change of play and pause alone is a different state: the home screen is told
        assertEquals(false, LauncherLink.stateOf(s) == LauncherLink.stateOf(s.copy(playing = true)))
    }

    @Test
    fun `a mirrored screen or a video is video, whatever else is going on`() {
        val s = Snapshot(status = Status.CONNECTED, videoActive = true, audioActive = true, title = "x", artist = "y")
        assertEquals(LauncherLink.State(Kind.VIDEO, "", ""), LauncherLink.stateOf(s))
    }

    @Test
    fun `nothing playing, or no sender, is nothing`() {
        assertEquals(Kind.NONE, LauncherLink.stateOf(Snapshot(status = Status.CONNECTED)).kind)
        assertEquals(Kind.NONE, LauncherLink.stateOf(Snapshot(status = Status.READY, audioActive = true, title = "x")).kind)
        assertEquals(Kind.NONE, LauncherLink.stateOf(Snapshot()).kind)
    }
}

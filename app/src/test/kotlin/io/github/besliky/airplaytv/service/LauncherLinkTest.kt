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

    @Test
    fun `who plays, the length and the cover belong to the state`() {
        val s = Snapshot(
            status = Status.CONNECTED, audioActive = true, title = "t", artist = "a", clientName = "Ashish's iPhone", artworkSeq = 3, durationMs = 200_000,
        )
        val state = LauncherLink.stateOf(s)
        assertEquals("Ashish's iPhone", state.client)
        assertEquals(3, state.coverSeq)
        assertEquals(200_000L, state.durationMs)
        assertEquals(false, state == LauncherLink.stateOf(s.copy(artworkSeq = 4)))
    }

    @Test
    fun `the place in the song moves on with time while the music plays, and stands still while it is paused`() {
        val s = Snapshot(status = Status.CONNECTED, audioActive = true, durationMs = 100_000, positionMs = 10_000, positionAtMs = 1_000, playing = true)
        assertEquals(15_000L, LauncherLink.positionNow(s, 6_000))
        assertEquals(10_000L, LauncherLink.positionNow(s.copy(playing = false), 6_000))
        assertEquals(100_000L, LauncherLink.positionNow(s, 500_000))
        assertEquals(-1L, LauncherLink.positionNow(s.copy(positionMs = -1), 6_000))
        assertEquals(-1L, LauncherLink.positionNow(s.copy(durationMs = -1), 6_000))
    }

    @Test
    fun `a change is announced, and so is a seek, but not the ordinary passing of time`() {
        val state = LauncherLink.State(Kind.AUDIO, "t", "a", true, "iPhone", 1, 200_000)
        assertEquals(true, LauncherLink.shouldSend(null, state, -1, 0, 5_000, 1_000))
        assertEquals(true, LauncherLink.shouldSend(state, state.copy(title = "u"), 5_000, 1_000, 6_000, 2_000))
        // five seconds later the place is where it was heading
        assertEquals(false, LauncherLink.shouldSend(state, state, 5_000, 1_000, 10_000, 6_000))
        // but a jump of a minute is a seek
        assertEquals(true, LauncherLink.shouldSend(state, state, 5_000, 1_000, 70_000, 6_000))
        // while paused the place stands still
        val paused = state.copy(playing = false)
        assertEquals(false, LauncherLink.shouldSend(paused, paused, 5_000, 1_000, 5_000, 60_000))
        assertEquals(true, LauncherLink.shouldSend(paused, paused, 5_000, 1_000, 40_000, 60_000))
    }

    @Test
    fun `the commands of the home screen's player become remote commands`() {
        assertEquals(DacpClient.PLAY_PAUSE, LauncherLink.dacpFor("playpause", -1))
        assertEquals(DacpClient.NEXT, LauncherLink.dacpFor("next", -1))
        assertEquals(DacpClient.PREVIOUS, LauncherLink.dacpFor("previous", -1))
        assertEquals(DacpClient.seekTo(42_000), LauncherLink.dacpFor("seek", 42_000))
        assertEquals(null, LauncherLink.dacpFor("seek", -1))
        assertEquals(null, LauncherLink.dacpFor("format everything", -1))
        assertEquals(null, LauncherLink.dacpFor(null, -1))
    }
}

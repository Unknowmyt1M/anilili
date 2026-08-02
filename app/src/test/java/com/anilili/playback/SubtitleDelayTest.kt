package com.anilili.playback

import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.NoSampleRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * The delay works by lying to the text renderer about where playback is, so the direction of that
 * lie is the whole feature: holding subtitles back by 15 s means asking the renderer, when the
 * video is at 20 s, for the line written for 5 s.
 */
@UnstableApi
class SubtitleDelayTest {
    private class RecordingRenderer : NoSampleRenderer() {
        val positionsUs = mutableListOf<Long>()

        override fun getName() = "recording"

        override fun supportsFormat(format: Format) = 0

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            positionsUs += positionUs
        }
    }

    private val delegate = RecordingRenderer()
    private val renderer = SubtitleDelayRenderer(delegate)

    @Before
    fun clearDelay() = SubtitleDelay.reset()

    @After
    fun restoreDelay() = SubtitleDelay.reset()

    @Test
    fun `passes the position straight through when nothing is set`() {
        renderer.render(5_000_000L, 0L)
        assertEquals(listOf(5_000_000L), delegate.positionsUs)
    }

    @Test
    fun `holding subtitles back asks for an earlier cue`() {
        SubtitleDelay.set(15_000L)
        renderer.render(20_000_000L, 0L)
        assertEquals(5_000_000L, delegate.positionsUs.last())
    }

    @Test
    fun `running subtitles ahead asks for a later cue`() {
        SubtitleDelay.set(-2_000L)
        renderer.render(20_000_000L, 0L)
        assertEquals(22_000_000L, delegate.positionsUs.last())
    }

    @Test
    fun `before the first line is due the shifted position goes negative`() {
        // A 15 s delay at 3 s of playback means nothing should be on screen yet, and "earlier than
        // the file starts" is the honest way to say so.
        SubtitleDelay.set(15_000L)
        renderer.render(3_000_000L, 0L)
        assertEquals(-12_000_000L, delegate.positionsUs.last())
    }

    @Test
    fun `the renderer follows changes made while it plays`() {
        renderer.render(10_000_000L, 0L)
        SubtitleDelay.set(4_000L)
        renderer.render(10_000_000L, 0L)
        SubtitleDelay.reset()
        renderer.render(10_000_000L, 0L)
        assertEquals(listOf(10_000_000L, 6_000_000L, 10_000_000L), delegate.positionsUs)
    }

    @Test
    fun `nudges accumulate and the value is clamped`() {
        SubtitleDelay.nudge(SubtitleDelay.STEP_MS)
        SubtitleDelay.nudge(SubtitleDelay.STEP_MS)
        assertEquals(500L, SubtitleDelay.delayMs.value)

        SubtitleDelay.set(SubtitleDelay.MAX_MS + 5_000L)
        assertEquals(SubtitleDelay.MAX_MS, SubtitleDelay.delayMs.value)
        SubtitleDelay.set(-SubtitleDelay.MAX_MS - 5_000L)
        assertEquals(-SubtitleDelay.MAX_MS, SubtitleDelay.delayMs.value)
    }

    @Test
    fun `a measured shift is flagged automatic, a hand-dialled one is not`() {
        SubtitleDelay.set(15_000L, automatic = true)
        assertEquals(true, SubtitleDelay.isAutomatic)
        SubtitleDelay.nudge(SubtitleDelay.STEP_MS)
        assertEquals(false, SubtitleDelay.isAutomatic)
        // A measured zero is just "nothing wrong here", not a finding to explain.
        SubtitleDelay.set(0L, automatic = true)
        assertEquals(false, SubtitleDelay.isAutomatic)
    }

    @Test
    fun `an opted-in anime delay wins over each episode provider offset`() {
        assertEquals(
            SubtitleDelaySeed(delayMs = 750L, automatic = false),
            subtitleDelaySeed(providerDelayMs = 4_000L, persistentDelayMs = 750L),
        )
    }

    @Test
    fun `without opt-in each episode keeps its provider measured offset`() {
        assertEquals(
            SubtitleDelaySeed(delayMs = 4_000L, automatic = true),
            subtitleDelaySeed(providerDelayMs = 4_000L, persistentDelayMs = null),
        )
    }
}

/**
 * Two identical cues alive at the same moment are what "double subtitles" looks like: the view
 * stacks them half a line apart. Only exact repeats collapse — everything else is a real second
 * line and must survive.
 */
@UnstableApi
class SimultaneousCueDedupeTest {
    private fun cue(text: String) = Cue.Builder().setText(text).build()

    @Test
    fun repeatedLineIsShownOnce() {
        val cues = listOf(cue("Just the seven of you."), cue("Just the seven of you."))
        assertEquals(listOf("Just the seven of you."), dedupeSimultaneousCues(cues).map { it.text })
    }

    @Test
    fun repeatWithSurroundingWhitespaceStillCollapses() {
        val cues = listOf(cue("Line"), cue("  Line \n"))
        assertEquals(1, dedupeSimultaneousCues(cues).size)
    }

    @Test
    fun genuinelyDifferentLinesBothSurvive() {
        val cues = listOf(cue("Top line"), cue("Bottom line"))
        assertEquals(cues.size, dedupeSimultaneousCues(cues).size)
    }

    @Test
    fun untouchedListKeepsItsIdentitySoNoCueGroupIsAllocated() {
        val cues = listOf(cue("Only line"), cue("Other line"))
        assertSame(cues, dedupeSimultaneousCues(cues))
    }

    /**
     * Textless cues — bitmap subtitles, which a JVM test cannot build — take the same branch as
     * these blank ones: no text to compare, so they are never treated as repeats of each other.
     */
    @Test
    fun cuesWithNoTextToCompareAllSurvive() {
        val cues = listOf(cue(""), cue("   "))
        assertEquals(cues.size, dedupeSimultaneousCues(cues).size)
    }
}

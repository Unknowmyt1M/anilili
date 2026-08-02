package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureControlsTest {
    @Test
    fun playbackTogglePausesEvenWhilePlaybackIsBuffering() {
        assertEquals(false, playerToggleWillPlay(playWhenReady = true))
        assertEquals(true, playerToggleWillPlay(playWhenReady = false))
    }

    @Test
    fun horizontalSlideSeeksRelativeToDragDistance() {
        assertEquals(
            630_000L,
            playerSlideSeekTarget(
                startPositionMs = 600_000L,
                durationMs = 1_440_000L,
                horizontalDragPx = 250f,
                widthPx = 1_000f,
            ),
        )
        assertEquals(
            570_000L,
            playerSlideSeekTarget(
                startPositionMs = 600_000L,
                durationMs = 1_440_000L,
                horizontalDragPx = -250f,
                widthPx = 1_000f,
            ),
        )
    }

    @Test
    fun horizontalSlideClampsToVideoBounds() {
        assertEquals(0L, playerSlideSeekTarget(10_000L, 60_000L, -1_000f, 1_000f))
        assertEquals(60_000L, playerSlideSeekTarget(50_000L, 60_000L, 1_000f, 1_000f))
    }

    @Test
    fun horizontalSlideWithoutDurationKeepsCurrentPosition() {
        assertEquals(15_000L, playerSlideSeekTarget(15_000L, 0L, 500f, 1_000f))
    }

    @Test
    fun verticalDragSplitsBrightnessAndVolumeDownTheMiddle() {
        assertEquals(GestureZone.Brightness, playerGestureZone(0f, 1_000f, dragGestures = true))
        assertEquals(GestureZone.Brightness, playerGestureZone(499f, 1_000f, dragGestures = true))
        assertEquals(GestureZone.Volume, playerGestureZone(500f, 1_000f, dragGestures = true))
        assertEquals(GestureZone.Volume, playerGestureZone(1_000f, 1_000f, dragGestures = true))
    }

    @Test
    fun midPictureDragsStillPickASideRatherThanGoingInert() {
        // The old edge strips reached ~28% in from each side and left the middle dead; every
        // horizontal position now belongs to one control or the other.
        assertEquals(GestureZone.Brightness, playerGestureZone(400f, 1_000f, dragGestures = true))
        assertEquals(GestureZone.Volume, playerGestureZone(600f, 1_000f, dragGestures = true))
    }

    @Test
    fun disabledGesturesAndDegenerateSurfacesClaimNothing() {
        assertEquals(null, playerGestureZone(100f, 1_000f, dragGestures = false))
        assertEquals(null, playerGestureZone(100f, 0f, dragGestures = true))
        assertEquals(null, playerGestureZone(Float.NaN, 1_000f, dragGestures = true))
    }
}

/**
 * Two TV users independently reported the control row appearing but ignoring the remote, with OK
 * firing "previous episode" — the row's leading button. Both noted it behaved only while the
 * "Next Episode" action was up, which is the tell: that action already exists before the row
 * opens, so its focus reclaim never re-fires. The "+Ns" button appears *with* the row, so its
 * reclaim did fire, one frame after the row had taken focus.
 */
class SkipActionFocusTest {
    @Test
    fun openingTheControlRowDoesNotPullFocusBackToThePlayer() {
        assertEquals(false, reclaimsPlayerFocusOnSkipAction(controlsVisible = true))
    }

    @Test
    fun timedActionOverBarePictureStillReclaimsTheRemote() {
        assertEquals(true, reclaimsPlayerFocusOnSkipAction(controlsVisible = false))
    }
}

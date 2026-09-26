package com.asr.live.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AudioCaptureLifecycleTest {
    private fun capture() = AudioCapture(RuntimeEnvironment.getApplication(),
        onChunk = {}, onStarted = {}, onFinished = {}, onError = {})

    /**
     * A Stop that races startup must not leave the recognizer waiting for audio that can never
     * arrive: a capture that never started already counts as finished.
     */
    @Test fun aCaptureThatNeverStartedCountsAsFinished() {
        val capture = capture()
        assertFalse("a live capture is not finished", capture.isFinished())

        capture.stopCapturing()
        assertTrue(capture.isFinished())
    }

    @Test fun cancelAlsoMarksAnUnstartedCaptureFinished() {
        val capture = capture()
        capture.cancel()
        assertTrue(capture.isFinished())
        assertTrue("there is no worker thread to join", capture.threadForJoin() == null)
    }
}

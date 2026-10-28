package com.applooma.rtc

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * On the JVM every android.media call throws ("Stub!"), which is exactly what a
 * phone that refuses the silent track does. The keep-alive must swallow that,
 * log once, switch itself off, and never throw to the caller (the audio thread
 * or the main thread).
 */
class AudioKeepAliveTest {
    private val logs = mutableListOf<String>()

    @Before fun reset() { AudioKeepAlive.refused = false; logs.clear() }
    @After fun cleanup() { AudioKeepAlive.refused = false }

    private fun keepAlive(comm: Boolean = true, sdk: Int = 30) = AudioKeepAlive(comm, { logs += it }, sdk)

    @Test fun refusedTrackDoesNotThrowAndTurnsItselfOff() {
        val k = keepAlive()
        k.start(true)          // playout stopped + started -> tries to create the track -> refused
        assertFalse(k.isPlaying)
        assertTrue(AudioKeepAlive.refused)
        assertTrue(logs.single().contains("create failed"))
        // Further state changes from the audio thread stay quiet and safe.
        k.onPlayoutStarted(); k.onPlayoutStopped(); k.stop(); k.start(true)
        assertFalse(k.isPlaying)
        assertTrue(logs.last().contains("skipped"))
        k.release()
    }

    @Test fun disabledByOptionOrRuleNeverTouchesAudio() {
        val k = keepAlive()
        k.start(false)
        k.onPlayoutStopped()
        assertFalse(AudioKeepAlive.refused)
        assertTrue(logs.isEmpty())
    }

    @Test fun notInCommunicationModeOrOldAndroidDoesNothing() {
        keepAlive(comm = false).start(true)
        keepAlive(sdk = 29).start(true)
        assertFalse(AudioKeepAlive.refused)
        assertTrue(logs.isEmpty())
    }

    @Test fun playoutRunningMeansNoTrackNeeded() {
        val k = keepAlive()
        k.onPlayoutStarted()
        k.start(true)
        assertFalse(AudioKeepAlive.refused)  // never tried to build: someone is being heard
        assertTrue(logs.isEmpty())
    }
}

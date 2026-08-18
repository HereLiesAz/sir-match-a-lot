package com.hereliesaz.sirmatchalot.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioSystem

/**
 * A missing or unmatched audio line must reach [DesktopAudioOutput.onError],
 * not crash whatever called [DesktopAudioOutput.start] — a phone essentially
 * always has an output; a build box or a fresh VM may not, and CI itself is
 * exactly such a machine (`AudioSystem.getMixerInfo()` returns none here).
 */
class DesktopAudioOutputTest {

    @Test
    fun `a missing audio line reports through onError instead of throwing`() {
        // Only meaningful proof on a machine with no usable line, which is
        // this one — skip rather than pass vacuously if that ever changes.
        org.junit.Assume.assumeTrue(
            "this test needs an environment with no audio line",
            AudioSystem.getMixerInfo().isEmpty(),
        )

        val output = DesktopAudioOutput()
        val errorLatch = CountDownLatch(1)
        val rendered = AtomicBoolean(false)
        output.onError = { errorLatch.countDown() }

        output.start { _, _ -> rendered.set(true) }

        assertTrue("onError should fire", errorLatch.await(2, TimeUnit.SECONDS))
        assertFalse("render must never run without a line", rendered.get())

        // Safe to call even though start() never actually got a line.
        output.stop()
        output.release()
    }
}

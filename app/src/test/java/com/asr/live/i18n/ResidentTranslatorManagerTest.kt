package com.asr.live.i18n

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ResidentTranslatorManagerTest {
    private class FakeTranslator(val id: Int) : LocalTranslator {
        val closed = AtomicInteger(0)
        override fun translate(text: String) = text
        override fun close() { closed.incrementAndGet() }
    }

    private fun identity(bundle: String = "hymt2-Q8_0") = TranslatorIdentity(
        modelBundle = bundle, sourceLanguage = "nl", targetLanguage = "en", glossary = "",
        backendRequestsOpenCl = false, batch = 256, ubatch = 128)

    @Test fun secondAcquireWithTheSameIdentityReusesTheInstanceAndSkipsTheLoad() {
        var created = 0
        val slot = "hy-${System.identityHashCode(this)}"
        val first = ResidentTranslatorManager.acquire(slot, identity(), { created++; FakeTranslator(created) })
        var reused = false
        val second = ResidentTranslatorManager.acquire(slot, identity(), { created++; FakeTranslator(created) }) { reused = it }

        assertSame("identical identity must reuse the resident instance", first, second)
        assertEquals("the loader must not run a second time", 1, created)
        assertTrue(reused)
        ResidentTranslatorManager.closeAll()
    }

    @Test fun listenStopListenNeverReloadsTheGgufWhenConfigurationIsUnchanged() {
        var loads = 0
        val slot = "hy-${System.identityHashCode(this)}-cycle"
        // Three Listen -> Stop -> Listen cycles with an identical SessionConfig.
        repeat(3) {
            ResidentTranslatorManager.acquire(slot, identity(), { loads++; FakeTranslator(loads) })
            // Stop: session calls release(), which is intentionally a no-op for residency.
            ResidentTranslatorManager.release(slot)
        }
        assertEquals("only the very first Listen should load the model", 1, loads)
        ResidentTranslatorManager.closeAll()
    }

    @Test fun aDifferentIdentityClosesTheStaleResidentBeforeBuildingTheNewOne() {
        val slot = "hy-${System.identityHashCode(this)}-swap"
        val first = ResidentTranslatorManager.acquire(slot, identity("hymt2-Q8_0"), { FakeTranslator(1) }) as FakeTranslator
        var reused = true
        val second = ResidentTranslatorManager.acquire(slot, identity("hymt2-Q4_K_M"), { FakeTranslator(2) }) { reused = it }

        assertFalse("a changed quality bundle is a different identity", reused)
        assertEquals(1, first.closed.get())
        assertNotSame(first, second)
        ResidentTranslatorManager.closeAll()
    }

    @Test fun closeAllClosesEveryResidentSlotExactlyOnce() {
        val hySlot = "hy-${System.identityHashCode(this)}-all"
        val opusSlot = "opus-${System.identityHashCode(this)}-all"
        val hy = ResidentTranslatorManager.acquire(hySlot, identity(), { FakeTranslator(1) }) as FakeTranslator
        val opus = ResidentTranslatorManager.acquire(opusSlot, identity("opus-nl-en"), { FakeTranslator(2) }) as FakeTranslator

        ResidentTranslatorManager.closeAll()
        ResidentTranslatorManager.closeAll() // idempotent: nothing left to double-close

        assertEquals(1, hy.closed.get())
        assertEquals(1, opus.closed.get())
        assertFalse(ResidentTranslatorManager.isResident(hySlot, identity()))
    }

    @Test fun forgetOnlyDropsTheMatchingIdentityWithoutClosingAnything() {
        val slot = "hy-${System.identityHashCode(this)}-forget"
        val identity = identity()
        val translator = ResidentTranslatorManager.acquire(slot, identity, { FakeTranslator(1) }) as FakeTranslator

        ResidentTranslatorManager.forget(slot, identity("hymt2-Q4_K_M")) // mismatched identity: no-op
        assertTrue(ResidentTranslatorManager.isResident(slot, identity))

        ResidentTranslatorManager.forget(slot, identity)
        assertFalse(ResidentTranslatorManager.isResident(slot, identity))
        assertEquals("forget never closes the native resource", 0, translator.closed.get())
        ResidentTranslatorManager.closeAll()
    }
}

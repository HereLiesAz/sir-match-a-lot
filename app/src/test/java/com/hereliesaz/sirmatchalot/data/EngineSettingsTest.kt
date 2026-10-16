package com.hereliesaz.sirmatchalot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings that decide how much memory and power the app spends.
 *
 * These exist because the two failures they address — running out of heap, and
 * running the battery down faster than everything else on the device combined —
 * have no single right answer across devices. What is tested here is that the
 * arithmetic behind each choice is honest: that a lower rate really does cost
 * proportionally less, that a budget really is the fraction it claims, and that
 * a choice survives being written down and read back.
 */
class EngineSettingsTest {

    /** A settings store with no Android under it. */
    private class MapStore(
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : KeyValueStore {
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    // --- Sample rate ---

    @Test
    fun `the device option resolves to whatever the device reports`() {
        assertEquals(44_100, SampleRateOption.DEVICE.resolve(44_100))
        assertEquals(48_000, SampleRateOption.DEVICE.resolve(48_000))
        assertEquals(96_000, SampleRateOption.DEVICE.resolve(96_000))
    }

    @Test
    fun `an explicit rate ignores the device`() {
        assertEquals(22_050, SampleRateOption.HZ_22050.resolve(48_000))
        assertEquals(44_100, SampleRateOption.HZ_44100.resolve(96_000))
    }

    @Test
    fun `memory cost falls in proportion to the rate`() {
        // This is the whole reason the setting exists, so it is worth asserting
        // rather than assuming: half the rate is half the bytes, exactly.
        val full = SampleRateOption.HZ_44100.megabytesPerMinute(48_000)
        val half = SampleRateOption.HZ_22050.megabytesPerMinute(48_000)
        assertEquals(full / 2, half, 0.01)
    }

    @Test
    fun `a minute of CD-rate stereo is about ten megabytes`() {
        // 44100 * 2 channels * 2 bytes * 60 seconds = 10.1 MB. If this figure
        // ever stops being true, the numbers shown next to each choice are
        // lying about what the choice costs.
        assertEquals(10.1, SampleRateOption.HZ_44100.megabytesPerMinute(48_000), 0.1)
    }

    @Test
    fun `the rate options are ordered from most expensive to least`() {
        val explicit = SampleRateOption.entries.filter { it.hz != null }
        val costs = explicit.map { it.megabytesPerMinute(48_000) }
        assertEquals(
            "the list should read downward in cost",
            costs.sortedDescending(),
            costs,
        )
    }

    // --- Refresh rate ---

    @Test
    fun `frame intervals match their stated rates`() {
        assertEquals(16L, VisualRefresh.HIGH.frameMillis)
        assertEquals(33L, VisualRefresh.BALANCED.frameMillis)
        assertEquals(66L, VisualRefresh.BATTERY.frameMillis)
    }

    @Test
    fun `a lower refresh rate means a longer interval`() {
        assertTrue(VisualRefresh.BATTERY.frameMillis > VisualRefresh.BALANCED.frameMillis)
        assertTrue(VisualRefresh.BALANCED.frameMillis > VisualRefresh.HIGH.frameMillis)
    }

    // --- Memory budget ---

    @Test
    fun `a budget is the fraction of the heap it says it is`() {
        val heap = 300L * 1024 * 1024
        assertEquals(heap / 6, MemoryBudget.SMALL.bytesFor(heap))
        assertEquals(heap / 3, MemoryBudget.BALANCED.bytesFor(heap))
        assertEquals(heap / 2, MemoryBudget.GENEROUS.bytesFor(heap))
    }

    @Test
    fun `no budget claims more than half the heap`() {
        // The rest has to cover the decoder's accumulation buffers, the
        // conversion intermediates and Compose. A cache allowed the whole heap
        // is a cache that guarantees the crash.
        val heap = 256L * 1024 * 1024
        for (budget in MemoryBudget.entries) {
            assertTrue(budget.name, budget.bytesFor(heap) <= heap / 2)
        }
    }

    // --- Persistence ---

    @Test
    fun `an empty store yields the defaults`() {
        val settings = SettingsStore(MapStore()).load()
        assertEquals(SampleRateOption.DEVICE, settings.sampleRate)
        assertEquals(VisualRefresh.BALANCED, settings.visualRefresh)
        assertEquals(MemoryBudget.BALANCED, settings.memoryBudget)
        assertTrue("the light show is part of the app, not an extra", settings.lightShow)
        assertTrue("standing down when silent is the default", settings.idleShutdown)
    }

    @Test
    fun `settings survive a round trip`() {
        val store = MapStore()
        val chosen = EngineSettings(
            sampleRate = SampleRateOption.HZ_32000,
            visualRefresh = VisualRefresh.BATTERY,
            memoryBudget = MemoryBudget.SMALL,
            lightShow = false,
            idleShutdown = false,
        )
        SettingsStore(store).save(chosen)
        assertEquals(chosen, SettingsStore(store).load())
    }

    @Test
    fun `an unrecognised stored value falls back rather than throwing`() {
        // A downgrade, or a renamed enum constant, must not stop the app
        // opening. Nothing here is worth a crash on launch.
        val store = MapStore(
            mutableMapOf(
                "sample_rate" to "HZ_99999",
                "visual_refresh" to "",
                "memory_budget" to "ENORMOUS",
                "light_show" to "perhaps",
            ),
        )
        val settings = SettingsStore(store).load()
        assertEquals(SampleRateOption.DEVICE, settings.sampleRate)
        assertEquals(VisualRefresh.BALANCED, settings.visualRefresh)
        assertEquals(MemoryBudget.BALANCED, settings.memoryBudget)
        assertTrue(settings.lightShow)
    }

    // --- What a change implies ---

    @Test
    fun `only a rate change needs a new engine`() {
        val base = EngineSettings()
        assertTrue(base.copy(sampleRate = SampleRateOption.HZ_32000).requiresEngineRebuild(base))
        assertFalse(base.copy(lightShow = false).requiresEngineRebuild(base))
        assertFalse(base.copy(idleShutdown = false).requiresEngineRebuild(base))
        assertFalse(base.copy(memoryBudget = MemoryBudget.SMALL).requiresEngineRebuild(base))
        assertFalse(base.copy(visualRefresh = VisualRefresh.HIGH).requiresEngineRebuild(base))
    }

    @Test
    fun `resolving to the same rate is not a rebuild in disguise`() {
        // DEVICE on a 48 kHz phone and an explicit 48 kHz are the same engine.
        // They are still different *settings*, and the check is on the setting —
        // so this documents that a redundant rebuild is possible, and that it is
        // the price of not having to ask the device inside a value class.
        val base = EngineSettings(sampleRate = SampleRateOption.DEVICE)
        val explicit = base.copy(sampleRate = SampleRateOption.HZ_48000)
        assertTrue(explicit.requiresEngineRebuild(base))
        assertEquals(
            SampleRateOption.DEVICE.resolve(48_000),
            SampleRateOption.HZ_48000.resolve(48_000),
        )
    }

    // --- The identity key's move to its own, unbacked-up store ---

    private class IdentityMapStore : KeyValueStore {
        val values = HashMap<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private fun migrating(primary: IdentityMapStore, legacy: IdentityMapStore) = MigratingKeyValueStore(
        primary = primary,
        legacy = legacy,
        removeLegacy = { legacy.values.remove(it) },
        migratedKeys = SettingsStore.IDENTITY_KEYS,
    )

    @Test
    fun `an identity key in the old settings file moves on first read and is deleted there`() {
        val primary = IdentityMapStore()
        val legacy = IdentityMapStore().apply {
            values["identity_private"] = "secret"
            values["sample_rate"] = "HZ_48000"
        }
        val store = migrating(primary, legacy)

        assertEquals("secret", store.getString("identity_private"))
        assertEquals("secret", primary.values["identity_private"])
        assertFalse("the key is still in the backed-up file", "identity_private" in legacy.values)
        assertEquals("unrelated settings are untouched", "HZ_48000", legacy.values["sample_rate"])
    }

    @Test
    fun `a key not being migrated is never read from the old file`() {
        val legacy = IdentityMapStore().apply { values["sample_rate"] = "HZ_48000" }
        val store = migrating(IdentityMapStore(), legacy)
        assertEquals(null, store.getString("sample_rate"))
    }

    @Test
    fun `writes go to the new store and clear any stale copy in the old one`() {
        val primary = IdentityMapStore()
        val legacy = IdentityMapStore().apply { values["known_devices"] = "old" }
        migrating(primary, legacy).putString("known_devices", "new")
        assertEquals("new", primary.values["known_devices"])
        assertFalse("known_devices" in legacy.values)
    }

    @Test
    fun `the migrated keys are the ones DeviceIdentity actually writes`() {
        val written = IdentityMapStore()
        com.hereliesaz.sirmatchalot.sync.DeviceIdentity.load(written)
        com.hereliesaz.sirmatchalot.sync.KnownDevices(written).let { known ->
            // Remembering one device forces the list to be written.
            known.remember("fingerprint", "Other")
        }
        assertEquals(SettingsStore.IDENTITY_KEYS, written.values.keys)
    }
}

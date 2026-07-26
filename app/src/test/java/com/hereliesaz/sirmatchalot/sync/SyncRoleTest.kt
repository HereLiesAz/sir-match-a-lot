package com.hereliesaz.sirmatchalot.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roles are part of the wire protocol, so their spellings are a commitment.
 *
 * These tests exist mostly to pin the strings: renaming an enum constant is a
 * refactor, renaming a `wireName` breaks every older device in the room.
 */
class SyncRoleTest {

    @Test
    fun `wire names are the documented ones`() {
        assertEquals("all", SyncRole.ALL.wireName)
        assertEquals("library", SyncRole.LIBRARY.wireName)
        assertEquals("decks", SyncRole.DECKS.wireName)
        assertEquals("pads", SyncRole.PADS.wireName)
    }

    @Test
    fun `every role round-trips through its wire name`() {
        for (role in SyncRole.entries) {
            assertEquals(role, SyncRole.fromWire(role.wireName))
        }
    }

    @Test
    fun `matching ignores case and surrounding space`() {
        assertEquals(SyncRole.PADS, SyncRole.fromWire("  PaDs "))
        assertEquals(SyncRole.DECKS, SyncRole.fromWire("DECKS"))
    }

    @Test
    fun `older spellings still resolve`() {
        assertEquals(SyncRole.LIBRARY, SyncRole.fromWire("browser"))
        assertEquals(SyncRole.DECKS, SyncRole.fromWire("platter"))
        assertEquals(SyncRole.PADS, SyncRole.fromWire("sampler"))
    }

    @Test
    fun `an unknown role is full control rather than nothing`() {
        // A device joining a room run by a newer version should get the whole
        // instrument, not a blank screen.
        assertEquals(SyncRole.ALL, SyncRole.fromWire("time-machine"))
        assertEquals(SyncRole.ALL, SyncRole.fromWire(null))
        assertEquals(SyncRole.ALL, SyncRole.fromWire(""))
    }

    @Test
    fun `only ALL is full control`() {
        assertTrue(SyncRole.ALL.isFullControl)
        assertFalse(SyncRole.LIBRARY.isFullControl)
        assertFalse(SyncRole.DECKS.isFullControl)
        assertFalse(SyncRole.PADS.isFullControl)
    }
}

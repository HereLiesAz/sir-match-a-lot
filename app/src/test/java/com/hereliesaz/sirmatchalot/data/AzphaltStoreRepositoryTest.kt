package com.hereliesaz.sirmatchalot.data

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AzphaltStoreRepositoryTest {

    @Test
    fun `readBounded returns the full text when it is within the limit`() {
        val text = "{\"packages\":[]}"
        val stream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

        val result = AzphaltStoreRepository.readBounded(stream, limit = 1024)

        assertEquals(text, result)
    }

    @Test
    fun `readBounded refuses to allocate past the limit instead of reading it all`() {
        // A response one byte over a tiny limit must fail fast, not be read to
        // EOF first — that is the whole point of bounding it.
        val oversized = ByteArray(1025) { 'a'.code.toByte() }
        val stream = ByteArrayInputStream(oversized)

        assertThrows(IOException::class.java) {
            AzphaltStoreRepository.readBounded(stream, limit = 1024)
        }
    }
}

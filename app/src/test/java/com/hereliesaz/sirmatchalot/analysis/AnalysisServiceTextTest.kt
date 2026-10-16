package com.hereliesaz.sirmatchalot.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

/** The end-of-run summary must not count failed tracks as measured. */
class AnalysisServiceTextTest {

    @Test
    fun `failures are not reported as measured`() {
        assertEquals("8 tracks measured, 2 could not be analysed", AnalysisService.completionText(10, 2))
    }

    @Test
    fun `a clean run says so`() {
        assertEquals("1 track measured", AnalysisService.completionText(1, 0))
    }
}

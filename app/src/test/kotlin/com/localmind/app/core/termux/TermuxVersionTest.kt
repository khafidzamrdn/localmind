package com.localmind.app.core.termux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxVersionTest {

    @Test
    fun `accepts the minimum version and newer releases`() {
        assertTrue(TermuxVersion.atLeast("0.109", "0.109"))
        assertTrue(TermuxVersion.atLeast("0.118.1", "0.109"))
        assertTrue(TermuxVersion.atLeast("0.109.0", "0.109"))
        assertTrue(TermuxVersion.atLeast("1.0", "0.109"))
    }

    @Test
    fun `rejects older or unknown versions`() {
        assertFalse(TermuxVersion.atLeast("0.108", "0.109"))
        assertFalse(TermuxVersion.atLeast("0.100.2", "0.109"))
        assertFalse(TermuxVersion.atLeast(null, "0.109"))
    }
}

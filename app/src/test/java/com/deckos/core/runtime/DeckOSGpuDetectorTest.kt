package com.deckos.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckOSGpuDetectorTest {

    @Test
    fun testDefaultDetectionReturnsValidEnvironment() {
        val env = DeckOSGpuDetector.detect("/tmp/fake_prefix")
        assertNotNull(env)
        assertNotNull(env.mode)
        assertNotNull(env.notes)
    }

    @Test
    fun testExtraEnvArrayMatchesEnvVars() {
        val extraEnv = DeckOSGpuDetector.extraEnvArray("/tmp/fake_prefix")
        assertNotNull(extraEnv)
        for (entry in extraEnv) {
            assertTrue(entry.contains("="))
        }
    }

    @Test
    fun testVendorDefaultModeProperties() {
        val env = DeckOSGpuDetector.detect("/tmp/prefix")
        if (System.getenv("DECKOS_GPU") == null) {
            assertEquals(DeckOSGpuDetector.GpuMode.VENDOR_DEFAULT, env.mode)
            assertTrue(env.envVars.isEmpty())
        }
    }
}

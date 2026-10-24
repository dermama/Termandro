package com.deckos.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatusTest {

    @Test
    fun testSessionConstants() {
        assertEquals("core", SessionManager.PATH_CORE)
        assertEquals("enhanced", SessionManager.PATH_ENHANCED)
    }

    @Test
    fun testSessionStates() {
        val states = SessionManager.State.values().map { it.name }
        assertEquals(listOf("IDLE", "STARTING", "RUNNING", "STOPPING", "ERROR"), states)
    }

    @Test
    fun testSessionStatusDataClass() {
        val status = SessionManager.SessionStatus(
            state = SessionManager.State.IDLE,
            path = SessionManager.PATH_CORE,
            x11Pid = null,
            desktopPid = null,
            gpuMode = DeckOSGpuDetector.GpuMode.VENDOR_DEFAULT,
            cycleCount = 0,
            lastError = null,
        )

        assertEquals(SessionManager.State.IDLE, status.state)
        assertEquals(SessionManager.PATH_CORE, status.path)
        assertNull(status.x11Pid)
        assertNull(status.desktopPid)
        assertEquals(DeckOSGpuDetector.GpuMode.VENDOR_DEFAULT, status.gpuMode)
        assertEquals(0, status.cycleCount)
        assertNull(status.lastError)
    }
}

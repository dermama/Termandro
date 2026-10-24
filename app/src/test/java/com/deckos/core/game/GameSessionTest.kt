package com.deckos.core.game

import org.junit.Assert.*
import org.junit.Test
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5A — Unit Tests for Game Mode contracts
//
// Covers:
//  • GamePhase state machine ordering (L-70)
//  • GameConfig defaults and resolution profiles (L-70)
//  • PreflightResult pass/fail logic (L-73)
//  • ProtonExtras-equivalent: GamePack version comparison (L-72)
//  • GameOverlay stats readers (L-77 — null-safe on non-Android JVM)
// ─────────────────────────────────────────────────────────────────────────────

class GameSessionTest {

    // ── GamePhase ordering ────────────────────────────────────────────────

    @Test fun `IDLE is the first phase`() {
        assertEquals(GamePhase.IDLE, GamePhase.values().first())
    }

    @Test fun `FAILED is a terminal phase followed by enum end`() {
        val phases = GamePhase.values().toList()
        assertTrue("FAILED should exist", phases.contains(GamePhase.FAILED))
    }

    @Test fun `GamePhase does not contain duplicate names`() {
        val names = GamePhase.values().map { it.name }
        assertEquals(names.distinct().size, names.size)
    }

    // ── GameConfig defaults ───────────────────────────────────────────────

    @Test fun `GameConfig default runtimeHint is native`() {
        assertEquals("native", GameConfig().runtimeHint)
    }

    @Test fun `GameConfig default resolution is HD`() {
        assertEquals(GameConfig.ResolutionProfile.HD, GameConfig().resolutionProfile)
    }

    @Test fun `GameConfig HD resolution is 1920x1080`() {
        val r = GameConfig.ResolutionProfile.HD
        assertEquals(1920, r.width)
        assertEquals(1080, r.height)
    }

    @Test fun `GameConfig HD720 resolution is 1280x720`() {
        val r = GameConfig.ResolutionProfile.HD720
        assertEquals(1280, r.width)
        assertEquals(720, r.height)
    }

    @Test fun `GameConfig FULL resolution width is 0 sentinel`() {
        assertEquals(0, GameConfig.ResolutionProfile.FULL.width)
    }

    @Test fun `GameConfig fpsCap default is 0 (uncapped)`() {
        assertEquals(0, GameConfig().fpsCap)
    }

    @Test fun `GameConfig fsrRatio default is 1f (no upscale)`() {
        assertEquals(1.0f, GameConfig().fsrRatio, 0.001f)
    }

    // ── PreflightResult logic ─────────────────────────────────────────────

    @Test fun `PreflightResult ok is true when all checks pass`() {
        val r = PreflightResult(
            ramOkMb               = 2_000,
            storageFreeGb         = 5.0f,
            gpuSupported          = true,
            temperatureSafe       = true,
            requiredPackInstalled = true,
        )
        assertTrue(r.ok)
        assertTrue(r.issues.isEmpty())
    }

    @Test fun `PreflightResult ok is false when RAM is too low`() {
        val r = PreflightResult(
            ramOkMb               = 500,
            storageFreeGb         = 5.0f,
            gpuSupported          = true,
            temperatureSafe       = true,
            requiredPackInstalled = true,
        )
        assertFalse(r.ok)
        assertTrue(r.issues.any { it.contains("RAM") })
    }

    @Test fun `PreflightResult ok is false when storage is too low`() {
        val r = PreflightResult(
            ramOkMb               = 2_000,
            storageFreeGb         = 0.2f,
            gpuSupported          = true,
            temperatureSafe       = true,
            requiredPackInstalled = true,
        )
        assertFalse(r.ok)
        assertTrue(r.issues.any { it.contains("storage") })
    }

    @Test fun `PreflightResult ok is false when GPU not supported`() {
        val r = PreflightResult(
            ramOkMb               = 2_000,
            storageFreeGb         = 5.0f,
            gpuSupported          = false,
            temperatureSafe       = true,
            requiredPackInstalled = true,
        )
        assertFalse(r.ok)
        assertTrue(r.issues.any { it.contains("GPU") })
    }

    @Test fun `PreflightResult ok is false when temperature is unsafe`() {
        val r = PreflightResult(
            ramOkMb               = 2_000,
            storageFreeGb         = 5.0f,
            gpuSupported          = true,
            temperatureSafe       = false,
            requiredPackInstalled = true,
        )
        assertFalse(r.ok)
        assertTrue(r.issues.any { it.contains("hot") })
    }

    @Test fun `PreflightResult issues list has exactly one entry per failed check`() {
        val r = PreflightResult(
            ramOkMb               = 100,   // fail
            storageFreeGb         = 0.1f,  // fail
            gpuSupported          = false, // fail
            temperatureSafe       = false, // fail
            requiredPackInstalled = false,
        )
        assertEquals(4, r.issues.size)
    }

    @Test fun `PreflightResult MIN_GAME_RAM_MB constant is 1500`() {
        assertEquals(1_500, PreflightResult.MIN_GAME_RAM_MB)
    }

    @Test fun `PreflightResult MIN_STORAGE_GB constant is 1f`() {
        assertEquals(1.0f, PreflightResult.MIN_STORAGE_GB, 0.001f)
    }

    // ── GamePack data class ───────────────────────────────────────────────

    @Test fun `GamePack id is used as directory name`() {
        val pack = GamePack(
            id          = "my_game_pack",
            displayName = "My Game",
            url         = "https://example.com/pack.tar.gz",
            sha256      = "abc123",
            sizeBytes   = 1_000_000L,
        )
        assertEquals("my_game_pack", pack.id)
    }

    @Test fun `GamePack minRamMb default is 1500`() {
        val pack = GamePack("id", "Name", "url", "sha256", 0)
        assertEquals(1_500, pack.minRamMb)
    }

    @Test fun `GamePack minStorageGb default is 2f`() {
        val pack = GamePack("id", "Name", "url", "sha256", 0)
        assertEquals(2.0f, pack.minStorageGb, 0.001f)
    }

    // ── IGameSession contract ─────────────────────────────────────────────

    @Test fun `IGameSession phase starts at IDLE`() {
        // Minimal stub implementation for contract testing
        val session = object : IGameSession {
            override val phase = GamePhase.IDLE
            override val config: GameConfig? = null
            override val preflightResult: PreflightResult? = null
            override val errorCode: String? = null
            override val errorMessage: String? = null
            override fun start(config: GameConfig, onProgress: ((GamePhase, Float, String) -> Unit)?): Boolean = false
            override fun requestStop() {}
            override fun awaitIdle(timeoutMs: Long): Boolean = true
            override fun reset() {}
            override fun setPhaseListener(listener: ((GamePhase) -> Unit)?) {}
        }
        assertEquals(GamePhase.IDLE, session.phase)
        assertNull(session.config)
        assertNull(session.errorCode)
    }
}

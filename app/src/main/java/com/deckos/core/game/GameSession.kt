package com.deckos.core.game

import androidx.annotation.Keep

// ─────────────────────────────────────────────────────────────────────────────
// L-70 — DeckOS Game Session — State machine contracts
//
// Architecture philosophy (DroidDeck lesson applied):
//  • SessionPhase tracks the *coarse* lifecycle visible to UI.
//  • GameConfig encapsulates all mutable per-session settings so they never
//    bleed across sessions (DroidDeck issue: global SessionState singleton).
//  • IGameSession is the internal contract used by GameService; a Binder
//    proxy can wrap it later when we split into a :game module (L-71 note).
//  • No Steam/Proton/FEX references here — they live in GamePackManager (L-72).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Coarse lifecycle phases, modelled after DroidDeck's SessionPhase but
 * adapted to our lean Core architecture (no compositor phase, no Steam phase).
 *
 * Transition diagram:
 *
 *   IDLE ──► PREFLIGHT ──► ACQUIRING_PACK ──► SUSPENDING_DESKTOP
 *         ──► STARTING_GAME ──► RUNNING ──► STOPPING ──► IDLE
 *
 * Any phase can transition to FAILED, which then transitions to IDLE after
 * the error is acknowledged.
 */
enum class GamePhase {
    /** No game session active. Default state at boot. */
    IDLE,

    /** L-73: Running preflight checks (RAM, storage, GPU, temperature). */
    PREFLIGHT,

    /** L-72: Downloading/verifying a required game pack (lazy install). */
    ACQUIRING_PACK,

    /** L-74: Stopping Openbox/PCManFM/X11 compositor to free memory. */
    SUSPENDING_DESKTOP,

    /** L-75: Launching the game via the available runtime (native or FEX). */
    STARTING_GAME,

    /** L-75: Game is active and rendering. */
    RUNNING,

    /** L-78: Game exited or crashed; cleaning up mounts/sockets/processes. */
    STOPPING,

    /** L-78: Cleanup done, restoring the desktop session. */
    RESTORING_DESKTOP,

    /**
     * Terminal failure. [errorCode] and [errorMessage] carry details.
     * The session must be explicitly reset (via [IGameSession.reset]) before
     * another session can start.
     */
    FAILED,
}

/**
 * Preflight check result flags.
 * Use [PreflightResult.issues] to build a user-readable diagnostic message.
 */
data class PreflightResult(
    val ramOkMb: Int,
    val storageFreeGb: Float,
    val gpuSupported: Boolean,
    val temperatureSafe: Boolean,
    val requiredPackInstalled: Boolean,
) {
    val ok: Boolean
        get() = ramOkMb >= MIN_GAME_RAM_MB
            && storageFreeGb >= MIN_STORAGE_GB
            && gpuSupported
            && temperatureSafe

    /** Human-readable list of issues. Empty when [ok] == true. */
    val issues: List<String>
        get() = buildList {
            if (ramOkMb < MIN_GAME_RAM_MB)
                add("Not enough free RAM: ${ramOkMb}MB available, ${MIN_GAME_RAM_MB}MB required")
            if (storageFreeGb < MIN_STORAGE_GB)
                add("Not enough storage: ${"%.1f".format(storageFreeGb)}GB free, ${MIN_STORAGE_GB}GB required")
            if (!gpuSupported)
                add("GPU not supported: Adreno 730+ or Adreno 8xx required for Turnip/Vulkan")
            if (!temperatureSafe)
                add("Device too hot — cool down before launching a game")
        }

    companion object {
        const val MIN_GAME_RAM_MB = 1_500   // must have ≥1.5 GB free before game
        const val MIN_STORAGE_GB  = 1.0f    // at least 1 GB free in filesDir partition
    }
}

/**
 * Per-session configuration. Immutable after session starts.
 *
 * [runtimeHint] is advisory — GameService picks the actual runtime based on
 * what is installed. Possible hint values: "native", "fex", "proton".
 */
@Keep
data class GameConfig(
    /** Display name of the game (for overlay and notifications). */
    val gameName: String = "Unknown Game",

    /** Executable path relative to the game pack root. */
    val execPath: String = "",

    /** Extra args forwarded verbatim to the game process. */
    val execArgs: List<String> = emptyList(),

    /**
     * Desired runtime. "native" = ARM64 binary, "fex" = x86-64 via FEX,
     * "proton" = Windows x86/x86-64 via Proton ARM64.
     * Defaults to native — safest and fastest on S24 Ultra.
     */
    val runtimeHint: String = "native",

    /**
     * L-79: A/B resolution profile.
     * "full" = native display resolution (could be 3088×1440 on S24U).
     * "hd"   = 1920×1080 — recommended for most games.
     * "hd720"= 1280×720  — for entry-level devices (Tab A9+, API 28 with 4GB).
     */
    val resolutionProfile: ResolutionProfile = ResolutionProfile.HD,

    /** L-79: Optional FSR upscale ratio 0.5–1.0. 1.0 = no upscale. */
    val fsrRatio: Float = 1.0f,

    /** L-79: Target FPS cap. 0 = uncapped. */
    val fpsCap: Int = 0,

    /** L-77: Show performance overlay (FPS/temp/RAM). */
    val showOverlay: Boolean = true,
) {
    enum class ResolutionProfile(val width: Int, val height: Int) {
        FULL(0, 0),       // resolved at runtime from actual display
        HD(1920, 1080),
        HD720(1280, 720),
    }
}

/**
 * Internal contract between UI/MainActivity and GameService.
 * A future :game module can expose this as a Binder stub (AIDL L-70).
 *
 * All calls that block (start, stop) must be invoked from a background thread.
 * Callbacks (onPhaseChange, onProgress) are delivered on the calling thread
 * unless otherwise documented.
 */
interface IGameSession {

    /** Current phase — safe to read from any thread. */
    val phase: GamePhase

    /** Active config for the running session, or null when IDLE/FAILED. */
    val config: GameConfig?

    /** Last preflight result, available after PREFLIGHT phase. */
    val preflightResult: PreflightResult?

    /** Last error code string (e.g. "PREFLIGHT_RAM", "PACK_HASH_MISMATCH"). */
    val errorCode: String?

    /** Human-readable error description. */
    val errorMessage: String?

    /**
     * Start a new game session.
     *
     * @param config     Session configuration.
     * @param onProgress Callback (phase, progress 0–1, message).
     * @return true if the game started successfully, false on any error.
     */
    fun start(
        config: GameConfig,
        onProgress: ((GamePhase, Float, String) -> Unit)? = null,
    ): Boolean

    /** Request a graceful stop of the running session. Non-blocking. */
    fun requestStop()

    /**
     * Block until the session reaches IDLE or FAILED.
     * Must not be called from the main thread.
     */
    fun awaitIdle(timeoutMs: Long = 30_000L): Boolean

    /**
     * Reset a FAILED session back to IDLE.
     * No-op if phase is not FAILED.
     */
    fun reset()

    /**
     * Register a listener for phase transitions.
     * Replaces any previously registered listener.
     */
    fun setPhaseListener(listener: ((GamePhase) -> Unit)?)
}

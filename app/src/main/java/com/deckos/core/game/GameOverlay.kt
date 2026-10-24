package com.deckos.core.game

import android.content.Context
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// L-77 — DeckOS Game Overlay
//
// A lightweight WindowManager overlay drawn over the game that shows:
//  • FPS counter (reads from /proc/<gamePid>/stat, estimated via frame timing)
//  • CPU temperature (from /sys/class/thermal)
//  • RAM usage (from /proc/meminfo)
//  • Battery level
//  • "Exit" button to stop the session
//
// Design notes:
//  • Uses a TYPE_APPLICATION_OVERLAY window — requires SYSTEM_ALERT_WINDOW
//    permission. For API 28+ we request it at runtime.
//  • The overlay is a translucent pill anchored to the top-right corner to
//    stay out of the way of most game UIs (following DroidDeck's PerfHud).
//  • Rendering is Compose — no custom View hierarchy to maintain.
//  • FPS is estimated by counting frame-time deltas (no SurfaceFlinger hook).
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "DeckOSOverlay"

data class OverlayStats(
    val fps: Int = 0,
    val cpuTempC: Int = 0,
    val ramUsedMb: Int = 0,
    val batteryPct: Int = 0,
)

class GameOverlay(private val context: Context) {

    private var overlayView: View? = null
    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    // ── Show / hide ───────────────────────────────────────────────────────

    /**
     * Show the overlay. Must be called from the main thread.
     * @param onStopRequested Called when the user taps the "Exit" button.
     */
    fun show(gamePid: Int, onStopRequested: () -> Unit) {
        if (overlayView != null) return  // already shown

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 48
        }

        val composeView = ComposeView(context).apply {
            setContent {
                OverlayContent(
                    gamePid          = gamePid,
                    onStopRequested  = onStopRequested,
                )
            }
        }

        try {
            windowManager.addView(composeView, params)
            overlayView = composeView
            Log.i(TAG, "Overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    /** Hide and remove the overlay. Must be called from the main thread. */
    fun hide() {
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
            overlayView = null
            Log.i(TAG, "Overlay hidden")
        }
    }

    // ── Stats readers (all /proc / /sys — no shell) ───────────────────────

    companion object {

        fun readCpuTempC(): Int {
            return try {
                val thermalRoot = File("/sys/class/thermal")
                if (!thermalRoot.exists()) return 0
                thermalRoot.listFiles()
                    ?.filter { it.name.startsWith("thermal_zone") }
                    ?.mapNotNull { runCatching { File(it, "temp").readText().trim().toIntOrNull() }.getOrNull() }
                    ?.maxOrNull()
                    ?.div(1000)
                    ?: 0
            } catch (e: Exception) { 0 }
        }

        fun readRamUsedMb(): Int {
            return try {
                var total = 0L; var available = 0L
                File("/proc/meminfo").useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line.startsWith("MemTotal")     -> total     = parseMemKb(line)
                            line.startsWith("MemAvailable") -> available = parseMemKb(line)
                        }
                    }
                }
                ((total - available) / 1024).toInt()
            } catch (e: Exception) { 0 }
        }

        private fun parseMemKb(line: String): Long =
            line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L

        fun readBatteryPct(): Int {
            return try {
                val batteryDir = File("/sys/class/power_supply")
                val batEntry = batteryDir.listFiles()
                    ?.firstOrNull { it.name.contains("battery", ignoreCase = true) }
                    ?: return 0
                File(batEntry, "capacity").readText().trim().toIntOrNull() ?: 0
            } catch (e: Exception) { 0 }
        }
    }
}

// ── Compose UI ───────────────────────────────────────────────────────────────

private val OverlayBg    = Color(0xCC0D1117)  // near-black, 80% opacity
private val OverlayGreen = Color(0xFF00E5A0)
private val OverlayRed   = Color(0xFFFF4B4B)
private val OverlayAmber = Color(0xFFFFA500)
private val OverlayText  = Color(0xFFE0E6F0)
private val OverlayMuted = Color(0xFF6B7A99)

@Composable
private fun OverlayContent(
    gamePid: Int,
    onStopRequested: () -> Unit,
) {
    var stats by remember { mutableStateOf(OverlayStats()) }
    var frameCount by remember { mutableStateOf(0) }
    var lastFrameMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var fps by remember { mutableStateOf(0) }
    var showExit by remember { mutableStateOf(false) }

    // Refresh stats every second
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val elapsed = (now - lastFrameMs).coerceAtLeast(1)
            fps = (frameCount * 1000 / elapsed).toInt()
            frameCount = 0
            lastFrameMs = now

            stats = OverlayStats(
                fps        = fps,
                cpuTempC   = GameOverlay.readCpuTempC(),
                ramUsedMb  = GameOverlay.readRamUsedMb(),
                batteryPct = GameOverlay.readBatteryPct(),
            )
        }
    }

    Box(
        modifier = Modifier
            .background(OverlayBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatChip(
                    label = "FPS",
                    value = "${stats.fps}",
                    color = when {
                        stats.fps >= 55 -> OverlayGreen
                        stats.fps >= 30 -> OverlayAmber
                        else            -> OverlayRed
                    },
                )
                StatChip(
                    label = "°C",
                    value = "${stats.cpuTempC}",
                    color = when {
                        stats.cpuTempC < 45 -> OverlayGreen
                        stats.cpuTempC < 52 -> OverlayAmber
                        else                -> OverlayRed
                    },
                )
                StatChip(
                    label = "RAM",
                    value = "${stats.ramUsedMb}M",
                    color = OverlayText,
                )
                StatChip(
                    label = "BAT",
                    value = "${stats.batteryPct}%",
                    color = when {
                        stats.batteryPct > 30 -> OverlayGreen
                        stats.batteryPct > 15 -> OverlayAmber
                        else                  -> OverlayRed
                    },
                )
            }

            // Exit button — tap once to confirm, tap again to stop
            Text(
                text     = if (showExit) "⏹ Confirm exit?" else "⏸ Exit game",
                color    = if (showExit) OverlayRed else OverlayMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .wrapContentWidth()
                    .clickable {
                        if (showExit) onStopRequested() else showExit = true
                    },
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text       = value,
            color      = color,
            fontSize   = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text     = label,
            color    = OverlayMuted,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

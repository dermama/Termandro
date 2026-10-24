package com.droiddeck.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.privileged.PrivilegedBridge
import com.droiddeck.launcher.core.privileged.PrivilegedMode
import com.droiddeck.launcher.core.privileged.PrivilegedStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
//  PrivilegedEnginePage  –  full settings page
// ─────────────────────────────────────────────────────────────

@Composable
fun PrivilegedEnginePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by PrivilegedBridge.statusFlow.collectAsState()
    var refreshing by remember { mutableStateOf(false) }

    // Auto-refresh every 4 seconds in background
    LaunchedEffect(Unit) {
        while (isActive) {
            withContext(Dispatchers.IO) { PrivilegedBridge.refresh() }
            delay(4_000)
        }
    }

    SettingsPage(
        host = rememberMenuHost(),
        title = "Privileged Engine",
        eyebrow = "System Access & Security",
        onBack = onBack,
        scrollContent = true,
    ) {
        // ── Header status banner ────────────────────────────────────
        StatusBanner(status, refreshing) {
            refreshing = true
            withContext(Dispatchers.IO) { PrivilegedBridge.refresh() }
            refreshing = false
        }
        Spacer(Modifier.height(12.dp))

        // ── What is this? ─────────────────────────────────────────
        SettingsGroup("What is Privileged Engine?") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EngineFeatureRow(
                    Icons.Outlined.Block,
                    "Phantom Process Killer Bypass",
                    "Prevents Android from killing Steam/Proton mid-game. Raises the process limit from 32 to unlimited.",
                )
                EngineFeatureRow(
                    Icons.Outlined.Speed,
                    "Game Performance Boost",
                    "Sets game processes to highest CPU priority (renice -20) and protects them from the low-memory killer.",
                )
                EngineFeatureRow(
                    Icons.Outlined.Security,
                    "Automatic Permission Grant",
                    "Grants DroidDeck WRITE_SECURE_SETTINGS, DUMP, and PACKAGE_USAGE_STATS at runtime.",
                )
                EngineFeatureRow(
                    Icons.Outlined.Bolt,
                    "Fixed Performance Mode",
                    "Locks the SoC into maximum-performance state during gaming sessions.",
                )
            }
        }

        // ── Embedded Daemon section ─────────────────────────────────
        SettingsGroup("Embedded Daemon (Recommended)") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The daemon is a tiny server bundled inside DroidDeck's APK that runs under " +
                    "ADB shell (UID 2000). Activate it once with one command — it then works " +
                    "automatically every time DroidDeck starts, with no external apps needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.mode == PrivilegedMode.EMBEDDED_DAEMON) {
                    DaemonActiveCard(status)
                } else {
                    DaemonActivationCard(ctx)
                }
            }
        }

        // ── Shizuku section ─────────────────────────────────────────
        SettingsGroup("Shizuku Integration") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "If you have Shizuku installed and running, DroidDeck connects to it " +
                    "automatically — no extra setup needed. Shizuku provides equivalent " +
                    "privileged access via its own secure IPC channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ShizukuStatusRow(status)
                Text(
                    "Note: The Embedded Daemon above is preferred and takes priority over Shizuku " +
                    "if both are active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        // ── Quick Actions ────────────────────────────────────────────
        if (status.isActive) {
            SettingsGroup("Quick Actions") {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionButton(
                        Icons.Outlined.Block, "Disable Phantom Limiter Now",
                        "Apply max_phantom_processes=2147483647 immediately",
                    ) {
                        withContext(Dispatchers.IO) { PrivilegedBridge.disablePhantomProcesses() }
                        Toast.makeText(ctx, "Phantom process limit disabled", Toast.LENGTH_SHORT).show()
                    }
                    QuickActionButton(
                        Icons.Outlined.VpnKey, "Grant DroidDeck Permissions",
                        "WRITE_SECURE_SETTINGS, DUMP, PACKAGE_USAGE_STATS",
                    ) {
                        withContext(Dispatchers.IO) { PrivilegedBridge.grantDroidDeckPermissions(ctx) }
                        Toast.makeText(ctx, "Permissions granted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ── Technical Details ────────────────────────────────────────
        var showDetails by remember { mutableStateOf(false) }
        SettingsGroup("Technical Details") {
            Row(
                Modifier.fillMaxWidth().clickable { showDetails = !showDetails }.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced information", style = MaterialTheme.typography.bodyMedium)
                Text(if (showDetails) "▲" else "▼", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(showDetails,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TechRow("Active mode", status.shortLabel)
                    if (status.daemonPid > 0) TechRow("Daemon PID", "${status.daemonPid}")
                    if (status.daemonUid > 0) TechRow("Daemon UID", "${status.daemonUid}")
                    if (status.shizukuVersion > 0) TechRow("Shizuku API version", "${status.shizukuVersion}")
                    TechRow("Daemon port", "127.0.0.1:28751")
                    TechRow("Protocol", "Base64-encoded TCP loopback")
                    TechRow("ADB UID", "2000 (shell)")
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Sub-composables
// ─────────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(status: PrivilegedStatus, refreshing: Boolean, onRefresh: suspend () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val good = status.isActive
    val animColor by animateColorAsState(if (good) pal.good else MaterialTheme.colorScheme.error, label = "bannerColor")
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(animColor.copy(alpha = 0.13f))
            .border(1.dp, animColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(animColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = animColor)
                } else {
                    Text(status.statusIcon, fontSize = 20.sp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (good) "Active — ${status.modeLabel}" else "Privileged Engine Inactive",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, color = colors.onBackground,
                )
                Text(
                    if (good) "Full system access granted · phantom limit disabled"
                    else "One ADB command away from full activation",
                    fontSize = 12.sp, color = colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = { scope.launch { onRefresh() } }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = animColor)
            }
        }
    }
}

@Composable
private fun DaemonActiveCard(status: PrivilegedStatus) {
    val pal = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(pal.good.copy(alpha = 0.1f))
            .border(1.dp, pal.good.copy(alpha = 0.3f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = pal.good, modifier = Modifier.size(18.dp))
            Text("Daemon Running", fontWeight = FontWeight.SemiBold, color = pal.good)
        }
        Text("PID ${status.daemonPid}  ·  UID ${status.daemonUid}  ·  port 28751",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Phantom process limit disabled  ·  Auto-reapplied on every session",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun isDevOptionsOn(ctx: Context): Boolean = runCatching {
    android.provider.Settings.Global.getInt(
        ctx.contentResolver,
        android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        0
    ) != 0
}.getOrDefault(false)

private fun isWirelessAdbOn(ctx: Context): Boolean = runCatching {
    android.provider.Settings.Global.getInt(
        ctx.contentResolver,
        "adb_wifi_enabled",
        0
    ) != 0
}.getOrDefault(false)

private fun openDevOptions(ctx: Context) {
    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
    }
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        try {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}

private fun openAboutPhone(ctx: Context) {
    val intent = Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        try {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}

@Composable
private fun DaemonActivationCard(ctx: Context) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val cmd = PrivilegedBridge.getStarterAdbCommand(ctx)
    val scope = rememberCoroutineScope()
    var startingAdb by remember { mutableStateOf(false) }

    val devOptionsOn = remember { mutableStateOf(isDevOptionsOn(ctx)) }
    val wirelessAdbOn = remember { mutableStateOf(isWirelessAdbOn(ctx)) }

    // Re-check settings state periodically
    LaunchedEffect(Unit) {
        while (isActive) {
            devOptionsOn.value = isDevOptionsOn(ctx)
            wirelessAdbOn.value = isWirelessAdbOn(ctx)
            delay(1_500)
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surface)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Smart Setup Assistant (Like Shizuku):", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)

        // ── Step 1: Developer Options ──
        if (!devOptionsOn.value) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.error.copy(alpha = 0.10f))
                    .border(1.dp, colors.error.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("⚠️ Step 1: Enable Developer Options", fontWeight = FontWeight.Bold, color = colors.error, fontSize = 13.sp)
                Text("Developer options are not enabled yet. Tap below to go directly to About Phone -> Software information, then tap 'Build number' 7 times.", fontSize = 12.sp, color = colors.onBackground)
                PrimaryButton("Open About Phone (Step 1)", compact = true) { openAboutPhone(ctx) }
            }
        } else if (!wirelessAdbOn.value) {
            // ── Step 2: Wireless Debugging Toggle ──
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.tertiary.copy(alpha = 0.12f))
                    .border(1.dp, colors.tertiary.copy(alpha = 0.40f), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("⚠️ Step 2: Turn ON Wireless Debugging", fontWeight = FontWeight.Bold, color = colors.onBackground, fontSize = 13.sp)
                Text("Developer options are enabled! Now tap below to open Developer options and switch ON 'Wireless debugging'.", fontSize = 12.sp, color = colors.onSurfaceVariant)
                PrimaryButton("Open Developer Options & Turn ON", compact = true) { openDevOptions(ctx) }
            }
        } else {
            // ── Step 3: Ready to Start ──
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(pal.good.copy(alpha = 0.10f))
                    .border(1.dp, pal.good.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("✅ Wireless Debugging is ON & Ready", fontWeight = FontWeight.Bold, color = pal.good, fontSize = 13.sp)
                Text("Tap below to activate full system privileges with one touch:", fontSize = 12.sp, color = colors.onSurfaceVariant)
                PrimaryButton(if (startingAdb) "Activating..." else "Start via Wireless ADB (One-Tap)", compact = true, enabled = !startingAdb) {
                    scope.launch {
                        startingAdb = true
                        val ok = withContext(Dispatchers.IO) {
                            com.droiddeck.launcher.core.WirelessAdbFix.startPrivilegedDaemonWithSavedPairing(ctx)
                        }
                        if (ok) {
                            withContext(Dispatchers.IO) { PrivilegedBridge.refresh() }
                            Toast.makeText(ctx, "Privileged Daemon started successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, "Pair Wireless Debugging once in Developer Options.", Toast.LENGTH_LONG).show()
                        }
                        startingAdb = false
                    }
                }
            }
        }

        // ── Reboot Explanation ──
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant.copy(alpha = 0.40f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("ℹ️ What happens when the phone restarts?", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = colors.onBackground)
            Text(
                "When you reboot your phone, Android resets running background services. However, your pairing remains saved! " +
                "After restarting, simply ensure Wireless Debugging is on and open DroidDeck — it automatically reconnects and restores all privileges.",
                fontSize = 11.sp, color = colors.onSurfaceVariant,
            )
        }

        // ── Manual Fallback ──
        Text("Alternative — Manual Command via PC / Terminal:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant).padding(8.dp),
        ) {
            Text(cmd, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, overflow = TextOverflow.Visible)
        }
        SecondaryButton("Copy ADB Command", compact = true) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("DroidDeck Daemon Command", cmd))
            Toast.makeText(ctx, "Command copied — paste in ADB shell", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun ShizukuStatusRow(status: PrivilegedStatus) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val shizActive = status.mode == PrivilegedMode.SHIZUKU_APP
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (shizActive) pal.good.copy(0.1f) else colors.surfaceVariant.copy(0.5f))
            .border(1.dp, if (shizActive) pal.good.copy(0.3f) else colors.outlineVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (shizActive) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
            contentDescription = null,
            tint = if (shizActive) pal.good else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (shizActive) "Shizuku connected (API v${status.shizukuVersion})" else "Shizuku not detected",
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = colors.onBackground,
            )
            Text(
                if (shizActive) "DroidDeck is using Shizuku for privileged commands"
                else "Install Shizuku from Play Store as an alternative to the Embedded Daemon",
                fontSize = 12.sp, color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EngineFeatureRow(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickActionButton(icon: ImageVector, title: String, subtitle: String, action: suspend () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant.copy(0.4f))
            .clickable(enabled = !running) { scope.launch { running = true; action(); running = false } }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = colors.onBackground)
            Text(subtitle, fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant,
            modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TechRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground)
    }
}

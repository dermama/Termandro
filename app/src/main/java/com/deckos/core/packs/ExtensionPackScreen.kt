package com.deckos.core.packs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deckos.core.ui.theme.DeckBackground
import com.deckos.core.ui.theme.DeckCyan
import com.deckos.core.ui.theme.DeckMuted
import com.deckos.core.ui.theme.DeckOutline
import com.deckos.core.ui.theme.DeckSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExtensionPackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ExtensionPackManager(context) }

    // State map: packId -> PackStatus
    val statusMap = remember { mutableStateMapOf<String, PackStatus>() }

    // Initialize statuses from disk
    LaunchedEffect(Unit) {
        ExtensionPackCatalog.all().forEach { pack ->
            statusMap[pack.id] = if (manager.isInstalled(pack)) PackStatus.Installed
                                 else PackStatus.NotInstalled
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeckBackground)
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onBack) {
                Text("←", style = MaterialTheme.typography.titleLarge, color = DeckCyan)
            }
            Column {
                Text(
                    "Extension Packs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = DeckCyan,
                )
                Text(
                    "Download optional capabilities on demand",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckMuted,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Category legend
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PackCategory.entries.forEach { cat ->
                Surface(
                    color = categoryColor(cat).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = cat.label(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = categoryColor(cat),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ExtensionPackCatalog.all(), key = { it.id }) { pack ->
                val status = statusMap[pack.id] ?: PackStatus.NotInstalled
                PackCard(
                    pack = pack,
                    status = status,
                    onInstall = {
                        scope.launch(Dispatchers.IO) {
                            manager.install(pack) { s -> statusMap[pack.id] = s }
                        }
                    },
                    onRemove = {
                        scope.launch(Dispatchers.IO) {
                            manager.remove(pack)
                            statusMap[pack.id] = PackStatus.NotInstalled
                        }
                    },
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun PackCard(
    pack: ExtensionPack,
    status: PackStatus,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DeckOutline, RoundedCornerShape(20.dp)),
        color = DeckSurface,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Title row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = when (status) {
                                is PackStatus.Installed -> DeckCyan
                                is PackStatus.Failed -> Color(0xFFEF5350)
                                else -> Color(0xFF3A4A5E)
                            },
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = pack.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // Category badge
                Surface(
                    color = categoryColor(pack.category).copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = pack.category.label(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = categoryColor(pack.category),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                text = pack.description,
                style = MaterialTheme.typography.bodySmall,
                color = DeckMuted,
            )

            // Size / requirements row
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetaChip("💾 ${formatSize(pack.sizeBytes)}")
                MetaChip("🧠 ${pack.minRamMb} MB RAM")
                MetaChip("📦 ${pack.minStorageGb} GB")
            }

            // Status / progress area
            AnimatedContent(targetState = status, label = "status") { s ->
                when (s) {
                    is PackStatus.Downloading -> {
                        val prog by animateFloatAsState(s.progress, tween(300), label = "dl")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = s.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = DeckMuted,
                            )
                            LinearProgressIndicator(
                                progress = { prog },
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(6.dp),
                                color = DeckCyan,
                                trackColor = DeckOutline,
                            )
                        }
                    }
                    is PackStatus.Verifying -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = DeckCyan, strokeWidth = 2.dp)
                            Text("Verifying checksum…", style = MaterialTheme.typography.labelSmall, color = DeckMuted)
                        }
                    }
                    is PackStatus.Extracting -> {
                        val prog by animateFloatAsState(s.progress, tween(200), label = "ext")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Extracting… ${(s.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = DeckMuted,
                            )
                            LinearProgressIndicator(
                                progress = { prog },
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(6.dp),
                                color = Color(0xFF66BB6A),
                                trackColor = DeckOutline,
                            )
                        }
                    }
                    is PackStatus.Installed -> {
                        Text(
                            "✓ Installed — ready to use",
                            style = MaterialTheme.typography.labelSmall,
                            color = DeckCyan,
                        )
                    }
                    is PackStatus.Failed -> {
                        Text(
                            "✗ ${s.reason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFEF5350),
                        )
                    }
                    else -> { /* NotInstalled — no extra indicator */ }
                }
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    is PackStatus.Installed -> {
                        OutlinedButton(
                            onClick = onRemove,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text("Remove", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    is PackStatus.Downloading, is PackStatus.Verifying, is PackStatus.Extracting -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            colors = ButtonDefaults.buttonColors(containerColor = DeckOutline, contentColor = DeckMuted),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = DeckCyan, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Installing…", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    else -> {
                        Button(
                            onClick = onInstall,
                            colors = ButtonDefaults.buttonColors(containerColor = DeckCyan, contentColor = DeckBackground),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                if (status is PackStatus.Failed) "Retry" else "Install",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaChip(label: String) {
    Surface(
        color = Color(0xFF0E1520),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, DeckOutline),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = DeckMuted,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024 * 1024)
    return if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
}

private fun PackCategory.label(): String = when (this) {
    PackCategory.GAME -> "🎮 Gaming"
    PackCategory.LINUX_DISTRO -> "🐧 Linux"
    PackCategory.DEVELOPMENT -> "🛠️ Dev"
    PackCategory.SYSTEM -> "⚙️ System"
}

private fun categoryColor(cat: PackCategory): Color = when (cat) {
    PackCategory.GAME -> Color(0xFF7C4DFF)
    PackCategory.LINUX_DISTRO -> Color(0xFF26C6DA)
    PackCategory.DEVELOPMENT -> Color(0xFF66BB6A)
    PackCategory.SYSTEM -> Color(0xFFFFA726)
}

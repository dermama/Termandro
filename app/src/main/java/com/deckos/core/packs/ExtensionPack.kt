package com.deckos.core.packs

enum class PackCategory {
    GAME,
    LINUX_DISTRO,
    DEVELOPMENT,
    SYSTEM,
}

sealed class PackStatus {
    object NotInstalled : PackStatus()
    data class Downloading(val progress: Float, val message: String) : PackStatus()
    object Verifying : PackStatus()
    data class Extracting(val progress: Float) : PackStatus()
    object Installed : PackStatus()
    data class Failed(val reason: String) : PackStatus()
}

/**
 * Descriptor for an installable extension pack in DeckOS.
 */
data class ExtensionPack(
    val id: String,
    val displayName: String,
    val category: PackCategory,
    val description: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val minRamMb: Int = 1_500,
    val minStorageGb: Float = 2.0f,
    val archiveFormat: String = "auto",
)

package com.deckos.core.packs

/**
 * Official Extension Pack Catalog for DeckOS.
 * Provides on-demand expansion packs without bloating the base APK.
 */
object ExtensionPackCatalog {

    /**
     * Steam & Proton Gaming Runtime (gamescope 3.16 + Steam Native ARM64 + Turnip KGSL).
     */
    val STEAM_PROTON = ExtensionPack(
        id = "steam_proton",
        displayName = "Steam & Proton Gaming Pack",
        category = PackCategory.GAME,
        description = "Valve Steam Native Client (ARM64), Proton-GE (Wine 9/10 + DXVK 2.x), FEX-Emu, and Mesa Turnip KGSL driver for Adreno GPUs.",
        url = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/linuxfs-r9/linuxfs.tar.zst",
        sha256 = "d3240c1531531615b0dd06b6ad56e92db9015a8641eb18c3a613835ae45992e7",
        sizeBytes = 790_907_606L,
        minRamMb = 2_000,
        minStorageGb = 3.5f,
        archiveFormat = "tar.zst",
    )

    /**
     * Ubuntu 24.04 (Noble Numbat) glibc rootfs with APT package manager.
     */
    val UBUNTU_ESSENTIALS = ExtensionPack(
        id = "ubuntu_essentials",
        displayName = "Ubuntu 24.04 LTS glibc Pack",
        category = PackCategory.LINUX_DISTRO,
        description = "Full Ubuntu 24.04 glibc environment with standard APT, system utilities, and Linux compatibility for development tools.",
        url = "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
        sha256 = "66f77ec945535359a37bc38b776c5b96969cf163013d39db707fc46c751c142c",
        sizeBytes = 32_800_000L,
        minRamMb = 1_000,
        minStorageGb = 1.0f,
        archiveFormat = "tar.xz",
    )

    /**
     * Developer Tooling Pack (Python 3, Node.js, Git, build tools).
     */
    val DEV_PACK = ExtensionPack(
        id = "dev_pack",
        displayName = "Developer Tools Pack",
        category = PackCategory.DEVELOPMENT,
        description = "Python 3 runtime, Node.js, Git version control, Clang compiler, and developer utilities.",
        url = "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.10.22/bootstrap-aarch64.zip",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        sizeBytes = 15_000_000L,
        minRamMb = 800,
        minStorageGb = 0.5f,
        archiveFormat = "zip",
    )

    /**
     * Phone Privileged Integration Pack (Shizuku + ADB local daemon).
     */
    val PHONE_PACK = ExtensionPack(
        id = "phone_privileged",
        displayName = "Phone Privileged & Shizuku Pack",
        category = PackCategory.SYSTEM,
        description = "Embedded Privileged Daemon (UID 2000 / Shell), Shizuku bridge, and performance tuning capabilities.",
        url = "",
        sha256 = "",
        sizeBytes = 0L,
        minRamMb = 500,
        minStorageGb = 0.1f,
        archiveFormat = "builtin",
    )

    val ALL_PACKS = listOf(
        STEAM_PROTON,
        UBUNTU_ESSENTIALS,
        DEV_PACK,
        PHONE_PACK,
    )

    fun getPackById(id: String): ExtensionPack? = ALL_PACKS.firstOrNull { it.id == id }

    fun getPacksByCategory(category: PackCategory): List<ExtensionPack> =
        ALL_PACKS.filter { it.category == category }
}

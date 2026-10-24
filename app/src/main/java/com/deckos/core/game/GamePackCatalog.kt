package com.deckos.core.game

/**
 * L-72 / L-93: Official Game Pack Catalog for DeckOS.
 *
 * Keeps base APK ultra-lean while offering full DroidDeck gaming parity:
 *  - Valve Steam Native Client (ARM64)
 *  - Proton-GE (Wine 9/10 + DXVK 2.x + VKD3D + FAudio)
 *  - FEX-Emu (x86_64 -> ARM64 binary translator)
 *  - Mesa 26.2+ Turnip KGSL driver (Adreno 750 direct Vulkan)
 */
object GamePackCatalog {

    /**
     * DroidDeck Steam & Proton Linux runtime (gamescope 3.16 + Steam Native ARM64 + Turnip KGSL).
     * Sourced from audited DroidDeck-Components release linuxfs-r9.
     */
    val STEAM_PROTON_RUNTIME = GamePack(
        id = "steam_proton",
        displayName = "Steam & Proton Gaming Runtime (Turnip/FEX)",
        url = "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/linuxfs-r9/linuxfs.tar.zst",
        sha256 = "d3240c1531531615b0dd06b6ad56e92db9015a8641eb18c3a613835ae45992e7",
        sizeBytes = 790_907_606L,
        minRamMb = 2_000,
        minStorageGb = 3.5f,
    )

    val ALL_PACKS = listOf(STEAM_PROTON_RUNTIME)

    fun resolvePackFor(runtimeHint: String, execPath: String = ""): GamePack? {
        return when {
            runtimeHint.equals("proton", ignoreCase = true) -> STEAM_PROTON_RUNTIME
            runtimeHint.equals("steam", ignoreCase = true) -> STEAM_PROTON_RUNTIME
            runtimeHint.equals("fex", ignoreCase = true) -> STEAM_PROTON_RUNTIME
            execPath.endsWith(".exe", ignoreCase = true) -> STEAM_PROTON_RUNTIME
            execPath.equals("steam", ignoreCase = true) -> STEAM_PROTON_RUNTIME
            else -> null
        }
    }
}

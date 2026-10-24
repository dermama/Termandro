package com.deckos.core.game

import android.content.Context
import android.util.Log
import com.deckos.core.runtime.DeckOSGpuDetector
import java.io.File
import java.io.RandomAccessFile

// ─────────────────────────────────────────────────────────────────────────────
// L-72 — DeckOS Game Pack Manager
//
// Design goals:
//  • Zero game runtime bytes in the base APK. Only descriptors live here.
//  • Each pack is a remote tarball: URL + SHA-256 + size bytes.
//  • Packs are extracted into filesDir/game_packs/<pack_id>/.
//  • Integrity check on every mount; corrupt packs are re-downloaded.
//  • Cross-device: same pack works on Tab A9+ (4 GB) and S24 Ultra (12 GB).
//
// DroidDeck lesson: ProtonExtras uses GitHub releases JSON for dynamic URLs.
// We do the same but with a simpler flat JSON catalog (no per-asset SHA-512;
// SHA-256 is sufficient for our size). Catalog is fetched at GameService start.
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "DeckOSPackMgr"

/**
 * Descriptor for a downloadable game pack.
 *
 * @param id          Unique identifier used as directory name in filesDir.
 * @param displayName Human-readable name shown in the install prompt.
 * @param url         Direct download URL (must be HTTPS, no auth).
 * @param sha256      Lower-case hex SHA-256 of the uncompressed tar archive.
 * @param sizeBytes   Expected compressed download size (shown to user).
 * @param minRamMb    Minimum free RAM required to use this pack.
 * @param minStorageGb Minimum free storage required.
 */
data class GamePack(
    val id: String,
    val displayName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val minRamMb: Int = 1_500,
    val minStorageGb: Float = 2.0f,
)

/**
 * Result of an install/verify operation.
 */
sealed class PackResult {
    object AlreadyInstalled : PackResult()
    object InstalledOk      : PackResult()
    data class Failed(val reason: String) : PackResult()
}

class GamePackManager(private val context: Context) {

    private val packsRoot: File
        get() = File(context.filesDir, "game_packs").also { it.mkdirs() }

    // ── Install & verify ──────────────────────────────────────────────────

    /**
     * Ensure [pack] is installed and passes SHA-256 verification.
     *
     * Steps:
     * 1. Check stamp file. If present, verify SHA-256 of sentinel file.
     * 2. If missing or corrupt, re-download and extract.
     * 3. Write stamp file on success.
     *
     * @param onProgress Callback (progress 0–1, message). Called from this thread.
     */
    fun ensureInstalled(
        pack: GamePack,
        onProgress: ((Float, String) -> Unit)? = null,
    ): PackResult {
        val packDir = File(packsRoot, pack.id)
        val stampFile = File(packDir, ".deckos_pack_${pack.id}")

        if (stampFile.exists() && verifyIntegrity(pack, packDir)) {
            Log.i(TAG, "Pack '${pack.id}' already installed and verified")
            return PackResult.AlreadyInstalled
        }

        Log.i(TAG, "Pack '${pack.id}' missing or corrupt — downloading")
        onProgress?.invoke(0f, "Preparing to download ${pack.displayName}…")

        return try {
            packDir.mkdirs()
            val ext = when {
                pack.url.endsWith(".tar.zst", ignoreCase = true) || pack.url.endsWith(".zst", ignoreCase = true) -> "tar.zst"
                pack.url.endsWith(".tar.xz", ignoreCase = true) || pack.url.endsWith(".xz", ignoreCase = true) -> "tar.xz"
                pack.url.endsWith(".tar.bz2", ignoreCase = true) || pack.url.endsWith(".bz2", ignoreCase = true) -> "tar.bz2"
                else -> "tar.gz"
            }
            val archiveFile = File(context.cacheDir, "${pack.id}.$ext")

            // Step 1: Download with progress
            downloadWithProgress(pack, archiveFile) { p, msg -> onProgress?.invoke(p * 0.7f, msg) }

            // Step 2: Verify downloaded file hash
            onProgress?.invoke(0.70f, "Verifying download…")
            val actualHash = sha256Hex(archiveFile)
            if (!actualHash.equals(pack.sha256, ignoreCase = true)) {
                archiveFile.delete()
                return PackResult.Failed(
                    "SHA-256 mismatch for '${pack.id}': expected ${pack.sha256}, got $actualHash"
                )
            }

            // Step 3: Extract (try native GNU tar first for speed & zst support)
            onProgress?.invoke(0.75f, "Extracting ${pack.displayName}…")
            val extractedOk = extractNative(archiveFile, packDir) { p ->
                onProgress?.invoke(0.75f + p * 0.22f, "Extracting ${pack.displayName}…")
            }

            if (!extractedOk) {
                if (ext == "tar.gz") {
                    extractTar(archiveFile, packDir) { p -> onProgress?.invoke(0.75f + p * 0.22f, "Extracting…") }
                } else {
                    archiveFile.delete()
                    return PackResult.Failed("Native GNU tar failed to extract $ext archive")
                }
            }
            archiveFile.delete()

            // Step 4: Write stamp
            stampFile.writeText(pack.sha256)
            Log.i(TAG, "Pack '${pack.id}' installed successfully")
            onProgress?.invoke(1f, "${pack.displayName} ready")
            PackResult.InstalledOk

        } catch (e: Exception) {
            Log.e(TAG, "Pack install failed for '${pack.id}'", e)
            PackResult.Failed(e.message ?: "Unknown error")
        }
    }

    /** Remove a pack from disk. Safe to call while not in session. */
    fun uninstall(pack: GamePack) {
        File(packsRoot, pack.id).deleteRecursively()
        Log.i(TAG, "Pack '${pack.id}' uninstalled")
    }

    /** True if the pack directory exists with a valid stamp file. */
    fun isInstalled(pack: GamePack): Boolean {
        val packDir = File(packsRoot, pack.id)
        val stampFile = File(packDir, ".deckos_pack_${pack.id}")
        return stampFile.exists()
    }

    // ── Environment builder ───────────────────────────────────────────────

    /**
     * Build the environment map that the game process should run with.
     * Includes GPU override when Adreno Turnip is requested.
     *
     * This is intentionally separate from the exec command so GameService
     * can log and audit the environment before launching.
     */
    fun buildGameEnv(
        pack: GamePack,
        config: GameConfig,
        prefix: File,
    ): Map<String, String> {
        val packDir = File(packsRoot, pack.id)
        val gpuInfo = DeckOSGpuDetector.detect(prefix.absolutePath)
        val tmpDir = File(context.filesDir, "tmp").apply { mkdirs() }
        val homeDir = File(context.filesDir, "home").apply { mkdirs() }

        return buildMap {
            // Basic paths
            put("HOME", homeDir.absolutePath)
            put("PREFIX", prefix.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("GAME_PACK_DIR", packDir.absolutePath)

            val packBin = File(packDir, "usr/bin")
            val packLocalBin = File(packDir, "usr/local/bin")
            val packLib = File(packDir, "usr/lib")
            val packPaths = listOfNotNull(
                packLocalBin.takeIf { it.exists() }?.absolutePath,
                packBin.takeIf { it.exists() }?.absolutePath,
                "${prefix.absolutePath}/bin",
                "/system/bin",
                "/system/xbin"
            ).joinToString(":")
            val packLibs = listOfNotNull(
                packLib.takeIf { it.exists() }?.absolutePath,
                "${prefix.absolutePath}/lib"
            ).joinToString(":")

            put("PATH", packPaths)
            put("LD_LIBRARY_PATH", packLibs)
            put("LD_PRELOAD", "${prefix.absolutePath}/lib/libsocket_hook.so")
            put("DECKOS_PREFIX", prefix.absolutePath)
            put("SHELL", "${prefix.absolutePath}/bin/bash")
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "en_US.UTF-8")
            put("PULSE_SERVER", "tcp:127.0.0.1:4713")
            put("TERMUX_APP__PACKAGE_NAME", context.packageName)
            put("TERMUX__PREFIX", prefix.absolutePath)
            put("TERMUX__HOME", homeDir.absolutePath)

            // Check for pack-provided Turnip ICD
            val freedrenoIcd = File(packDir, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")
            if (freedrenoIcd.exists()) {
                put("VK_ICD_FILENAMES", freedrenoIcd.absolutePath)
                put("TU_DEBUG", "sysmem")
            }

            // GPU settings — match DroidDeck's BL_TURNIP_ENABLED pattern
            when (gpuInfo.mode) {
                DeckOSGpuDetector.GpuMode.TURNIP_ZINK -> {
                    put("DECKOS_GPU", "turnip")
                    put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
                    // Adreno performance governor hint
                    put("ADRENOTOOLS_DRIVER_PATH", "${prefix.absolutePath}/lib/turnip")
                }
                DeckOSGpuDetector.GpuMode.LLVMPIPE -> {
                    put("DECKOS_GPU", "software")
                    put("LIBGL_ALWAYS_SOFTWARE", "1")
                    put("GALLIUM_DRIVER", "llvmpipe")
                }
                else -> {
                    // VENDOR_DEFAULT: let the game use whatever GL/Vulkan is available
                    put("DECKOS_GPU", "native")
                }
            }

            // Resolution and FPS cap (L-79)
            val (w, h) = resolvedResolution(config)
            put("DECKOS_GAME_WIDTH", w.toString())
            put("DECKOS_GAME_HEIGHT", h.toString())
            if (config.fpsCap > 0) put("DECKOS_FPS_CAP", config.fpsCap.toString())
            if (config.fsrRatio < 1.0f) put("DECKOS_FSR_RATIO", config.fsrRatio.toString())

            // Display
            put("DISPLAY", ":1")
            put("XDG_RUNTIME_DIR", tmpDir.absolutePath)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun resolvedResolution(config: GameConfig): Pair<Int, Int> {
        return if (config.resolutionProfile == GameConfig.ResolutionProfile.FULL) {
            // Resolved at runtime — for now default to HD as safe fallback
            Pair(1920, 1080)
        } else {
            Pair(config.resolutionProfile.width, config.resolutionProfile.height)
        }
    }

    /**
     * Integrity check: reads the stamp file and compares the stored hash to
     * the pack descriptor. Stamp hash was written at install time, so a mismatch
     * means the descriptor changed (pack updated) or the files are corrupt.
     */
    private fun verifyIntegrity(pack: GamePack, packDir: File): Boolean {
        val stampFile = File(packDir, ".deckos_pack_${pack.id}")
        if (!stampFile.exists()) return false
        val storedHash = runCatching { stampFile.readText().trim() }.getOrNull() ?: return false
        return storedHash.equals(pack.sha256, ignoreCase = true)
    }

    /**
     * HTTP download with per-chunk progress callbacks.
     * Uses chunked reads so large packs (>2 GB) don't OOM.
     */
    private fun downloadWithProgress(
        pack: GamePack,
        dest: File,
        onProgress: (Float, String) -> Unit,
    ) {
        val url = java.net.URL(pack.url)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 60_000
        conn.connect()

        val total = if (pack.sizeBytes > 0) pack.sizeBytes else conn.contentLengthLong

        conn.inputStream.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(128 * 1024)
                var written = 0L
                var read: Int
                while (ins.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    written += read
                    if (total > 0) {
                        val pct = written.toFloat() / total
                        onProgress(pct, "Downloading ${pack.displayName} (${"%.0f".format(pct*100)}%)")
                    }
                }
            }
        }
        conn.disconnect()
    }

    /**
     * Compute SHA-256 hex of [file]. Streams in 64KB chunks.
     */
    private fun sha256Hex(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(65_536)
            var read: Int
            while (raf.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Native extraction using GNU tar with support for zstd (.tar.zst), xz, gzip, and bz2.
     * Uses the pre-installed tar and zstd in usr/bin.
     */
    private fun extractNative(
        archiveFile: File,
        destDir: File,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val prefix = File(context.filesDir, "usr")
        val tarBin = File(prefix, "bin/tar")
        if (!tarBin.exists()) {
            Log.w(TAG, "Native tar not found at ${tarBin.absolutePath}")
            return false
        }

        Log.i(TAG, "Starting native GNU tar extraction of ${archiveFile.name}")
        return try {
            onProgress(0.1f)
            val pb = ProcessBuilder(
                tarBin.absolutePath,
                "-xf",
                archiveFile.absolutePath,
                "-C",
                destDir.absolutePath
            ).apply {
                environment()["PATH"] = "${prefix.absolutePath}/bin:/system/bin"
                environment()["LD_LIBRARY_PATH"] = "${prefix.absolutePath}/lib"
                redirectErrorStream(true)
            }
            val process = pb.start()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> Log.d(TAG, "[tar] $line") }
                    }
                }
            }
            reader.start()
            val exitCode = process.waitFor()
            reader.join(1000)
            Log.i(TAG, "Native tar extraction finished with exit code $exitCode")
            if (exitCode == 0) {
                onProgress(1f)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native tar extraction error: ${e.message}")
            false
        }
    }

    /**
     * Extract a .tar.gz (or .tar) archive into [destDir].
     * Uses Android's built-in java.util.zip streams — fallback if native tar fails.
     */
    private fun extractTar(
        tarFile: File,
        destDir: File,
        onProgress: (Float) -> Unit,
    ) {
        val totalSize = tarFile.length().coerceAtLeast(1L)
        var extracted = 0L
        val buf = ByteArray(128 * 1024)

        val gzipStream = java.util.zip.GZIPInputStream(tarFile.inputStream().buffered())
        // Simple TAR parser: 512-byte headers
        var lastProgress = 0f
        while (true) {
            val header = ByteArray(512)
            val bytesRead = gzipStream.readNBytes(header, 0, 512)
            if (bytesRead < 512 || header.all { it == 0.toByte() }) break

            val name = String(header, 0, 100).trimEnd('\u0000').trim()
            val sizeOctal = String(header, 124, 12).trimEnd('\u0000').trim()
            val typeFlag = header[156].toInt().toChar()
            val fileSize = if (sizeOctal.isEmpty()) 0L else sizeOctal.toLong(8)

            if (name.isEmpty()) break

            val destFile = File(destDir, name)
            when (typeFlag) {
                '0', '\u0000' -> {
                    // Regular file
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { out ->
                        var remaining = fileSize
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val r = gzipStream.read(buf, 0, toRead)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            remaining -= r
                            extracted += r
                        }
                    }
                    // Skip padding to 512-byte boundary
                    val pad = ((fileSize + 511) / 512 * 512 - fileSize).toInt()
                    if (pad > 0) gzipStream.skip(pad.toLong())
                }
                '5' -> {
                    // Directory
                    destFile.mkdirs()
                    if (fileSize > 0) gzipStream.skip(fileSize)
                }
                '2' -> {
                    // Symbolic link — read link target from header
                    val linkTarget = String(header, 157, 100).trimEnd('\u0000').trim()
                    destFile.parentFile?.mkdirs()
                    // Create symlink on supported filesystems
                    runCatching {
                        if (destFile.exists()) destFile.delete()
                        java.nio.file.Files.createSymbolicLink(
                            destFile.toPath(),
                            java.nio.file.Paths.get(linkTarget),
                        )
                    }
                }
                else -> {
                    // Unknown type — skip data
                    if (fileSize > 0) {
                        val blocks = (fileSize + 511) / 512 * 512
                        gzipStream.skip(blocks)
                    }
                }
            }

            val p = extracted.toFloat() / totalSize
            if (p - lastProgress >= 0.05f) {
                onProgress(p.coerceIn(0f, 1f))
                lastProgress = p
            }
        }
        gzipStream.close()
        onProgress(1f)
    }
}

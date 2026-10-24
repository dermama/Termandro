package com.deckos.core.packs

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "DeckOSPackMgr"

/** Suffix appended to in-progress download files to distinguish from complete archives. */
private const val PARTIAL_SUFFIX = ".partial"

class ExtensionPackManager(private val context: Context) {

    val packsDir: File
        get() = File(context.filesDir, "packs").also { it.mkdirs() }

    private val _packStatuses = MutableStateFlow<Map<String, PackStatus>>(emptyMap())
    val packStatuses: StateFlow<Map<String, PackStatus>> = _packStatuses.asStateFlow()

    init { refreshStatuses() }

    fun getPackDir(packId: String): File = File(packsDir, packId)

    fun isInstalled(packId: String): Boolean {
        if (packId == "phone_privileged") return true
        val packDir = File(packsDir, packId)
        val stampFile = File(packDir, ".deckos_pack_$packId")
        return stampFile.exists() && packDir.isDirectory
    }

    fun isInstalled(pack: ExtensionPack): Boolean = isInstalled(pack.id)

    fun remove(pack: ExtensionPack): Boolean = uninstall(pack.id)

    fun getStatus(packId: String): PackStatus {
        if (packId == "phone_privileged") return PackStatus.Installed
        return if (isInstalled(packId)) PackStatus.Installed else PackStatus.NotInstalled
    }

    fun refreshStatuses() {
        val map = ExtensionPackCatalog.ALL_PACKS.associate { it.id to getStatus(it.id) }
        _packStatuses.value = map
    }

    /**
     * Install a pack with a live [PackStatus] callback — call from IO coroutine.
     * Supports resuming interrupted downloads automatically.
     */
    fun install(pack: ExtensionPack, onStatus: (PackStatus) -> Unit) {
        if (pack.id == "phone_privileged") { onStatus(PackStatus.Installed); return }

        val packDir   = File(packsDir, pack.id).also { it.mkdirs() }
        val stampFile = File(packDir, ".deckos_pack_${pack.id}")

        if (stampFile.exists()) { onStatus(PackStatus.Installed); return }

        val ext = archiveExt(pack)
        val finalArchive   = File(context.cacheDir, "${pack.id}.$ext")
        val partialArchive = File(context.cacheDir, "${pack.id}.$ext$PARTIAL_SUFFIX")

        try {
            // ── 1. DOWNLOAD (resumable) ─────────────────────────────────────
            onStatus(PackStatus.Downloading(0f, "Preparing download…"))
            downloadResumable(pack.url, pack.sizeBytes, finalArchive, partialArchive) { pct, msg ->
                onStatus(PackStatus.Downloading(pct * 0.7f, msg))
            }

            if (!finalArchive.exists() || finalArchive.length() == 0L) {
                onStatus(PackStatus.Failed("Download failed — file missing"))
                return
            }

            // ── 2. VERIFY checksum ────────────────────────────────────────────
            if (pack.sha256.isNotEmpty() && !pack.sha256.startsWith("000000")) {
                onStatus(PackStatus.Verifying)
                val hash = sha256Hex(finalArchive)
                if (!hash.equals(pack.sha256, ignoreCase = true)) {
                    Log.e(TAG, "Hash mismatch for ${pack.id}: expected ${pack.sha256}, got $hash")
                    // Don't delete — let user retry; hash mismatch could be a server update
                    onStatus(PackStatus.Failed("Checksum mismatch — tap Retry"))
                    return
                }
            }

            // ── 3. EXTRACT ────────────────────────────────────────────────────
            onStatus(PackStatus.Extracting(0f))
            val ok = extractWithProgress(finalArchive, packDir, pack) { pct ->
                onStatus(PackStatus.Extracting(pct))
            }

            if (!ok) {
                onStatus(PackStatus.Failed("Extraction failed — archive may be corrupt"))
                return
            }

            // ── 4. STAMP & DONE ───────────────────────────────────────────────
            finalArchive.delete()
            stampFile.writeText(pack.sha256.ifEmpty { "installed" })
            Log.i(TAG, "Pack ${pack.id} installed OK")
            refreshStatuses()
            onStatus(PackStatus.Installed)

        } catch (e: Exception) {
            Log.e(TAG, "Install failed for ${pack.id}", e)
            // Keep partial/final archive on disk for resume next time
            onStatus(PackStatus.Failed(e.message ?: "Unknown error"))
        }
    }

    fun uninstall(packId: String): Boolean {
        if (packId == "phone_privileged") return true
        val ok = File(packsDir, packId).deleteRecursively()
        // Also clean any leftover archive
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(packId) }
            ?.forEach { it.delete() }
        refreshStatuses()
        return ok
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resumable HTTP download using the `Range` header.
     * If [partialFile] exists its size is sent as `Range: bytes=<size>-` so the
     * server continues from where we stopped. On success the file is renamed to
     * [destFile].
     */
    private fun downloadResumable(
        urlStr: String,
        expectedSize: Long,
        destFile: File,
        partialFile: File,
        onProgress: (Float, String) -> Unit,
    ) {
        // Already fully downloaded?
        if (destFile.exists() && destFile.length() > 0) {
            Log.i(TAG, "Archive already present, skipping download")
            onProgress(1f, "Already downloaded")
            return
        }

        val alreadyHave = if (partialFile.exists()) partialFile.length() else 0L
        Log.i(TAG, "Download start: already=$alreadyHave url=$urlStr")

        var attempt = 0
        while (attempt < 3) {
            attempt++
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout    = 90_000
                    setRequestProperty("User-Agent", "DeckOS/1.0")
                    if (alreadyHave > 0) setRequestProperty("Range", "bytes=$alreadyHave-")
                    connect()
                }

                val resuming  = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                val startByte = if (resuming) alreadyHave else 0L
                val total     = if (expectedSize > 0) expectedSize
                                else (conn.contentLengthLong + startByte).coerceAtLeast(1L)

                if (!resuming && alreadyHave > 0) {
                    // Server doesn't support Range — restart
                    partialFile.delete()
                }

                conn.inputStream.use { ins ->
                    FileOutputStream(partialFile, resuming).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var written = startByte
                        var read: Int
                        while (ins.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            written += read
                            val pct = (written.toFloat() / total).coerceIn(0f, 1f)
                            val mb  = written / 1_048_576
                            val tot = total  / 1_048_576
                            onProgress(pct, "Downloading $mb / $tot MB  (${(pct * 100).toInt()}%)")
                        }
                    }
                }
                conn.disconnect()

                // Rename partial → final
                partialFile.renameTo(destFile)
                onProgress(1f, "Download complete")
                return

            } catch (e: Exception) {
                Log.w(TAG, "Download attempt $attempt failed: ${e.message}")
                if (attempt >= 3) throw e
                Thread.sleep(2_000)
            }
        }
    }

    /**
     * Extracts via Termux's native `tar` binary (supports .tar.zst, .tar.xz, .tar.gz).
     * Reports approximate progress by watching the archive read position every 500ms.
     */
    private fun extractWithProgress(
        archiveFile: File,
        destDir: File,
        pack: ExtensionPack,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val prefix = File(context.filesDir, "usr")
        val tarBin = File(prefix, "bin/tar")
        val shBin  = File("/system/bin/sh")

        if (!tarBin.exists()) return extractFallback(archiveFile, destDir, prefix)

        return try {
            val usrLib = "${prefix.absolutePath}/lib"
            val usrBin = "${prefix.absolutePath}/bin"

            // Wrap in sh -c so LD_LIBRARY_PATH is inherited by zstd subprocess spawned by tar
            val cmd = listOf(
                shBin.absolutePath, "-c",
                "LD_LIBRARY_PATH=$usrLib PATH=$usrBin:/system/bin " +
                    "${tarBin.absolutePath} -xf ${archiveFile.absolutePath} " +
                    "-C ${destDir.absolutePath} --no-same-owner --delay-directory-restore 2>&1"
            )

            val pb = ProcessBuilder(cmd).apply {
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                redirectErrorStream(true)
            }

            val process   = pb.start()
            val archSize  = archiveFile.length().coerceAtLeast(1L)
            val outputLog = StringBuilder()

            // Progress watcher thread — polls how much of the archive tar has read
            val watcher = Thread {
                try {
                    RandomAccessFile(archiveFile, "r").use { raf ->
                        while (process.isAlive) {
                            // Heuristic: estimate progress via file position (tar reads sequentially)
                            val pos = runCatching { raf.filePointer }.getOrDefault(0L)
                            onProgress((pos.toFloat() / archSize).coerceIn(0f, 0.99f))
                            Thread.sleep(500)
                        }
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            // Drain stdout (tar --verbose would help but adds overhead)
            val drainer = Thread {
                try {
                    process.inputStream.bufferedReader().use { br ->
                        br.lines().forEach { outputLog.appendLine(it) }
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            // Wait up to 30 minutes (large archives take time)
            val done = process.waitFor(30, TimeUnit.MINUTES)
            watcher.interrupt()
            drainer.join(2_000)

            if (!done) {
                process.destroyForcibly()
                Log.e(TAG, "Extraction timed out for ${pack.id}")
                return false
            }

            val exitCode = process.exitValue()
            Log.i(TAG, "tar exit=$exitCode for ${pack.id}")
            if (exitCode != 0) Log.e(TAG, "tar output: $outputLog")

            onProgress(1f)
            exitCode == 0

        } catch (e: Exception) {
            Log.e(TAG, "Extract failed: ${e.message}")
            false
        }
    }

    /** Fallback: unzstd | tar if the combined command doesn't work. */
    private fun extractFallback(archive: File, destDir: File, prefix: File): Boolean {
        val unzstd = File(prefix, "bin/unzstd")
        val tar    = File(prefix, "bin/tar")
        if (!unzstd.exists() || !tar.exists()) return false
        return try {
            val decompressed = File(context.cacheDir, archive.nameWithoutExtension)
            ProcessBuilder(unzstd.absolutePath, archive.absolutePath, "-o", decompressed.absolutePath)
                .apply { environment()["TMPDIR"] = context.cacheDir.absolutePath }
                .start().waitFor(20, TimeUnit.MINUTES)
            ProcessBuilder(tar.absolutePath, "-xf", decompressed.absolutePath, "-C", destDir.absolutePath)
                .apply { environment()["PATH"] = "${prefix.absolutePath}/bin:/system/bin" }
                .start().waitFor(20, TimeUnit.MINUTES)
            decompressed.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallback extract failed: ${e.message}")
            false
        }
    }

    private fun archiveExt(pack: ExtensionPack) = when {
        pack.url.endsWith(".tar.zst", ignoreCase = true) || pack.archiveFormat == "tar.zst" -> "tar.zst"
        pack.url.endsWith(".tar.xz",  ignoreCase = true) || pack.archiveFormat == "tar.xz"  -> "tar.xz"
        pack.url.endsWith(".tar.bz2", ignoreCase = true)                                    -> "tar.bz2"
        else -> "tar.gz"
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(65_536)
            var read: Int
            while (raf.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

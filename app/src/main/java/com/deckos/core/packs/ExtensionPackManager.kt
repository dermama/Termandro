package com.deckos.core.packs

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "DeckOSPackMgr"

class ExtensionPackManager(private val context: Context) {

    val packsDir: File
        get() = File(context.filesDir, "packs").also { it.mkdirs() }

    private val _packStatuses = MutableStateFlow<Map<String, PackStatus>>(emptyMap())
    val packStatuses: StateFlow<Map<String, PackStatus>> = _packStatuses.asStateFlow()

    init {
        refreshStatuses()
    }

    fun getPackDir(packId: String): File = File(packsDir, packId)

    fun isInstalled(packId: String): Boolean {
        if (packId == "phone_privileged") return true // built-in
        val packDir = File(packsDir, packId)
        val stampFile = File(packDir, ".deckos_pack_$packId")
        return stampFile.exists() && packDir.isDirectory
    }

    /** Overload accepting an [ExtensionPack] directly. */
    fun isInstalled(pack: ExtensionPack): Boolean = isInstalled(pack.id)

    /** Remove a pack and clean its directory. */
    fun remove(pack: ExtensionPack): Boolean = uninstall(pack.id)

    fun getStatus(packId: String): PackStatus {
        if (packId == "phone_privileged") return PackStatus.Installed
        return if (isInstalled(packId)) PackStatus.Installed else PackStatus.NotInstalled
    }

    fun refreshStatuses() {
        val map = ExtensionPackCatalog.ALL_PACKS.associate { pack ->
            pack.id to getStatus(pack.id)
        }
        _packStatuses.value = map
    }

    /**
     * Install a pack with a [PackStatus] callback suitable for Compose UI.
     * Runs synchronously — call from a coroutine / IO dispatcher.
     */
    fun install(pack: ExtensionPack, onStatus: (PackStatus) -> Unit) {
        onStatus(PackStatus.Downloading(0f, "Preparing…"))
        val ok = install(pack) { pct, msg ->
            onStatus(PackStatus.Downloading(pct, msg))
        }
        onStatus(if (ok) PackStatus.Installed else PackStatus.Failed("Installation failed"))
    }

    fun install(
        pack: ExtensionPack,
        onProgress: ((Float, String) -> Unit)? = null,
    ): Boolean {
        if (pack.id == "phone_privileged") {
            refreshStatuses()
            return true
        }

        val packDir = File(packsDir, pack.id)
        val stampFile = File(packDir, ".deckos_pack_${pack.id}")

        if (stampFile.exists()) {
            Log.i(TAG, "Pack ${pack.id} already installed")
            return true
        }

        val ext = when {
            pack.url.endsWith(".tar.zst", ignoreCase = true) || pack.archiveFormat == "tar.zst" -> "tar.zst"
            pack.url.endsWith(".tar.xz", ignoreCase = true) || pack.archiveFormat == "tar.xz" -> "tar.xz"
            pack.url.endsWith(".tar.bz2", ignoreCase = true) -> "tar.bz2"
            else -> "tar.gz"
        }

        val tempArchive = File(context.cacheDir, "${pack.id}.$ext")

        return try {
            packDir.mkdirs()

            // 1. Download
            onProgress?.invoke(0.05f, "Downloading ${pack.displayName}…")
            downloadWithProgress(pack.url, pack.sizeBytes, tempArchive) { p, msg ->
                onProgress?.invoke(0.05f + p * 0.65f, msg)
            }

            // 2. Hash check if sha256 is provided
            if (pack.sha256.isNotEmpty() && !pack.sha256.startsWith("000000")) {
                onProgress?.invoke(0.72f, "Verifying checksum…")
                val hash = sha256Hex(tempArchive)
                if (!hash.equals(pack.sha256, ignoreCase = true)) {
                    tempArchive.delete()
                    Log.e(TAG, "Hash mismatch for ${pack.id}: expected ${pack.sha256}, got $hash")
                    return false
                }
            }

            // 3. Extract
            onProgress?.invoke(0.75f, "Extracting ${pack.displayName}…")
            val extracted = extractNative(tempArchive, packDir)
            if (!extracted) {
                Log.e(TAG, "Extraction failed for ${pack.id}")
                tempArchive.delete()
                return false
            }

            tempArchive.delete()

            // 4. Mark success
            stampFile.writeText(pack.sha256.ifEmpty { "installed" })
            Log.i(TAG, "Pack ${pack.id} installed successfully")
            onProgress?.invoke(1.0f, "${pack.displayName} Ready")
            refreshStatuses()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Install failed for ${pack.id}", e)
            tempArchive.delete()
            false
        }
    }

    fun uninstall(packId: String): Boolean {
        if (packId == "phone_privileged") return true
        val packDir = File(packsDir, packId)
        val success = packDir.deleteRecursively()
        refreshStatuses()
        return success
    }

    private fun downloadWithProgress(
        urlStr: String,
        expectedSize: Long,
        dest: File,
        onProgress: (Float, String) -> Unit,
    ) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.connect()

        val total = if (expectedSize > 0) expectedSize else conn.contentLengthLong

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
                        onProgress(pct, "Downloading (${"%.0f".format(pct * 100)}%)")
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(65_536)
            var read: Int
            while (raf.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractNative(archiveFile: File, destDir: File): Boolean {
        val prefix = File(context.filesDir, "usr")
        val tarBin = File(prefix, "bin/tar")
        if (!tarBin.exists()) return false

        return try {
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
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Native extract failed: ${e.message}")
            false
        }
    }
}

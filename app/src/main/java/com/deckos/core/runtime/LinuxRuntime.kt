package com.deckos.core.runtime

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class LinuxRuntime(private val context: Context) {

    companion object {
        private const val TAG = "DeckOSLinux"

        const val BOOTSTRAP_URL =
            "https://github.com/termux/termux-packages/releases/download/" +
                "bootstrap-2026.10.18-r1%2Bapt.android-7/bootstrap-aarch64.zip"
        const val BOOTSTRAP_SHA256 =
            "7b170ff5ef0befe501d01febc61382f3c6e34456521853cff9ba0b277aa52fe9"
        const val BOOTSTRAP_SIZE = 33_071_189L

        private const val OLD_PREFIX = "/data/data/com.termux/files/usr"
        private const val OLD_HOME = "/data/data/com.termux/files/home"
        private const val MARKER = ".deckos_bootstrap_v1"

        private val TEXT_DIRS = listOf(
            "bin",
            "libexec",
            "etc",
            "share",
            "lib",
            "var/lib/dpkg/info",
        )
    }

    private val baseDir: File get() = context.filesDir
    private val prefixDir: File get() = File(baseDir, "usr")
    private val homeDir: File get() = File(baseDir, "home")
    private val tmpDir: File get() = File(baseDir, "tmp")

    fun isInstalled(): Boolean =
        File(baseDir, MARKER).exists() && File(prefixDir, "bin/bash").exists()

    fun shellPath(): String = File(prefixDir, "bin/bash").absolutePath

    fun homePath(): String = homeDir.absolutePath

    fun prepare() {
        if (isInstalled()) {
            ensureRuntimeFiles()
        }
    }

    @Synchronized
    fun ensureInstalled(onProgress: (Float, String) -> Unit) {
        if (isInstalled()) {
            ensureRuntimeFiles()
            onProgress(1f, "Linux is ready")
            return
        }

        homeDir.mkdirs()
        tmpDir.mkdirs()
        onProgress(0.01f, "Downloading Linux packages")

        val zip = download(onProgress)
        try {
            onProgress(0.82f, "Unpacking Linux packages")
            installFromZip(zip, onProgress)
        } finally {
            zip.delete()
        }
        onProgress(1f, "Linux is ready")
    }

    fun environment(): Array<String> {
        val prefix = prefixDir.absolutePath
        val home = homeDir.absolutePath
        val tmp = tmpDir.absolutePath
        return arrayOf(
            "PREFIX=$prefix",
            "HOME=$home",
            "TMPDIR=$tmp",
            "PATH=$prefix/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=$prefix/lib",
            "LD_PRELOAD=$prefix/lib/libsocket_hook.so",
            "DECKOS_PREFIX=$prefix",
            "SHELL=$prefix/bin/bash",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "DISPLAY=:1",
            "XDG_RUNTIME_DIR=$tmp",
            "TERMUX_APP__PACKAGE_NAME=com.deckos.core",
            "TERMUX__PREFIX=$prefix",
            "TERMUX__HOME=$home",
            "TERMUX_VERSION=DeckOS 0.0.3",
            "TERMUX_APP_PACKAGE_MANAGER=apt",
            "DPKG_ADMINDIR=$prefix/var/lib/dpkg",
            "APT_CONFIG=$prefix/etc/apt/apt.conf.d/99-deckos-paths.conf",
            "ANDROID_DATA=${System.getenv("ANDROID_DATA") ?: "/data"}",
            "ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: "/system"}",
            "EXTERNAL_STORAGE=${System.getenv("EXTERNAL_STORAGE") ?: "/sdcard"}",
        )
    }

    private fun download(onProgress: (Float, String) -> Unit): File {
        val cached = File(context.cacheDir, "bootstrap-aarch64.zip")
        if (cached.isFile && cached.length() == BOOTSTRAP_SIZE && sha256(cached) == BOOTSTRAP_SHA256) {
            onProgress(0.8f, "Using cached Linux packages")
            return cached
        }
        cached.delete()

        val part = File(context.cacheDir, "bootstrap-aarch64.zip.part")
        part.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(BOOTSTRAP_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 90_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DeckOS-Core")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Download failed with HTTP $code")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val fraction = if (total > 0) copied.toFloat() / total else 0f
                        onProgress(
                            0.02f + fraction.coerceIn(0f, 1f) * 0.76f,
                            "Downloading Linux packages ${(fraction * 100).toInt()}%",
                        )
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }

        if (part.length() != BOOTSTRAP_SIZE) {
            part.delete()
            throw IOException("Downloaded file has an unexpected size")
        }
        val digest = sha256(part)
        if (!digest.equals(BOOTSTRAP_SHA256, ignoreCase = true)) {
            part.delete()
            throw IOException("Downloaded file failed its integrity check")
        }
        if (!part.renameTo(cached)) {
            part.copyTo(cached, overwrite = true)
            part.delete()
        }
        return cached
    }

    private fun installFromZip(zip: File, onProgress: (Float, String) -> Unit) {
        val staging = File(baseDir, "usr.new")
        deleteTree(staging)
        staging.mkdirs()

        try {
            extract(zip, staging)
            onProgress(0.9f, "Restoring Linux links")
            restoreSymlinks(staging)
            prepareDirectories(staging)
            onProgress(0.93f, "Configuring package manager")
            relocateText(staging)
            installHook(staging)
            installDpkgWrapper(staging)
            writeAptConfig(staging)
            setExecutables(staging)
            linkRuntimeDirectories(staging)

            deleteTree(prefixDir)
            if (!staging.renameTo(prefixDir)) {
                throw IOException("Could not activate the Linux environment")
            }
            File(baseDir, MARKER).writeText(BOOTSTRAP_SHA256 + "\n")
            Log.i(TAG, "Linux installed at ${prefixDir.absolutePath}")
        } catch (error: Throwable) {
            deleteTree(staging)
            throw error
        }
    }

    private fun extract(zip: File, destination: File) {
        val root = destination.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val output = File(destination, entry.name)
                val canonical = output.canonicalFile
                if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
                    throw IOException("Invalid path in Linux archive: ${entry.name}")
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                } else {
                    canonical.parentFile?.mkdirs()
                    canonical.outputStream().use { out -> input.copyTo(out) }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }

    private fun restoreSymlinks(prefix: File) {
        val links = File(prefix, "SYMLINKS.txt")
        if (!links.isFile) return

        var created = 0
        links.forEachLine { line ->
            val parts = line.trim().split('\u2190')
            if (parts.size != 2) return@forEachLine
            val rawTarget = parts[0].trim()
            val rawLink = parts[1].trim()
            if (rawTarget.isEmpty() || rawLink.isEmpty()) return@forEachLine

            val target = when {
                rawTarget.startsWith(OLD_PREFIX) ->
                    prefixDir.absolutePath + rawTarget.substring(OLD_PREFIX.length)
                rawTarget.startsWith(OLD_HOME) ->
                    homeDir.absolutePath + rawTarget.substring(OLD_HOME.length)
                else -> rawTarget
            }
            val linkPath = rawLink.removePrefix("./")
            val link = File(prefix, linkPath)
            try {
                deleteTree(link)
                link.parentFile?.mkdirs()
                Os.symlink(target, link.absolutePath)
                created++
            } catch (error: Exception) {
                Log.w(TAG, "Could not restore ${link.absolutePath}: ${error.message}")
            }
        }
        Log.i(TAG, "Restored $created links")
    }

    private fun prepareDirectories(prefix: File) {
        listOf(
            "etc/dpkg/dpkg.cfg.d",
            "var/cache/apt/archives/partial",
            "var/lib/apt/lists/partial",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/parts",
            "var/lib/dpkg/triggers",
            "var/log/apt",
        ).forEach { File(prefix, it).mkdirs() }

        listOf(
            "var/lib/dpkg/status",
            "var/lib/dpkg/available",
            "var/lib/dpkg/diversions",
        ).forEach { relative ->
            val file = File(prefix, relative)
            if (!file.exists()) file.createNewFile()
        }
    }

    private fun relocateText(prefix: File) {
        var changed = 0
        TEXT_DIRS.forEach { relative ->
            val dir = File(prefix, relative)
            if (!dir.exists()) return@forEach
            dir.walkTopDown().forEach { file ->
                if (!file.isFile || file.length() > 4 * 1024 * 1024) return@forEach
                val name = file.name
                if (name.endsWith(".list") || name.endsWith(".md5sums") || name.endsWith(".conffiles")) {
                    return@forEach
                }
                if (isElf(file)) return@forEach
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                val text = String(bytes, Charsets.ISO_8859_1)
                if (!text.contains(OLD_PREFIX) && !text.contains(OLD_HOME)) return@forEach
                file.writeBytes(
                    text.replace(OLD_PREFIX, prefix.absolutePath)
                        .replace(OLD_HOME, homeDir.absolutePath)
                        .toByteArray(Charsets.ISO_8859_1),
                )
                changed++
            }
        }
        Log.i(TAG, "Relocated $changed text files")
    }

    private fun installHook(prefix: File) {
        val destination = File(prefix, "lib/libsocket_hook.so")
        destination.parentFile?.mkdirs()
        context.assets.open("libsocket_hook.so").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        destination.setExecutable(true, false)
    }

    private fun installDpkgWrapper(prefix: File) {
        val dpkg = File(prefix, "bin/dpkg")
        val real = File(prefix, "bin/dpkg.real")
        if (!real.exists() && dpkg.exists()) {
            if (!dpkg.renameTo(real)) {
                throw IOException("Could not prepare the package manager")
            }
        }

        val relocate = File(prefix, "bin/deckos-relocate-paths")
        relocate.writeText(
            """
            #!/system/bin/sh
            old_prefix="$OLD_PREFIX"
            new_prefix="${prefix.absolutePath}"
            old_home="$OLD_HOME"
            new_home="${homeDir.absolutePath}"
            marker="${'$'}1"
            [ -f "${'$'}marker" ] || exit 0
            for root in \
                "${'$'}new_prefix/bin" \
                "${'$'}new_prefix/libexec" \
                "${'$'}new_prefix/etc" \
                "${'$'}new_prefix/share" \
                "${'$'}new_prefix/lib" \
                "${'$'}new_prefix/var/lib/dpkg/info"
            do
                [ -d "${'$'}root" ] || continue
                "${'$'}new_prefix/bin/find" "${'$'}root" -type f -newer "${'$'}marker" -size -4M \
                    ! -name '*.list' ! -name '*.md5sums' ! -name '*.conffiles' \
                    ! -name 'deckos-relocate-paths' 2>/dev/null |
                while IFS= read -r file; do
                    if "${'$'}new_prefix/bin/grep" -qIF "${'$'}old_prefix" "${'$'}file" 2>/dev/null ||
                       "${'$'}new_prefix/bin/grep" -qIF "${'$'}old_home" "${'$'}file" 2>/dev/null; then
                        "${'$'}new_prefix/bin/sed" -i \
                            -e "s|${'$'}old_prefix|${'$'}new_prefix|g" \
                            -e "s|${'$'}old_home|${'$'}new_home|g" "${'$'}file" 2>/dev/null || true
                    fi
                done
            done
            exit 0
            """.trimIndent() + "\n",
        )
        relocate.setExecutable(true, false)

        dpkg.writeText(
            """
            #!/system/bin/sh
            PREFIX="${prefix.absolutePath}"
            HOME="${homeDir.absolutePath}"
            TMPDIR="${tmpDir.absolutePath}"
            export PREFIX HOME TMPDIR
            export PATH="${'$'}PREFIX/bin:/system/bin:/system/xbin"
            export LD_LIBRARY_PATH="${'$'}PREFIX/lib"
            export LD_PRELOAD="${'$'}PREFIX/lib/libsocket_hook.so"
            export DECKOS_PREFIX="${'$'}PREFIX"
            export DPKG_ADMINDIR="${'$'}PREFIX/var/lib/dpkg"
            marker="${'$'}TMPDIR/dpkg-scan.${'$'}${'$'}"
            : > "${'$'}marker"
            "${'$'}PREFIX/bin/dpkg.real" "${'$'}@"
            status=${'$'}?
            "${'$'}PREFIX/bin/deckos-relocate-paths" "${'$'}marker" >/dev/null 2>&1
            rm -f "${'$'}marker"
            exit ${'$'}status
            """.trimIndent() + "\n",
        )
        dpkg.setExecutable(true, false)
    }

    private fun writeAptConfig(prefix: File) {
        val directory = File(prefix, "etc/apt/apt.conf.d")
        directory.mkdirs()
        File(directory, "99-deckos-paths.conf").writeText(
            """
            Dir "${prefix.absolutePath}";
            Dir::Etc "${prefix.absolutePath}/etc/apt";
            Dir::State "${prefix.absolutePath}/var/lib/apt";
            Dir::State::dpkg "${prefix.absolutePath}/var/lib/dpkg";
            Dir::Cache "${prefix.absolutePath}/var/cache/apt";
            Dir::Log "${prefix.absolutePath}/var/log/apt";
            Dir::Bin::Methods "${prefix.absolutePath}/lib/apt/methods";
            Dir::Bin::dpkg "${prefix.absolutePath}/bin/dpkg";
            Acquire::Retries "3";
            Acquire::http::Timeout "30";
            Acquire::https::Timeout "30";
            DPkg::Lock::Timeout "60";
            """.trimIndent() + "\n",
        )
    }

    private fun setExecutables(prefix: File) {
        listOf("bin", "libexec", "lib").forEach { relative ->
            val dir = File(prefix, relative)
            if (!dir.exists()) return@forEach
            dir.walkTopDown().forEach { file ->
                if (file.isFile) file.setExecutable(true, false)
            }
        }
    }

    private fun linkRuntimeDirectories(prefix: File) {
        val tmp = File(prefix, "tmp")
        if (tmp.isDirectory && tmp.list()?.isEmpty() == true) {
            tmp.delete()
        }
        if (!tmp.exists()) {
            Os.symlink(tmpDir.absolutePath, tmp.absolutePath)
        }

        val usr = File(prefix, "usr")
        if (!usr.exists()) {
            Os.symlink(".", usr.absolutePath)
        }
    }

    private fun ensureRuntimeFiles() {
        File(prefixDir, "lib/libsocket_hook.so").let { hook ->
            if (!hook.isFile) {
                installHook(prefixDir)
            }
        }
        if (!File(prefixDir, "bin/dpkg.real").exists()) {
            installDpkgWrapper(prefixDir)
        }
        writeAptConfig(prefixDir)
        prepareDirectories(prefixDir)
        linkRuntimeDirectories(prefixDir)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteTree(file: File) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) {
            file.delete()
            return
        }
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteTree(child) }
        }
        file.delete()
    }

    private fun isElf(file: File): Boolean {
        val header = ByteArray(4)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
        return read == 4 &&
            header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()
    }
}

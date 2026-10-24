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
        private const val DESKTOP_MARKER = ".deckos_desktop_v1"

        /** Minimal desktop package set: window manager + terminal + fonts.
         *  Openbox, st (simple terminal), and xorg-xsetroot live in Termux's x11-repo.
         *  L-61: Added dbus for session bus support (featherpad/pcmanfm expect it).
         */
        private val X11_REPO_PACKAGE = listOf("x11-repo")
        private val DESKTOP_PACKAGES = listOf(
            "openbox",
            "st",
            "fontconfig",
            "fontconfig-utils",
            "xorg-xsetroot",
            "tint2",
            "pcmanfm",
            "featherpad",
            "pulseaudio",
            "xclip",
            "dbus",         // L-61: session bus for X11 desktop apps
            "devilspie2",   // L-61: window rules for mobile form factor
        )

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

    fun isDesktopInstalled(): Boolean =
        File(baseDir, DESKTOP_MARKER).exists() &&
            File(prefixDir, "bin/openbox").exists() &&
            File(prefixDir, "bin/st").exists() &&
            File(prefixDir, "bin/tint2").exists() &&
            File(prefixDir, "bin/pcmanfm").exists() &&
            File(prefixDir, "bin/featherpad").exists() &&
            File(prefixDir, "bin/pulseaudio").exists() &&
            File(prefixDir, "bin/dbus-daemon").exists()  // L-61: dbus required

    fun shellPath(): String = File(prefixDir, "bin/bash").absolutePath

    fun homePath(): String = homeDir.absolutePath

    /** Ensure essential desktop config files (Openbox autostart, PulseAudio, .bashrc, fake root, workspace) exist. */
    private fun ensureDesktopConfigFiles() {
        // Workspace directories
        val workspaceDir = File(baseDir, "workspace")
        workspaceDir.mkdirs()
        val desktopDir = File(homeDir, "Desktop")
        desktopDir.mkdirs()
        val wsLink = File(homeDir, "workspace")
        if (!wsLink.exists()) {
            runCatching { Os.symlink(workspaceDir.absolutePath, wsLink.absolutePath) }
        }
        val desktopWsLink = File(desktopDir, "Workspace")
        if (!desktopWsLink.exists()) {
            runCatching { Os.symlink(workspaceDir.absolutePath, desktopWsLink.absolutePath) }
        }

        // Shared phone storage link
        val phoneStorage = File(context.getExternalFilesDir(null), "storage")
        phoneStorage.mkdirs()
        val sharedLink = File(workspaceDir, "phone_storage")
        if (!sharedLink.exists()) {
            runCatching { Os.symlink(phoneStorage.absolutePath, sharedLink.absolutePath) }
        }

        // Configure PulseAudio default.pa for low-latency AAudio and TCP streaming
        val pulseDefaultPa = File(prefixDir, "etc/pulse/default.pa")
        if (pulseDefaultPa.isFile) {
            val content = pulseDefaultPa.readText()
            var modified = content
            if (modified.contains("load-module module-sles-sink")) {
                modified = modified.replace("load-module module-sles-sink", "load-module module-aaudio-sink")
            }
            if (modified.contains("#load-module module-native-protocol-tcp")) {
                modified = modified.replace(
                    "#load-module module-native-protocol-tcp",
                    "load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1"
                )
            }
            if (modified != content) {
                pulseDefaultPa.writeText(modified)
            }
        }

        // L-61: Configure devilspie2 window rules for mobile form factor
        val devilspie2Dir = File(homeDir, ".config/devilspie2")
        devilspie2Dir.mkdirs()
        val devilspie2Cfg = File(devilspie2Dir, "deckos-mobile.lua")
        if (!devilspie2Cfg.exists()) {
            devilspie2Cfg.writeText("""
                -- DeckOS: maximize all windows by default on mobile screens
                if get_window_type() == "WINDOW_TYPE_NORMAL" then
                    local w = get_screen_geometry()["width"]
                    if w < 1280 then
                        maximize()
                    end
                end
            """.trimIndent() + "\n")
        }

        val configDir = File(homeDir, ".config/openbox")
        configDir.mkdirs()
        val autostart = File(configDir, "autostart")
        if (!autostart.exists() || !autostart.readText().contains("--rcfile")) {
            autostart.writeText(
                """
                #!/data/user/0/com.deckos.core/files/usr/bin/bash
                export DISPLAY=:1
                export LD_LIBRARY_PATH=/data/user/0/com.deckos.core/files/usr/lib
                export LD_PRELOAD=/data/user/0/com.deckos.core/files/usr/lib/libsocket_hook.so
                export DECKOS_PREFIX=/data/user/0/com.deckos.core/files/usr
                export HOME=/data/user/0/com.deckos.core/files/home
                export TMPDIR=/data/user/0/com.deckos.core/files/tmp
                export PULSE_SERVER=tcp:127.0.0.1:4713
                # L-61: Start dbus session bus FIRST (apps depend on it)
                if command -v dbus-daemon >/dev/null 2>&1; then
                    eval $(dbus-launch --sh-syntax 2>/dev/null) || true
                    export DBUS_SESSION_BUS_ADDRESS
                fi
                # L-53: Start PulseAudio in background — don't block Openbox startup
                pulseaudio --start --exit-idle-time=-1 2>/dev/null &
                # Set background immediately (fast, no deps)
                xorg-xsetroot -solid "#0D1117" 2>/dev/null || xsetroot -solid "#0D1117" 2>/dev/null || true
                # Desktop services & panels — all background
                tint2 &
                pcmanfm --desktop &
                # L-61: devilspie2 window rules for mobile
                command -v devilspie2 >/dev/null 2>&1 && devilspie2 &
                st -e bash --rcfile "${'$'}HOME/.bashrc" &
                """.trimIndent() + "\n",
            )
            autostart.setExecutable(true, false)
        }

        val bashrc = File(homeDir, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                """
                # DeckOS Default Shell Configuration
                export PS1='\[\033[01;36m\]deckos\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                export PULSE_SERVER=tcp:127.0.0.1:4713
                alias ls='ls --color=auto'
                alias ll='ls -la'
                """.trimIndent() + "\n",
            )
        }

        val fakeRootFiles = File(baseDir, ".deckos-termux-root/files")
        fakeRootFiles.mkdirs()
        val fakeHome = File(fakeRootFiles, "home")
        if (!fakeHome.exists()) {
            runCatching { Os.symlink(homeDir.absolutePath, fakeHome.absolutePath) }
        }
        val fakeUsr = File(fakeRootFiles, "usr")
        if (!fakeUsr.exists()) {
            runCatching { Os.symlink(prefixDir.absolutePath, fakeUsr.absolutePath) }
        }
    }

    /** Script that starts the minimal DeckOS desktop session under DISPLAY :1. */
    private fun desktopSessionScript(): File {
        ensureDesktopConfigFiles()
        val script = File(homeDir, ".deckos-start-desktop")
        if (!script.exists()) {
            script.writeText(
                """
                #!/data/user/0/com.deckos.core/files/usr/bin/bash
                export DISPLAY=:1
                export PULSE_SERVER=tcp:127.0.0.1:4713
                # L-61: Start dbus session bus (apps need it before openbox-session)
                if command -v dbus-daemon >/dev/null 2>&1; then
                    eval $(dbus-launch --sh-syntax 2>/dev/null) || true
                    export DBUS_SESSION_BUS_ADDRESS
                fi
                # Start the window manager session
                exec openbox-session
                """.trimIndent() + "\n",
            )
            script.setExecutable(true, false)
        }
        return script
    }

    /**
     * L-61: Repair half-installed dpkg packages before any session start.
     * Ported from DroidDesk's pre-session repair pattern.
     * Safe to call repeatedly — dpkg --configure -a is idempotent.
     */
    fun repairBrokenPackages() {
        if (!isInstalled()) return
        val logFile = File(baseDir, "repair.log")
        runCatching {
            // Step 1: finish any interrupted dpkg configure
            val pb1 = ProcessBuilder(
                File(prefixDir, "bin/dpkg").absolutePath,
                "--configure", "-a"
            )
            pb1.environment().clear()
            for (entry in environment()) {
                val eq = entry.indexOf('=')
                if (eq > 0) pb1.environment()[entry.substring(0, eq)] = entry.substring(eq + 1)
            }
            pb1.environment()["DEBIAN_FRONTEND"] = "noninteractive"
            pb1.directory(homeDir)
            pb1.redirectErrorStream(true)
            pb1.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            pb1.start().waitFor()

            // Step 2: fix broken dependencies
            val pb2 = ProcessBuilder(
                File(prefixDir, "bin/apt-get").absolutePath,
                "install", "-f", "-y"
            )
            pb2.environment().clear()
            for (entry in environment()) {
                val eq = entry.indexOf('=')
                if (eq > 0) pb2.environment()[entry.substring(0, eq)] = entry.substring(eq + 1)
            }
            pb2.environment()["DEBIAN_FRONTEND"] = "noninteractive"
            pb2.directory(homeDir)
            pb2.redirectErrorStream(true)
            pb2.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            pb2.start().waitFor()
        }.onFailure { e ->
            Log.w(TAG, "Package repair failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Install the minimal desktop package set (Openbox + xterm) via apt.
     * Calls [onProgress] with 0..1 progress and a status message.
     * Must be called from a background thread — never the main thread.
     */
    @Synchronized
    fun installDesktopPackages(onProgress: (Float, String) -> Unit) {
        if (isDesktopInstalled()) {
            onProgress(1f, "Desktop already installed")
            return
        }
        require(isInstalled()) { "Bootstrap must be installed before the desktop" }

        onProgress(0.05f, "Enabling X11 package repository")
        runApt(listOf("install", "-y") + X11_REPO_PACKAGE)
        onProgress(0.15f, "Updating package lists")
        runApt(listOf("update"))
        onProgress(0.30f, "Installing desktop environment")
        runApt(listOf("install", "-y", "--no-install-recommends") + DESKTOP_PACKAGES)
        // Write the session script now that openbox binary is available.
        desktopSessionScript()
        File(baseDir, DESKTOP_MARKER).writeText("openbox+st\n")
        onProgress(1f, "Desktop installed")
    }

    /**
     * Launch the desktop session script as a background process.
     * Returns the PID of the launched process, or null if launch failed.
     * The Openbox process is detached — it continues after this call returns.
     */
    fun startDesktop(): Int? {
        desktopSessionScript()
        val script = File(homeDir, ".deckos-start-desktop").absolutePath
        val pidFile = File(baseDir, "openbox.pid")
        pidFile.delete()

        // Wrap the session script so it writes its own PID before exec-ing openbox.
        val wrapper = File(homeDir, ".deckos-start-desktop-wrapped")
        wrapper.writeText(
            "#!/system/bin/sh\necho \$\$ > '${pidFile.absolutePath}'\nexec '$script'\n"
        )
        wrapper.setExecutable(true, false)

        return runCatching {
            val pb = ProcessBuilder(shellPath(), wrapper.absolutePath)
            pb.environment().clear()
            for (entry in environment()) {
                val eq = entry.indexOf('=')
                if (eq > 0) pb.environment()[entry.substring(0, eq)] = entry.substring(eq + 1)
            }
            pb.directory(homeDir)
            pb.redirectErrorStream(true)
            val logFile = File(baseDir, "openbox.log")
            // L-53: stamp launch time for bench/measure-startup.sh
            logFile.appendText("[DeckOS] desktop-start ${System.currentTimeMillis()}\n")
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            val proc = pb.start()
            // L-53: poll at 50ms for faster PID detection (was 100ms)
            var waited = 0
            while (waited < 2000 && !pidFile.isFile && proc.isAlive) {
                Thread.sleep(50)
                waited += 50
            }
            if (!proc.isAlive) {
                Log.w(TAG, "Openbox exited immediately: rc=${proc.exitValue()}")
                return null
            }
            pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                ?: -1  // -1 means running but PID unknown — still considered success
        }.getOrElse { e ->
            Log.e(TAG, "Could not start desktop", e)
            null
        }
    }


    /** Run an apt command inside the DeckOS prefix, capturing output to apt.log. */
    private fun runApt(args: List<String>) {
        val prefix = prefixDir.absolutePath
        val cmd = mutableListOf(File(prefixDir, "bin/apt").absolutePath) + args
        val pb = ProcessBuilder(cmd)
        pb.environment().clear()
        for (entry in environment()) {
            val eq = entry.indexOf('=')
            if (eq > 0) pb.environment()[entry.substring(0, eq)] = entry.substring(eq + 1)
        }
        pb.environment()["DEBIAN_FRONTEND"] = "noninteractive"
        pb.environment()["APT_LISTCHANGES_FRONTEND"] = "none"
        pb.directory(homeDir)
        pb.redirectErrorStream(true)
        pb.redirectOutput(File(baseDir, "apt.log"))
        val proc = pb.start()
        val rc = proc.waitFor()
        if (rc != 0) {
            val tail = File(baseDir, "apt.log")
                .takeIf { it.isFile }
                ?.readLines()
                ?.takeLast(8)
                ?.joinToString("\n")
                ?: "(no log)"
            throw IOException("apt ${args.joinToString(" ")} failed (rc=$rc): $tail")
        }
    }

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
        val baseEnv = arrayOf(
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
            "TERMUX_VERSION=DeckOS 0.0.6",
            "TERMUX_APP_PACKAGE_MANAGER=apt",
            "DPKG_ADMINDIR=$prefix/var/lib/dpkg",
            "APT_CONFIG=$prefix/etc/apt/apt.conf.d/99-deckos-paths.conf",
            "ANDROID_DATA=${System.getenv("ANDROID_DATA") ?: "/data"}",
            "ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: "/system"}",
            "EXTERNAL_STORAGE=${System.getenv("EXTERNAL_STORAGE") ?: "/sdcard"}",
        )
        // L-63: Append GPU-specific env vars (opt-in: DECKOS_GPU=turnip|software)
        val gpuEnv = DeckOSGpuDetector.extraEnvArray(prefix)
        return if (gpuEnv.isEmpty()) baseEnv else baseEnv + gpuEnv
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
            relocateText(staging, prefixDir.absolutePath)
            installHook(staging)
            installDpkgWrapper(staging, prefixDir.absolutePath)
            writeAptConfig(staging, prefixDir.absolutePath)
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

    private fun relocateText(prefix: File, targetPrefix: String) {
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
                    text.replace(OLD_PREFIX, targetPrefix)
                        .replace(OLD_HOME, homeDir.absolutePath)
                        .toByteArray(Charsets.ISO_8859_1),
                )
                changed++
            }
        }
        Log.i(TAG, "Relocated $changed text files")
    }

    private fun repairStagedPaths() {
        val targetPrefix = prefixDir.absolutePath
        val stagedPrefix = "$targetPrefix.new"
        var changed = 0
        TEXT_DIRS.forEach { relative ->
            val dir = File(prefixDir, relative)
            if (!dir.exists()) return@forEach
            dir.walkTopDown().forEach { file ->
                if (!file.isFile || file.length() > 4 * 1024 * 1024) return@forEach
                if (isElf(file)) return@forEach
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                val text = String(bytes, Charsets.ISO_8859_1)
                if (!text.contains(stagedPrefix)) return@forEach
                file.writeBytes(
                    text.replace(stagedPrefix, targetPrefix).toByteArray(Charsets.ISO_8859_1),
                )
                changed++
            }
        }
        if (changed > 0) {
            Log.i(TAG, "Repaired $changed staged paths")
        }
    }

    private fun installHook(prefix: File) {
        val destination = File(prefix, "lib/libsocket_hook.so")
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "libsocket_hook.so.new")
        context.assets.open("libsocket_hook.so").use { input ->
            staging.outputStream().use { output -> input.copyTo(output) }
        }
        staging.setExecutable(true, false)
        destination.delete()
        if (!staging.renameTo(destination)) {
            staging.delete()
            throw IOException("Could not install path hook")
        }
    }

    private fun installDpkgWrapper(prefix: File, targetPrefix: String) {
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
            new_prefix="$targetPrefix"
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
            PREFIX="$targetPrefix"
            HOME="${homeDir.absolutePath}"
            TMPDIR="${tmpDir.absolutePath}"
            export PREFIX HOME TMPDIR
            export PATH="${'$'}PREFIX/bin:/system/bin:/system/xbin"
            export LD_LIBRARY_PATH="${'$'}PREFIX/lib"
            export LD_PRELOAD="${'$'}PREFIX/lib/libsocket_hook.so"
            export DECKOS_PREFIX="${'$'}PREFIX"
            export DPKG_ADMINDIR="${'$'}PREFIX/var/lib/dpkg"
            mkdir -p "${'$'}PREFIX/../.deckos-termux-root/files"
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

    private fun writeAptConfig(prefix: File, targetPrefix: String) {
        val directory = File(prefix, "etc/apt/apt.conf.d")
        directory.mkdirs()
        File(directory, "99-deckos-paths.conf").writeText(
            """
            Dir "$targetPrefix";
            Dir::Etc "$targetPrefix/etc/apt";
            Dir::State "$targetPrefix/var/lib/apt";
            Dir::State::dpkg "$targetPrefix/var/lib/dpkg";
            Dir::Cache "$targetPrefix/var/cache/apt";
            Dir::Log "$targetPrefix/var/log/apt";
            Dir::Bin::Methods "$targetPrefix/lib/apt/methods";
            Dir::Bin::dpkg "$targetPrefix/bin/dpkg";
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
        installHook(prefixDir)
        repairStagedPaths()
        installDpkgWrapper(prefixDir, prefixDir.absolutePath)
        writeAptConfig(prefixDir, prefixDir.absolutePath)
        prepareDirectories(prefixDir)
        linkRuntimeDirectories(prefixDir)
        ensureDesktopConfigFiles()
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

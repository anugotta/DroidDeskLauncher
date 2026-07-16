package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Real chroot-based Linux runtime for rooted Android devices.
 *
 * This runtime downloads a standard ARM64 Ubuntu rootfs, mounts the necessary
 * kernel filesystems via root access, and runs the desktop environment inside a
 * real chroot. X11 output is sent to the app's embedded LorieView X server
 * through a bind-mounted Unix socket.
 */
class ChrootRuntime(private val context: Context) {

    companion object {
        private const val TAG = "ChrootRuntime"
        private const val CHROOT_DE_MARKER = ".chroot_de_installed"

        // Ubuntu 24.04 ARM64 minimal rootfs
        const val ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
    }

    private val rootShell = RootShell(context)
    private val rootfsManager = RootfsManager(context)

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val x11HostDir: File get() = File(tmpDir, ".X11-unix")

    @Volatile
    private var sessionProcess: Process? = null

    // ── Status ──

    fun hasRoot(): Boolean = rootShell.hasRoot()

    fun isRootfsReady(): Boolean = rootfsManager.isRootfsReady()

    fun isDesktopInstalled(): Boolean {
        return File(rootfsDir, CHROOT_DE_MARKER).exists() ||
                File(rootfsDir, "usr/bin/startxfce4").exists()
    }

    fun isRunning(): Boolean = sessionProcess?.isAlive == true

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun getRootfsSizeMB(): Long = rootfsManager.getRootfsSizeMB()

    // ── Rootfs setup ──

    /**
     * Download the Ubuntu rootfs with progress callbacks.
     */
    fun downloadRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.downloadRootfs("ubuntu", onProgress)
    }

    /**
     * Extract the downloaded rootfs and configure it for chroot.
     */
    fun extractRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.extractRootfs { progress, status ->
            onProgress(progress, status)
            if (progress == 1.0) {
                // Additional chroot-specific configuration
                configureChrootRootfs()
            }
        }
    }

    private fun configureChrootRootfs() {
        Log.i(TAG, "Applying chroot-specific rootfs configuration")

        // Ensure critical mount points exist
        listOf(
            "dev", "dev/pts", "dev/shm",
            "proc", "sys", "run",
            "tmp", "tmp/.X11-unix", "tmp/runtime-root",
            "root", "mnt/android", "mnt/sdcard"
        ).forEach {
            File(rootfsDir, it).mkdirs()
        }

        // Hardware-accelerated environment profile
        File(rootfsDir, "etc/profile.d/droiddesk-ha.sh").apply {
            parentFile?.mkdirs()
            writeText(
                """
                #!/bin/bash
                # DroidDesk hardware-accelerated environment
                export DISPLAY=:0
                export XDG_RUNTIME_DIR=/tmp/runtime-root
                export XDG_SESSION_TYPE=x11
                export XDG_DATA_DIRS=/usr/share:/usr/local/share
                export XDG_CONFIG_DIRS=/etc/xdg

                # Mesa / Adreno performance
                export MESA_NO_ERROR=1
                export GALLIUM_DRIVER=llvmpipe
                export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
                export TU_DEBUG=noconform
                export MESA_VK_WSI_PRESENT_MODE=immediate

                # Prefer Vulkan turnip ICD if available
                if [ -f /usr/share/vulkan/icd.d/lvp_icd.aarch64.json ]; then
                    export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.aarch64.json
                fi

                # Disable accessibility bus spam
                export NO_AT_BRIDGE=1
                export GTK_A11Y=none

                # Locale
                export LANG=C.UTF-8
                export LC_ALL=C.UTF-8
                export LANGUAGE=C.UTF-8

                # Prompt
                export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                """.trimIndent()
            )
        }

        // Sources list for Ubuntu 24.04
        File(rootfsDir, "etc/apt/sources.list").writeText(
            """
            deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse
            deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse
            """.trimIndent().trim() + "\n"
        )

        // Make sure apt works without _apt sandbox user
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")

        Log.i(TAG, "Chroot rootfs configuration complete")
    }

    /**
     * Install the desktop environment and GPU drivers inside the chroot.
     */
    fun installDesktopEnvironment(
        desktopEnv: String = "xfce4",
        installType: String = "minimal",
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {}
    ) {
        if (!hasRoot()) {
            onProgress(-1.0, "Root access required for chroot mode")
            return
        }
        if (!isRootfsReady()) {
            onProgress(-1.0, "Rootfs not ready. Download and extract first.")
            return
        }

        thread(name = "chroot-de-install") {
            try {
                onProgress(0.0, "Mounting rootfs...")
                ensureMounts()

                onProgress(0.05, "Updating package lists...")
                execChroot("apt-get update -y", onLog)

                onProgress(0.1, "Installing core tools...")
                execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                            "locales ca-certificates wget curl dbus-x11",
                    onLog
                )

                onProgress(0.2, "Installing Mesa GPU drivers...")
                execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                            "mesa-vulkan-drivers mesa-opencl-icd libgl1-mesa-dri libglx-mesa0 vulkan-tools",
                    onLog
                )

                onProgress(0.4, "Installing desktop environment...")
                val dePackages = when (desktopEnv) {
                    "lxqt" -> "lxqt qterminal pcmanfm-qt featherpad"
                    "mate" -> "mate-desktop-environment mate-terminal"
                    "kde" -> "plasma-desktop konsole dolphin"
                    else -> "xfce4 xfce4-terminal xfce4-whiskermenu-plugin thunar mousepad"
                }
                execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends $dePackages",
                    onLog
                )

                onProgress(0.8, "Installing extras...")
                val extraPackages = if (installType == "full") {
                    "git nano htop wget curl python3 python3-pip nodejs npm libreoffice-writer libreoffice-calc gimp vlc firefox"
                } else {
                    "git nano htop wget curl python3"
                }
                execChroot(
                    "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends $extraPackages",
                    onLog
                )

                onProgress(0.9, "Cleaning up...")
                execChroot("apt-get clean", onLog)

                File(rootfsDir, CHROOT_DE_MARKER).writeText("$desktopEnv\n")
                onProgress(1.0, "$desktopEnv installed in chroot")
                Log.i(TAG, "Desktop environment installation complete")
            } catch (e: Exception) {
                Log.e(TAG, "DE install failed", e)
                onProgress(-1.0, "Installation failed: ${e.message}")
            }
        }
    }

    // ── Session management ──

    /**
     * Start the chrooted desktop session.
     * The caller should ensure the X11 socket directory is mounted before this.
     */
    fun startSession(desktopEnv: String = "xfce4", width: Int = 1920, height: Int = 1080) {
        if (!hasRoot()) {
            Log.e(TAG, "Cannot start chroot session without root")
            return
        }
        if (!isRootfsReady()) {
            Log.e(TAG, "Rootfs not ready")
            return
        }
        if (isRunning()) {
            Log.w(TAG, "Chroot session already running")
            return
        }

        ensureMounts()
        bindX11Socket()

        val deBin = when (desktopEnv) {
            "lxqt" -> "lxqt-session"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            "xfce4" -> "startxfce4"
            else -> desktopEnv
        }

        val runScript = """
            # Standard FHS PATH (inherited Android PATH lacks /usr/bin)
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            # Reset environment variables leaked from Android app
            export TMPDIR=/tmp
            export HOME=/root
            export PREFIX=/usr
            export LD_PRELOAD=/usr/local/lib/libclose_range_hack.so

            # Source DroidDesk environment
            . /etc/profile.d/droiddesk-ha.sh 2>/dev/null || true

            # Session D-Bus
            export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session
            rm -f /tmp/dbus-session
            dbus-daemon --session --address="${'$'}DBUS_SESSION_BUS_ADDRESS" --fork --nopidfile

            # Make sure X11 socket dir exists in case bind mount was late
            mkdir -p /tmp/.X11-unix

            echo "DIAG: Starting $desktopEnv in chroot on DISPLAY=:0 ..."
            exec dbus-run-session -- $deBin
        """.trimIndent()

        Log.i(TAG, "Starting chroot session for $desktopEnv")

        // Launch via ProcessBuilder through su so we get a Process handle we can monitor.
        val su = rootShell.findSuPath() ?: return
        val fullCommand = "chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(runScript)}"
        sessionProcess = ProcessBuilder(su, "-c", fullCommand)
            .redirectErrorStream(true)
            .start()

        Thread {
            val reader = sessionProcess!!.inputStream.bufferedReader()
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                Log.d(TAG, "CHROOT DESKTOP: " + String(buffer, 0, charsRead))
            }
        }.start()
    }

    /**
     * Stop the chroot session and unmount bind mounts.
     */
    fun stopSession() {
        Log.i(TAG, "Stopping chroot session...")
        sessionProcess?.let {
            try {
                it.destroyForcibly()
                it.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping session: ${e.message}")
            }
        }
        sessionProcess = null
        unmountAll()
        Log.i(TAG, "Chroot session stopped")
    }

    // ── Mount handling ──

    /**
     * Ensure /dev, /proc, /sys, /dev/pts and tmpfs mounts are active.
     */
    fun ensureMounts() {
        if (!hasRoot()) return

        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean {
            val absolute = File(rootfsDir, path).absolutePath
            return mounts.any { it.contains(" on $absolute ") }
        }

        mountIfNeeded("/dev", "--bind /dev") { isMounted("dev") }
        mountIfNeeded("/dev/pts", "--bind /dev/pts") { isMounted("dev/pts") }
        mountIfNeeded("/dev/shm", "-t tmpfs tmpfs") { isMounted("dev/shm") }
        mountIfNeeded("/proc", "--bind /proc") { isMounted("proc") }
        mountIfNeeded("/sys", "--bind /sys") { isMounted("sys") }
        mountIfNeeded("/run", "-t tmpfs tmpfs") { isMounted("run") }
        mountIfNeeded("/tmp", "-t tmpfs tmpfs") { isMounted("tmp") }

        // Create runtime dirs after tmpfs is mounted
        execChroot("mkdir -p /tmp/.X11-unix /tmp/runtime-root /root")
    }

    private fun mountIfNeeded(relative: String, mountArgs: String, alreadyMounted: () -> Boolean) {
        if (alreadyMounted()) return
        val target = File(rootfsDir, relative).absolutePath
        try {
            rootShell.exec("mkdir -p $target && mount $mountArgs $target")
            Log.i(TAG, "Mounted $target")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mount $target: ${e.message}")
        }
    }

    /**
     * Bind-mount the host X11 socket directory into the chroot.
     */
    fun bindX11Socket() {
        if (!hasRoot()) return
        x11HostDir.mkdirs()
        val chrootX11 = File(rootfsDir, "tmp/.X11-unix").absolutePath
        val hostX11 = x11HostDir.absolutePath

        // If already mounted, leave it
        val mounts = rootShell.exec("mount").lines()
        if (mounts.any { it.contains(" on $chrootX11 ") }) return

        rootShell.exec("mkdir -p $chrootX11 && mount --bind $hostX11 $chrootX11")
        Log.i(TAG, "Bound X11 socket: $hostX11 -> $chrootX11")
    }

    /**
     * Unmount all DroidDesk-related mounts.
     */
    fun unmountAll() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        val targets = listOf(
            File(rootfsDir, "tmp/.X11-unix").absolutePath,
            File(rootfsDir, "dev/pts").absolutePath,
            File(rootfsDir, "dev/shm").absolutePath,
            File(rootfsDir, "dev").absolutePath,
            File(rootfsDir, "proc").absolutePath,
            File(rootfsDir, "sys").absolutePath,
            File(rootfsDir, "run").absolutePath,
            File(rootfsDir, "tmp").absolutePath
        )
        // Unmount in reverse order, be tolerant of busy mounts
        targets.reversed().forEach { target ->
            if (mounts.any { it.contains(" on $target ") }) {
                try {
                    rootShell.exec("umount -l $target 2>/dev/null || umount $target 2>/dev/null || true")
                    Log.i(TAG, "Unmounted $target")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unmount $target: ${e.message}")
                }
            }
        }
    }

    // ── Command execution inside chroot ──

    /**
     * Execute a command inside the chroot as root.
     */
    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (!hasRoot()) return "Error: root access required"
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        return if (onOutput != null) {
            val code = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
                onOutput(chunk)
            }
            "Exit code: $code"
        } else {
            rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}")
        }
    }

    private fun execChroot(command: String, onLog: (String) -> Unit = {}) {
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        val output = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
            onLog(chunk)
        }
        Log.d(TAG, "chroot command exit code: $output")
    }

    private fun shellQuote(input: String): String {
        // Use a single-quoted string that handles embedded single quotes safely
        return "'" + input.replace("'", "'\"'\"'") + "'"
    }
}

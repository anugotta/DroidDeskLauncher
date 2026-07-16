package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Core Linux runtime engine.
 *
 * Runs native Termux Linux on Android without PRoot.
 * Uses a C hook (libsocket_hook.so) via LD_PRELOAD to redirect file operations
 * and Unix socket connections from /data/data/com.termux/files/usr to DroidDesk's prefix.
 */
class LinuxRuntime(private val context: Context) {

    companion object {
        private const val TAG = "LinuxRuntime"
        private const val BOOTSTRAP_MARKER = ".bootstrap_extracted"
        private const val SHEBANG_MARKER = ".shebangs_patched"
        private const val ELF_PATCH_MARKER = ".elf_runpaths_patched"
        private const val DE_MARKER = ".de_installed"

        // ELF64 constants
        private const val ELFMAG0: Byte = 0x7f
        private const val ELFMAG1: Byte = 'E'.code.toByte()
        private const val ELFMAG2: Byte = 'L'.code.toByte()
        private const val ELFMAG3: Byte = 'F'.code.toByte()
        private const val ELFCLASS64: Byte = 2
        private const val ELFDATA2LSB: Byte = 1

        private const val PT_LOAD = 1
        private const val PT_DYNAMIC = 2

        private const val DT_NULL = 0L
        private const val DT_STRTAB = 5L
        private const val DT_STRSZ = 10L
        private const val DT_RPATH = 15L
        private const val DT_RUNPATH = 29L

        private const val EI_MAG0 = 0
        private const val EI_MAG1 = 1
        private const val EI_MAG2 = 2
        private const val EI_MAG3 = 3
        private const val EI_CLASS = 4
        private const val EI_DATA = 5

        private const val E_PHOFF_OFFSET = 32
        private const val E_PHENTSIZE_OFFSET = 54
        private const val E_PHNUM_OFFSET = 56

        private const val P_TYPE_OFFSET = 0
        private const val P_OFFSET_OFFSET = 8
        private const val P_VADDR_OFFSET = 16
        private const val P_FILESZ_OFFSET = 32
        private const val PH_SIZE = 56

        private const val D_TAG_OFFSET = 0
        private const val D_VAL_OFFSET = 8
        private const val DYN_SIZE = 16
    }

    private var sessionProcess: Process? = null
    @Volatile private var activeCommandProcess: Process? = null

    // ── Base directories (all inside app's private storage) ──

    private val baseDir: File get() = context.filesDir
    private val prefixDir: File get() = File(baseDir, "usr")
    private val binDir: File get() = File(prefixDir, "bin")
    private val libDir: File get() = File(prefixDir, "lib")
    private val tmpDir: File get() = File(prefixDir, "tmp")
    private val homeDir: File get() = File(baseDir, "home")

    // ── Status ──

    fun isBootstrapped(): Boolean {
        return File(baseDir, BOOTSTRAP_MARKER).exists() && File(prefixDir, "bin/bash").exists()
    }

    fun isRunning(): Boolean {
        return sessionProcess?.isAlive == true
    }

    fun getInstalledDE(): String {
        return if (File(prefixDir, DE_MARKER).exists() || File(prefixDir, "bin/startxfce4").exists()) "xfce4" else ""
    }

    // ── Bootstrap ──

    fun setupBootstrap() {
        Log.i(TAG, "Setting up bootstrap environment...")
        listOf(prefixDir, binDir, libDir, tmpDir, homeDir).forEach { it.mkdirs() }
        Log.i(TAG, "Bootstrap directories ready. Base: ${baseDir.absolutePath}")
    }

    fun extractBootstrapIfNeeded(context: Context) {
        val bashBin = File(prefixDir, "bin/bash")
        if (bashBin.exists()) {
            Log.i(TAG, "Bootstrap already extracted at ${prefixDir.absolutePath}")
            // Refresh wrapper/config in case the app was updated
            createAptConfigOverride()
            ensureAptDirectories()
            wrapDpkgForPath()
            wrapUpdateAlternatives()
            ensureSocketHookPrebuilt()
            return
        }

        val marker = File(baseDir, BOOTSTRAP_MARKER)
        if (marker.exists()) {
            marker.delete()
        }

        Log.i(TAG, "Extracting bootstrap from assets to ${prefixDir.absolutePath}...")

        // Remove any partial extraction to ensure a clean bootstrap
        if (prefixDir.exists()) {
            prefixDir.deleteRecursively()
        }
        prefixDir.mkdirs()

        // Flutter assets are located under "flutter_assets/" in the APK asset tree
        val assetName = "flutter_assets/assets/bootstrap-aarch64.zip"
        val tmpZip = File(tmpDir, "bootstrap-aarch64.zip")
        tmpZip.parentFile?.mkdirs()

        try {
            context.assets.open(assetName).use { input ->
                tmpZip.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy bootstrap asset: ${e.message}", e)
            return
        }

        // Extract the bootstrap so the top-level bin/lib/etc directories land under prefixDir/usr
        extractZip(tmpZip, prefixDir)

        // Restore symlinks from SYMLINKS.txt
        restoreSymlinks(prefixDir)

        // Create apt config override so apt uses our prefix
        createAptConfigOverride()

        // Ensure apt cache/state directories exist (bootstrap zip may omit empty dirs)
        ensureAptDirectories()

        // Set executable permissions on binaries
        setExecutableRecursively(binDir)
        setExecutableRecursively(File(prefixDir, "libexec"))

        // Wrap dpkg so it and its children (e.g. dpkg-split) always see the right PATH/env
        wrapDpkgForPath()

        // Wrap update-alternatives so its postinst invocations don't fail under
        // our relocated root (it otherwise double-prefixes absolute paths).
        wrapUpdateAlternatives()

        // Copy prebuilt socket hook from jniLibs to prefix/lib
        ensureSocketHookPrebuilt()

        marker.writeText("DroidDesk native bootstrap\n")
        tmpZip.delete()
        Log.i(TAG, "Bootstrap extraction complete")
    }

    private fun ensureSocketHookPrebuilt() {
        try {
            val libDir = File(prefixDir, "lib")
            libDir.mkdirs()
            val destHook = File(libDir, "libsocket_hook.so")
            if (destHook.exists()) return

            // Find the hook in jniLibs
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val jniDir = File(context.applicationInfo.nativeLibraryDir)
            val srcHook = File(jniDir, "libsocket_hook.so")
            if (srcHook.exists()) {
                srcHook.inputStream().use { input ->
                    destHook.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destHook.setExecutable(true, false)
                Log.i(TAG, "Copied prebuilt socket hook to ${destHook.absolutePath}")
            } else {
                Log.w(TAG, "Prebuilt socket hook not found in jniLibs at ${srcHook.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy prebuilt socket hook: ${e.message}")
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun createAptConfigOverride() {
        try {
            val aptConfDir = File(prefixDir, "etc/apt/apt.conf.d")
            aptConfDir.mkdirs()
            val confFile = File(aptConfDir, "99-droiddesk-paths.conf")
            confFile.writeText(
                """
                Dir "${prefixDir.absolutePath}";
                Dir::Etc "${prefixDir.absolutePath}/etc/apt";
                Dir::State "${prefixDir.absolutePath}/var/lib/apt";
                Dir::State::dpkg "${prefixDir.absolutePath}/var/lib/dpkg";
                Dir::Cache "${prefixDir.absolutePath}/var/cache/apt";
                Dir::Log "${prefixDir.absolutePath}/var/log/apt";
                Dir::Bin::Methods "${prefixDir.absolutePath}/lib/apt/methods";
                Dir::Bin::dpkg "${prefixDir.absolutePath}/bin/dpkg";
                Dir::Bin::apt-key "${prefixDir.absolutePath}/bin/apt-key";
                Acquire::gpgv::Options:: "--homedir=${prefixDir.absolutePath}/etc/apt/trusted.gpg.d";
                """.trimIndent()
            )
            Log.i(TAG, "Created apt config override at ${confFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create apt config override: ${e.message}")
        }
    }

    private fun wrapDpkgForPath() {
        try {
            val dpkgBin = File(prefixDir, "bin/dpkg")
            val dpkgReal = File(prefixDir, "bin/dpkg.real")

            // Build a mini root tree so dpkg can use Termux-style paths internally
            // while the actual files land in our private prefix.
            val dpkgRoot = File(baseDir, "dpkgroot")
            mapOf(
                File(dpkgRoot, "data/data/com.termux/files/usr") to prefixDir.absolutePath,
                File(dpkgRoot, "var/lib/dpkg") to File(prefixDir, "var/lib/dpkg").absolutePath,
                File(dpkgRoot, "tmp") to tmpDir.absolutePath
            ).forEach { (linkDir, target) ->
                linkDir.parentFile?.mkdirs()
                // Remove an existing symlink so we can update its target on app upgrades.
                if (linkDir.exists()) {
                    linkDir.delete()
                }
                if (!linkDir.exists()) {
                    android.system.Os.symlink(target, linkDir.absolutePath)
                }
            }

            // Make sure the real dpkg binary is saved as dpkg.real, then always
            // rewrite the wrapper so updates take effect on app upgrade/reinstall.
            if (!dpkgReal.exists() && dpkgBin.exists()) {
                dpkgBin.renameTo(dpkgReal)
            }

            val wrapper = """
                #!/system/bin/sh
                export PATH="${prefixDir.absolutePath}/bin:${'$'}PATH"
                export LD_LIBRARY_PATH="${prefixDir.absolutePath}/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
                export LD_PRELOAD="${prefixDir.absolutePath}/lib/libsocket_hook.so${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}"
                # dpkg requires admindir to be inside root. Strip any caller-provided
                # --root/--admindir (and their values) and prepend our own before any
                # trailing filenames/apt separators so dpkg parses them as options.
                args=""
                while [ ${'$'}# -gt 0 ]; do
                    case "${'$'}1" in
                        --admindir=*|--root=*)
                            ;;
                        --admindir|--root)
                            shift
                            ;;
                        *)
                            args="${'$'}args ${'$'}1"
                            ;;
                    esac
                    shift
                done
                exec "${dpkgReal.absolutePath}" --force-not-root --force-script-chrootless --root="${dpkgRoot.absolutePath}" --admindir="${dpkgRoot.absolutePath}/var/lib/dpkg" ${'$'}args
            """.trimIndent()

            dpkgBin.writeText(wrapper)
            dpkgBin.setExecutable(true, false)
            Log.i(TAG, "Installed dpkg wrapper at ${dpkgBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install dpkg wrapper: ${e.message}")
        }
    }

    private fun wrapUpdateAlternatives() {
        try {
            val uaBin = File(prefixDir, "bin/update-alternatives")
            val uaReal = File(prefixDir, "bin/update-alternatives.real")

            if (!uaBin.exists() || uaReal.exists()) return

            uaBin.renameTo(uaReal)
            uaBin.writeText(
                """
                #!/system/bin/sh
                # update-alternatives is not needed for DroidDesk's single-prefix
                # environment and fails when dpkg is run with a relocated root.
                exit 0
                """.trimIndent()
            )
            uaBin.setExecutable(true, false)
            Log.i(TAG, "Installed update-alternatives wrapper at ${uaBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install update-alternatives wrapper: ${e.message}")
        }
    }

    private fun ensureAptDirectories() {
        listOf(
            "var/cache/apt/archives/partial",
            "var/lib/apt/lists/partial",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/parts",
            "var/lib/dpkg/triggers",
            "var/log/apt"
        ).forEach { relativePath ->
            File(prefixDir, relativePath).mkdirs()
        }
        // dpkg requires these files to exist even if empty
        listOf(
            "var/lib/dpkg/status",
            "var/lib/dpkg/available",
            "var/lib/dpkg/diversions"
        ).forEach { relativePath ->
            val f = File(prefixDir, relativePath)
            if (!f.exists()) f.createNewFile()
        }
        Log.i(TAG, "Ensured apt/dpkg cache/state directories exist")
    }

    private fun setExecutableRecursively(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                file.setExecutable(true, false)
            }
        }
    }

    private fun restoreSymlinks(prefixDir: File) {
        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (!symlinksFile.exists()) {
            Log.w(TAG, "SYMLINKS.txt not found, skipping symlink restoration")
            return
        }

        val termuxPrefix = "/data/data/com.termux/files/usr"
        var created = 0
        var failed = 0
        symlinksFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            // Format: /data/data/com.termux/files/usr/.../target←./linkpath
            val arrow = "\u2190"
            val parts = trimmed.split(arrow)
            if (parts.size != 2) return@forEach

            val targetPath = parts[0].trim()
            val linkPath = parts[1].trim()

            // linkPath is relative to prefix root (starts with ./)
            val cleanLinkPath = if (linkPath.startsWith("./")) linkPath.substring(2) else linkPath
            val linkFile = File(prefixDir, cleanLinkPath)

            // Rewrite target from Termux prefix to app prefix;
            // bare filenames are kept relative so the symlink resolves correctly.
            val newTarget = when {
                targetPath.startsWith(termuxPrefix) -> {
                    prefixDir.absolutePath + targetPath.substring(termuxPrefix.length)
                }
                targetPath.startsWith("/") -> targetPath
                else -> targetPath
            }

            try {
                if (linkFile.exists()) {
                    linkFile.deleteRecursively()
                }
                linkFile.parentFile?.mkdirs()
                android.system.Os.symlink(newTarget, linkFile.absolutePath)
                created++
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Failed to create symlink ${linkFile.absolutePath} -> $newTarget: ${e.message}")
            }
        }

        Log.i(TAG, "Restored $created symlinks, $failed failed")
    }

    // ── Shebang Patching ──
    fun patchShebangs(force: Boolean = false) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath

        if (oldPrefix == newPrefix) return

        val markerFile = File(prefixDir, SHEBANG_MARKER)
        if (!force && markerFile.exists()) {
            Log.i(TAG, "Shebangs already patched, skipping")
            return
        }

        Log.i(TAG, "Patching shebangs: $oldPrefix -> $newPrefix")
        var patchCount = 0

        val dirsToScan = listOf("bin", "libexec", "share", "etc", "var/lib/dpkg/info")
        for (dirName in dirsToScan) {
            val dir = File(prefixDir, dirName)
            if (!dir.exists()) continue

            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.canRead()) {
                    try {
                        val bytes = file.inputStream().use {
                            val buf = ByteArray(256)
                            val n = it.read(buf)
                            if (n > 0) buf.copyOf(n) else ByteArray(0)
                        }

                        if (bytes.size >= 2 && bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()) {
                            val content = file.readText()
                            if (content.contains(oldPrefix)) {
                                val updated = content.replace(oldPrefix, newPrefix)
                                file.writeText(updated)
                                patchCount++
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore binary or read-only files
                    }
                }
            }
        }
        markerFile.writeText("done")
        Log.i(TAG, "Patched $patchCount scripts.")
    }

    // ── ELF RUNPATH/RPATH Patching ──

    fun patchElfRunpaths(prefixDir: File) {
        val marker = File(prefixDir, ELF_PATCH_MARKER)
        if (marker.exists()) {
            Log.i(TAG, "ELF runpaths already patched, skipping")
            return
        }

        val libDir = File(prefixDir, "lib")
        if (!libDir.exists()) {
            Log.w(TAG, "lib directory not found, skipping ELF patch")
            return
        }

        val oldPath = "/data/data/com.termux/files/usr/lib"
        val newPath = libDir.absolutePath
        val driOldPath = "/data/data/com.termux/files/usr/lib/dri"

        var patched = 0
        libDir.walkTopDown()
            .filter { it.isFile && it.canRead() && isElf64(it) }
            .forEach { file ->
                try {
                    if (patchElfFile(file, oldPath, newPath, driOldPath)) {
                        patched++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch ${file.absolutePath}: ${e.message}")
                }
            }

        marker.writeText("patched $patched files")
        Log.i(TAG, "Patched ELF runpaths in $patched files")
    }

    private fun isElf64(file: File): Boolean {
        if (file.length() < 6) return false
        return file.inputStream().use { input ->
            val buf = ByteArray(6)
            val n = input.read(buf)
            if (n < 6) return@use false
            buf[EI_MAG0] == ELFMAG0 &&
                    buf[EI_MAG1] == ELFMAG1 &&
                    buf[EI_MAG2] == ELFMAG2 &&
                    buf[EI_MAG3] == ELFMAG3 &&
                    buf[EI_CLASS] == ELFCLASS64 &&
                    buf[EI_DATA] == ELFDATA2LSB
        }
    }

    private fun patchElfFile(file: File, oldPath: String, newPath: String, driOldPath: String): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 64) return false

        val ePhoff = getLongLe(bytes, E_PHOFF_OFFSET)
        val ePhentsize = getShortLe(bytes, E_PHENTSIZE_OFFSET).toInt() and 0xFFFF
        val ePhnum = getShortLe(bytes, E_PHNUM_OFFSET).toInt() and 0xFFFF

        if (ePhoff < 0 || ePhoff + ePhnum * ePhentsize.toLong() > bytes.size) return false

        var strTabAddr: Long? = null
        var strTabSize: Long? = null
        val runpathOffsets = mutableListOf<Long>()
        val rpathOffsets = mutableListOf<Long>()

        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_DYNAMIC) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (pOffset < 0 || pOffset + pFilesz > bytes.size) continue

                val dynCount = pFilesz / DYN_SIZE
                for (j in 0 until dynCount) {
                    val dynOffset = (pOffset + j * DYN_SIZE).toInt()
                    val dTag = getLongLe(bytes, dynOffset + D_TAG_OFFSET)
                    val dVal = getLongLe(bytes, dynOffset + D_VAL_OFFSET)
                    when (dTag) {
                        DT_STRTAB -> strTabAddr = dVal
                        DT_STRSZ -> strTabSize = dVal
                        DT_RPATH -> rpathOffsets.add(dVal)
                        DT_RUNPATH -> runpathOffsets.add(dVal)
                        DT_NULL -> break
                    }
                }
            }
        }

        if (strTabAddr == null || strTabSize == null || strTabSize <= 0) return false

        var strTabFileOffset: Long? = null
        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_LOAD) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pVaddr = getLongLe(bytes, phOffset + P_VADDR_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (strTabAddr >= pVaddr && strTabAddr < pVaddr + pFilesz) {
                    strTabFileOffset = pOffset + (strTabAddr - pVaddr)
                    break
                }
            }
        }

        if (strTabFileOffset == null) return false

        val origin = "\${ORIGIN}"
        val replacement = if (newPath.length <= oldPath.length) newPath else origin
        var modified = false

        for (offset in runpathOffsets + rpathOffsets) {
            val fileOffset = (strTabFileOffset + offset).toInt()
            if (fileOffset < 0 || fileOffset >= bytes.size) continue
            val endOffset = minOf((strTabFileOffset + strTabSize).toInt(), bytes.size)
            val current = readNullTerminatedString(bytes, fileOffset, endOffset)

            when {
                current == driOldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, origin, current.length)
                    modified = true
                }
                current == oldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, replacement, current.length)
                    modified = true
                }
                current.contains(driOldPath) -> {
                    val replaced = current.replace(driOldPath, origin)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
                current.contains(oldPath) -> {
                    val replaced = current.replace(oldPath, replacement)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
            }
        }

        if (modified) {
            file.writeBytes(bytes)
            file.setExecutable(true, false)
            return true
        }
        return false
    }

    private fun readNullTerminatedString(bytes: ByteArray, start: Int, end: Int): String {
        var i = start
        while (i < end && bytes[i] != 0.toByte()) i++
        return String(bytes, start, i - start, Charsets.UTF_8)
    }

    private fun writeNullTerminatedString(bytes: ByteArray, offset: Int, value: String, padTo: Int) {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        for (i in valueBytes.indices) {
            bytes[offset + i] = valueBytes[i]
        }
        for (i in valueBytes.size until padTo) {
            bytes[offset + i] = 0
        }
    }

    private fun getLongLe(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }

    private fun getIntLe(bytes: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 3 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return result
    }

    private fun getShortLe(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset + 1].toInt() and 0xFF) shl 8 or (bytes[offset].toInt() and 0xFF)).toShort()
    }

    // ── C Socket Hook Native Compilation ──
    fun compileSocketHook() {
        val hookC = File(tmpDir, "socket_hook.c")
        val hookBuildC = File(tmpDir, "socket_hook_build.c")
        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        File(prefixDir, "lib").mkdirs()

        try {
            context.assets.open("flutter_assets/assets/socket_hook.c").use { input ->
                hookC.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy socket_hook.c asset: ${e.message}")
            return
        }

        hookBuildC.writeText(
            """
            #define _GNU_SOURCE
            #define NEW_PREFIX "${prefixDir.absolutePath}"
            #include "${hookC.absolutePath}"
            """.trimIndent()
        )

        val clang = File(prefixDir, "bin/clang")
        if (clang.exists()) {
            Log.i(TAG, "Compiling socket_hook.c natively using clang...")
            val compileCmd = listOf(
                clang.absolutePath,
                "-shared", "-fPIC",
                hookBuildC.absolutePath,
                "-I", tmpDir.absolutePath,
                "-o", hookSo.absolutePath,
                "-ldl", "-llog"
            )
            try {
                val pb = ProcessBuilder(compileCmd)
                    .redirectErrorStream(true)
                    .also {
                        it.environment().clear()
                        it.environment()["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
                        it.environment()["PATH"] = "${prefixDir.absolutePath}/bin:${System.getenv("PATH")}"
                        it.environment()["TMPDIR"] = tmpDir.absolutePath
                    }
                val process = pb.start()
                val log = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.i(TAG, "Native compilation of libsocket_hook.so successful!")
                } else {
                    Log.e(TAG, "Native compilation failed (code $exitCode): $log")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error compiling natively: ${e.message}")
            }
        } else {
            Log.w(TAG, "clang binary not found yet. Cannot compile socket_hook.c.")
        }
    }

    // ── Environment Configuration ──

    private fun getTermuxEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["EXTERNAL_STORAGE"] = System.getenv("EXTERNAL_STORAGE") ?: "/sdcard"

        env["PREFIX"] = prefixDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
        env["PATH"] = "${prefixDir.absolutePath}/bin:${System.getenv("PATH")}"
        env["HOME"] = homeDir.absolutePath
        env["LANG"] = "en_US.UTF-8"

        env["DISPLAY"] = ":0"
        env["XDG_RUNTIME_DIR"] = tmpDir.absolutePath

        env["PERL5LIB"] = "${prefixDir.absolutePath}/lib/perl5/core_perl:${prefixDir.absolutePath}/lib/perl5/site_perl:${prefixDir.absolutePath}/lib/perl5/vendor_perl:${prefixDir.absolutePath}/lib/perl5"
        env["PYTHONHOME"] = prefixDir.absolutePath
        env["PIP_CONFIG_FILE"] = "${prefixDir.absolutePath}/etc/pip.conf"
        env["XDG_DATA_DIRS"] = "${prefixDir.absolutePath}/share"
        env["XDG_CONFIG_DIRS"] = "${prefixDir.absolutePath}/etc/xdg"
        env["GDK_PIXBUF_MODULEDIR"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders"
        env["GDK_PIXBUF_MODULE_FILE"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache"

        // GPU acceleration for Adreno (Zink + Freedreno)
        env["VK_ICD_FILENAMES"] = "${prefixDir.absolutePath}/share/vulkan/icd.d/freedreno_icd.aarch64.json"
        env["LIBGL_DRIVERS_PATH"] = "${prefixDir.absolutePath}/lib/dri"
        env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
        env["GALLIUM_DRIVER"] = "zink"
        env["MESA_GL_VERSION_OVERRIDE"] = "4.6"
        env["MESA_GLES_VERSION_OVERRIDE"] = "3.2"
        env["MESA_NO_ERROR"] = "1"
        env["TU_DEBUG"] = "noconform"
        env["ZINK_DESCRIPTORS"] = "lazy"
        env["MESA_VK_WSI_PRESENT_MODE"] = "immediate"

        env["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=${tmpDir.absolutePath}/dbus-session"

        env["DPKG_ADMINDIR"] = "${prefixDir.absolutePath}/var/lib/dpkg"
        env["APT_CONFIG"] = "${prefixDir.absolutePath}/etc/apt/apt.conf.d/99-droiddesk-paths.conf"

        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        if (hookSo.exists()) {
            env["LD_PRELOAD"] = hookSo.absolutePath
        }

        return env
    }

    // ── Native Package Installation ──

    private fun installRepoPackages(): Boolean {
        val pkgs = listOf("x11-repo", "tur-repo")

        // Ensure main package list is up to date before downloading the repo packages.
        if (executeCommand("apt-get update").startsWith("Error:")) {
            Log.e(TAG, "apt-get update failed before installing repo packages")
            return false
        }

        // Download the .debs to the prefix root.
        val downloadCmd = "cd \"${prefixDir.absolutePath}\" && apt-get download ${pkgs.joinToString(" ")}"
        if (executeCommand(downloadCmd).startsWith("Error:")) {
            Log.e(TAG, "Failed to download x11-repo/tur-repo .debs")
            return false
        }

        // Unpack without configuring so we can edit the maintainer scripts first.
        val debs = pkgs.joinToString(" ") { "${it}_*.deb" }
        if (executeCommand("dpkg --unpack $debs").startsWith("Error:")) {
            Log.e(TAG, "Failed to unpack x11-repo/tur-repo .debs")
            return false
        }

        // Replace the postinst scripts with no-ops. The originals just run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter.
        for (pkg in pkgs) {
            val postinst = File(prefixDir, "var/lib/dpkg/info/$pkg.postinst")
            postinst.writeText("#!/system/bin/sh\nexit 0\n")
            postinst.setExecutable(true, false)
        }

        // Now configure the repo packages and refresh apt's package lists.
        if (executeCommand("dpkg --configure ${pkgs.joinToString(" ")}").startsWith("Error:")) {
            Log.e(TAG, "Failed to configure x11-repo/tur-repo")
            return false
        }
        if (executeCommand("pkg update -y").startsWith("Error:")) {
            Log.e(TAG, "Failed to update package lists after installing repo packages")
            return false
        }

        return true
    }

    private fun installPackageGroup(cmd: String): Boolean {
        // pkg install downloads, unpacks and configures in one go. Newly unpacked
        // maintainer scripts still contain the original Termux shebang, so after
        // the command finishes (successfully or not) we patch them and run a
        // configuration pass to finish any half-configured packages.
        Log.i(TAG, "Running: $cmd")
        executeCommand(cmd)
        patchShebangs(force = true)
        val configureOutput = executeCommand("dpkg --configure -a")
        return !configureOutput.startsWith("Error:")
    }

    fun installDesktopEnvironmentNative(): Boolean {
        val marker = File(prefixDir, DE_MARKER)

        if (marker.exists()) {
            Log.i(TAG, "Desktop environment already installed")
            return true
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot install DE — bootstrap not extracted")
            return false
        }

        patchShebangs()
        patchElfRunpaths(prefixDir)
        compileSocketHook()

        // Install the x11/tur repository packages. Their postinst scripts run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter, so we
        // unpack the .debs, neutralise the postinst scripts, configure them, and
        // then update the package lists ourselves.
        if (!installRepoPackages()) {
            Log.e(TAG, "Failed to install x11-repo/tur-repo")
            return false
        }

        // Finish configuring anything left over from a previous run, then install
        // the desktop, GPU drivers, and build tools. Each install is followed by a
        // shebang patch + configure pass so postinst scripts find our prefix.
        if (!installPackageGroup("dpkg --configure -a")) {
            Log.e(TAG, "Initial dpkg --configure -a failed")
            return false
        }
        if (!installPackageGroup("pkg update -y")) {
            Log.e(TAG, "pkg update failed")
            return false
        }
        if (!installPackageGroup("pkg install -y xfce4 xfce4-terminal xfce4-whiskermenu-plugin thunar mousepad")) {
            Log.e(TAG, "XFCE4 package install failed")
            return false
        }
        if (!installPackageGroup("pkg install -y mesa-zink mesa-vulkan-icd-freedreno vulkan-loader-android")) {
            Log.e(TAG, "Mesa/Vulkan package install failed")
            return false
        }
        if (!installPackageGroup("pkg install -y clang")) {
            Log.e(TAG, "clang package install failed")
            return false
        }

        marker.writeText("done")
        Log.i(TAG, "Desktop environment installation complete")
        return true
    }

    // ── Session Management ──

    fun startSession(desktopEnv: String = "xfce4", mode: String = "x11", width: Int = 1920, height: Int = 1080) {
        extractBootstrapIfNeeded(context)

        if (isRunning()) {
            Log.w(TAG, "Session already running")
            return
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot start session — not bootstrapped")
            return
        }

        patchShebangs()
        patchElfRunpaths(prefixDir)
        compileSocketHook()

        val oldSocket = File(tmpDir, ".X11-unix/X0")
        if (oldSocket.exists()) {
            oldSocket.delete()
            Log.i(TAG, "Deleted stale X11 socket file before launching session")
        }

        // Start a session dbus-daemon if available
        val dbusSocket = File(tmpDir, "dbus-session")
        try {
            if (dbusSocket.exists()) dbusSocket.delete()
            val dbusCmd = listOf(
                File(prefixDir, "bin/dbus-daemon").absolutePath,
                "--session",
                "--address=unix:path=${dbusSocket.absolutePath}",
                "--fork",
                "--nopidfile"
            )
            ProcessBuilder(dbusCmd)
                .directory(homeDir.apply { mkdirs() })
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()
            Log.i(TAG, "Started session dbus-daemon")
        } catch (e: Exception) {
            Log.w(TAG, "dbus-daemon start failed, falling back to dbus-launch: ${e.message}")
        }

        val runScript = """
            # ── Disable AT-SPI accessibility bus ──
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export DISPLAY=:0

            # Use the session bus DroidDesk already started
            export DBUS_SESSION_BUS_ADDRESS="unix:path=${dbusSocket.absolutePath}"

            # Launch XFCE4 Session natively
            echo "DIAG: Launching startxfce4 natively on DISPLAY=:0 ..."

            if [ -x "${prefixDir.absolutePath}/bin/startxfce4" ]; then
                exec startxfce4
            else
                exec xfce4-session
            fi
        """.trimIndent()

        Log.i(TAG, "Starting native Termux session for $desktopEnv")

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val command = listOf(bashBin, "-c", runScript)

        sessionProcess = ProcessBuilder(command)
            .directory(homeDir.apply { mkdirs() })
            .redirectErrorStream(true)
            .also { pb ->
                pb.environment().clear()
                pb.environment().putAll(getTermuxEnv())
            }
            .start()

        Thread {
            val reader = java.io.InputStreamReader(sessionProcess!!.inputStream)
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                Log.d(TAG, "DESKTOP: " + String(buffer, 0, charsRead))
            }
        }.start()

        Log.i(TAG, "Termux session started")
    }

    fun stopSession() {
        Log.i(TAG, "Stopping Linux session...")
        sessionProcess?.let {
            it.destroyForcibly()
            it.waitFor()
        }
        sessionProcess = null
        Log.i(TAG, "Session stopped")
    }

    // ── Command Execution ──

    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        activeCommandProcess?.let { process ->
            try {
                Log.d(TAG, "Routing input to active command: $command")
                val os = process.outputStream
                os.write((command + "\n").toByteArray())
                os.flush()
                return ""
            } catch (e: Exception) {
                Log.w(TAG, "Active process closed or failed to receive input: ${e.message}")
            }
        }

        if (!isBootstrapped()) return "Error: Runtime not bootstrapped"

        compileSocketHook()

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val fullCommand = listOf(bashBin, "-c", command)

        return try {
            Log.d(TAG, "Executing command natively: $command")

            val process = ProcessBuilder(fullCommand)
                .directory(prefixDir)
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()

            activeCommandProcess = process

            val output = StringBuilder()
            val reader = java.io.InputStreamReader(process.inputStream)
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                val chunk = String(buffer, 0, charsRead)
                Log.d(TAG, "CHUNK: $chunk")
                output.append(chunk)
                onOutput?.invoke(chunk)
            }
            process.waitFor()
            activeCommandProcess = null
            Log.d(TAG, "Command finished with exit code: ${process.exitValue()}")

            if (process.exitValue() != 0) {
                throw Exception("Command failed with exit code ${process.exitValue()}. Output: \n$output")
            }

            output.toString()
        } catch (e: Exception) {
            activeCommandProcess = null
            Log.e(TAG, "Command execution failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    fun interruptCommand() {
        activeCommandProcess?.let {
            Log.d(TAG, "Interrupting active command...")
            it.destroy()
        }
        activeCommandProcess = null
    }
}

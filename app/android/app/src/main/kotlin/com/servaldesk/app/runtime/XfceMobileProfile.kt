package com.servaldesk.app.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import java.io.File

/**
 * Touch-friendlier XFCE defaults with a unique ServalDesk visual theme
 * (midnight + gold + violet), plus a docked/external-monitor layout.
 *
 * Profile installs are atomic: the marker is written only after all files succeed,
 * so a failed upgrade cannot leave a half-broken session (black desktop on restart).
 *
 * Layout: top tasklist/clock bar + bottom application dock (not a vertical dock).
 */
object XfceMobileProfile {
    private const val TAG = "XfceMobileProfile"
    private const val PROFILE_MARKER = ".droiddesk-xfce-mobile-v27"
    private const val WALLPAPER_ASSET = "droiddesk/ubuntu-touch-wallpaper.jpg"
    private const val WALLPAPER_DIR_ASSET = "droiddesk/wallpapers"
    private const val SERVAL_WALLPAPER = "serval-dusk.jpg"

    fun install(
        context: Context,
        homeDir: File,
        wallpaperDir: File,
        wallpaperPathPrefixInSession: String = wallpaperDir.absolutePath,
        binDir: File? = null,
    ): Boolean {
        val marker = File(homeDir, PROFILE_MARKER)
        if (marker.exists()) return true

        return try {
            val defaultWallpaper = installWallpapers(context, wallpaperDir)
            val defaultPathInSession =
                "$wallpaperPathPrefixInSession/${defaultWallpaper.name}"

            val xfconfDir = File(
                homeDir,
                ".config/xfce4/xfconf/xfce-perchannel-xml",
            ).apply { mkdirs() }
            File(xfconfDir, "xfce4-panel.xml").writeText(panelConfig())
            File(xfconfDir, "xfce4-desktop.xml").writeText(
                desktopConfig(xmlEscape(defaultPathInSession)),
            )
            File(xfconfDir, "xsettings.xml").writeText(xsettingsConfig())
            File(xfconfDir, "xfwm4.xml").writeText(xfwm4Config())

            installServalDeskTheme(homeDir)
            installDarkXfwmTheme(homeDir)
            ServalDeskAppearance.install(homeDir)
            ensureHelpers(homeDir, binDir)

            val panelDir = File(homeDir, ".config/xfce4/panel")
            val localBin = File(homeDir, ".local/bin")
            val shell = resolveShell(binDir)
            fun helperExec(name: String): String =
                "\"$shell\" \"${File(localBin, name).absolutePath}\""

            writeLauncher(
                File(panelDir, "launcher-21/droiddesk-terminal.desktop"),
                name = "Terminal",
                comment = "Open the Linux terminal",
                exec = "xfce4-terminal",
                icon = "utilities-terminal",
            )
            writeLauncher(
                File(panelDir, "launcher-22/droiddesk-files.desktop"),
                name = "Files",
                comment = "Browse files",
                exec = "thunar %u",
                icon = "system-file-manager",
            )
            writeLauncher(
                File(panelDir, "launcher-23/droiddesk-browser.desktop"),
                name = "Web Browser",
                comment = "Browse the web",
                exec = "exo-open --launch WebBrowser %u",
                icon = "web-browser",
            )
            writeLauncher(
                File(panelDir, "launcher-26/droiddesk-vnc-share.desktop"),
                name = "Share VNC",
                comment = "Share this desktop to a Pi or laptop (port 5901)",
                exec = helperExec("droiddesk-vnc-share"),
                icon = "network-transmit-symbolic",
            )
            writeLauncher(
                File(panelDir, "launcher-26/droiddesk-vnc-connect.desktop"),
                name = "VNC Connect",
                comment = "Connect to another computer via VNC",
                exec = helperExec("droiddesk-vnc-connect"),
                icon = "network-receive-symbolic",
            )
            writeLauncher(
                File(panelDir, "launcher-26/droiddesk-vnc-stop.desktop"),
                name = "Stop VNC Share",
                comment = "Stop sharing this desktop over VNC",
                exec = helperExec("droiddesk-vnc-stop"),
                icon = "process-stop-symbolic",
            )
            writeLauncher(
                File(panelDir, "launcher-26/droiddesk-show-ip.desktop"),
                name = "Show IP",
                comment = "Show this phone's IP addresses for VNC / SSH",
                exec = helperExec("droiddesk-show-ip"),
                icon = "network-workgroup-symbolic",
            )
            // Drop older per-action dock slots that crowded the panel.
            File(panelDir, "launcher-28").deleteRecursively()
            File(panelDir, "launcher-29").deleteRecursively()
            cleanupAndroidAppsArtifacts(homeDir, binDir)

            homeDir.listFiles { file ->
                file.isFile &&
                    file.name.startsWith(".droiddesk-xfce-mobile-") &&
                    file.name != PROFILE_MARKER
            }?.forEach { it.delete() }
            marker.writeText("1\n")
            Log.i(TAG, "Installed XFCE mobile profile v27 (ServalDesk theme) in ${homeDir.absolutePath}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install XFCE mobile profile — leaving previous config intact", error)
            false
        }
    }

    private fun installWallpapers(context: Context, wallpaperDir: File): File {
        wallpaperDir.mkdirs()
        generateServalWallpaper(wallpaperDir)
        context.assets.open(WALLPAPER_ASSET).use { input ->
            File(wallpaperDir, "ubuntu-touch.jpg").outputStream().use(input::copyTo)
        }
        context.assets.list(WALLPAPER_DIR_ASSET)?.forEach { name ->
            if (!name.endsWith(".jpg", ignoreCase = true) &&
                !name.equals("CREDITS.txt", ignoreCase = true)
            ) {
                return@forEach
            }
            context.assets.open("$WALLPAPER_DIR_ASSET/$name").use { input ->
                File(wallpaperDir, name).outputStream().use(input::copyTo)
            }
        }
        // Prefer a real photo backdrop — flat gradients read as unfinished.
        return sequenceOf("city-night.jpg", "mountains.jpg", "ocean.jpg", SERVAL_WALLPAPER, "ubuntu-touch.jpg")
            .map { File(wallpaperDir, it) }
            .firstOrNull { it.exists() }
            ?: File(wallpaperDir, "ubuntu-touch.jpg")
    }

    /** Procedural midnight → violet → gold dusk wallpaper (ServalDesk brand). */
    private fun generateServalWallpaper(wallpaperDir: File): File {
        val out = File(wallpaperDir, SERVAL_WALLPAPER)
        val width = 1440
        val height = 2560
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(0xFF070B14.toInt(), 0xFF12182A.toInt(), 0xFF1A1430.toInt(), 0xFF0C101C.toInt()),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)

        val violetGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.78f, height * 0.22f, width * 0.55f,
                intArrayOf(0x668B5CF6, 0x228B5CF6, 0x00000000),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), violetGlow)

        val goldGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.2f, height * 0.72f, width * 0.5f,
                intArrayOf(0x55E8A23A, 0x18E8A23A, 0x00000000),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), goldGlow)

        // Soft horizon band
        val horizon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, height * 0.58f, 0f, height * 0.78f,
                intArrayOf(0x00000000, 0x33C084FC, 0x22E8A23A, 0x00000000),
                floatArrayOf(0f, 0.35f, 0.65f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), horizon)

        out.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        bitmap.recycle()
        return out
    }

    /**
     * Install/refresh helper scripts + Applications menu entries.
     * Safe to call on every session start (does not require a profile bump).
     *
     * Termux-style prefixes have no usable `/bin/sh`, so scripts must use the
     * real bash under [binDir] and .desktop files must Exec that interpreter.
     */
    fun ensureHelpers(homeDir: File, binDir: File? = null) {
        // Keep ServalDesk chrome current even when the profile marker exists.
        installServalDeskTheme(homeDir)
        installDarkXfwmTheme(homeDir)
        ServalDeskAppearance.install(homeDir)

        val localBin = File(homeDir, ".local/bin").apply { mkdirs() }
        val apps = File(homeDir, ".local/share/applications").apply { mkdirs() }
        val shell = resolveShell(binDir)

        fun installScript(name: String, body: String) {
            val file = File(localBin, name)
            val cleaned = body.lineSequence()
                .dropWhile { it.startsWith("#!") || it.isBlank() }
                .joinToString("\n")
                .trimStart()
            file.writeText("#!$shell\n$cleaned\n")
            file.setExecutable(true, false)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath)).waitFor()
            } catch (_: Exception) {
            }
            binDir?.let { dir ->
                dir.mkdirs()
                val copy = File(dir, name)
                copy.writeText(file.readText())
                copy.setExecutable(true, false)
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", copy.absolutePath)).waitFor()
                } catch (_: Exception) {
                }
            }
        }

        fun helperExec(name: String): String =
            "\"$shell\" \"${File(localBin, name).absolutePath}\""

        installScript("droiddesk-fit-windows", fitWindowsScript())
        installScript("droiddesk-apply-layout", applyLayoutScript("phone"))
        // Layout script body is mode-agnostic via LAYOUT arg — keep a thin wrapper pair.
        installScript(
            "droiddesk-layout-phone",
            applyLayoutScript("phone"),
        )
        installScript(
            "droiddesk-layout-docked",
            applyLayoutScript("docked"),
        )
        installScript("droiddesk-vnc-share", vncShareScript())
        installScript("droiddesk-vnc-stop", vncStopScript())
        installScript("droiddesk-vnc-connect", vncConnectScript())
        installScript("droiddesk-show-ip", showIpScript())
        installScript("droiddesk-theme-toggle", ServalDeskAppearance.switchScript())
        installScript("droiddesk-install-icons", ServalDeskAppearance.installIconsScript())
        // Kick off Papirus install once in the background (best-effort, needs network).
        try {
            val marker = File(homeDir, ".config/servaldesk/papirus-installed")
            if (!marker.exists()) {
                val installer = File(localBin, "droiddesk-install-icons")
                if (installer.isFile) {
                    Thread {
                        try {
                            ProcessBuilder(installer.absolutePath)
                                .redirectErrorStream(true)
                                .start()
                                .waitFor()
                        } catch (_: Exception) {
                        }
                    }.apply { isDaemon = true; name = "servaldesk-papirus" }.start()
                }
            }
        } catch (_: Exception) {
        }

        writeMenuDesktop(
            File(apps, "droiddesk-appearance.desktop"),
            name = "Toggle Light / Dark",
            comment = "Switch ServalDesk between light and dark skins",
            exec = helperExec("droiddesk-theme-toggle"),
            icon = "preferences-desktop-theme",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-install-icons.desktop"),
            name = "Install Modern Icons",
            comment = "Download Papirus icons (Xubuntu-like) if missing",
            exec = helperExec("droiddesk-install-icons"),
            icon = "preferences-desktop-icons",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-vnc-share.desktop"),
            name = "Share Desktop (VNC)",
            comment = "Share this Linux desktop on port 5901 for a Pi or laptop",
            exec = helperExec("droiddesk-vnc-share"),
            icon = "network-transmit-symbolic",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-vnc-stop.desktop"),
            name = "Stop VNC Share",
            comment = "Stop sharing this desktop over VNC",
            exec = helperExec("droiddesk-vnc-stop"),
            icon = "process-stop-symbolic",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-vnc-connect.desktop"),
            name = "Connect to VNC…",
            comment = "Open a VNC viewer to another computer",
            exec = helperExec("droiddesk-vnc-connect"),
            icon = "network-receive-symbolic",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-show-ip.desktop"),
            name = "Show Network / IP",
            comment = "Show IP addresses for VNC, SSH, or the Pi bridge",
            exec = helperExec("droiddesk-show-ip"),
            icon = "network-workgroup-symbolic",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-fit-windows.desktop"),
            name = "Fit Windows to Screen",
            comment = "Maximize open windows to the current display size",
            exec = helperExec("droiddesk-fit-windows"),
            icon = "zoom-fit-best-symbolic",
        )

        // Keep a single dock launcher with a VNC submenu (avoids icon overcrowding).
        val panelDir = File(homeDir, ".config/xfce4/panel")
        writeLauncher(
            File(panelDir, "launcher-26/droiddesk-vnc-share.desktop"),
            name = "Share VNC",
            comment = "Share this desktop to a Pi or laptop (port 5901)",
            exec = helperExec("droiddesk-vnc-share"),
            icon = "network-transmit-symbolic",
        )
        writeLauncher(
            File(panelDir, "launcher-26/droiddesk-vnc-connect.desktop"),
            name = "VNC Connect",
            comment = "Connect to another computer via VNC",
            exec = helperExec("droiddesk-vnc-connect"),
            icon = "network-receive-symbolic",
        )
        writeLauncher(
            File(panelDir, "launcher-26/droiddesk-vnc-stop.desktop"),
            name = "Stop VNC Share",
            comment = "Stop sharing this desktop over VNC",
            exec = helperExec("droiddesk-vnc-stop"),
            icon = "process-stop-symbolic",
        )
        writeLauncher(
            File(panelDir, "launcher-26/droiddesk-show-ip.desktop"),
            name = "Show IP",
            comment = "Show this phone's IP addresses for VNC / SSH",
            exec = helperExec("droiddesk-show-ip"),
            icon = "network-workgroup-symbolic",
        )
        writeLauncher(
            File(panelDir, "launcher-26/droiddesk-appearance.desktop"),
            name = "Light / Dark",
            comment = "Toggle ServalDesk light and dark skins",
            exec = helperExec("droiddesk-theme-toggle"),
            icon = "preferences-desktop-theme",
        )
        File(panelDir, "launcher-28").deleteRecursively()
        File(panelDir, "launcher-29").deleteRecursively()
    }

    private fun resolveShell(binDir: File?): String {
        if (binDir != null) {
            listOf("bash", "sh").forEach { name ->
                val candidate = File(binDir, name)
                if (candidate.exists()) return candidate.absolutePath
            }
        }
        listOf("/bin/bash", "/usr/bin/bash", "/bin/sh").forEach { path ->
            if (File(path).exists()) return path
        }
        return "bash"
    }

    /** @deprecated Use [ensureHelpers]. Kept for call-site compatibility. */
    fun ensureFitWindowsHelper(homeDir: File, binDir: File? = null) =
        ensureHelpers(homeDir, binDir)

    /**
     * Fit app windows to the live X root (and optionally wait until the root
     * matches EXPECTED_W×EXPECTED_H from Android after rotate / mode switch).
     */
    fun fitWindowsScript(): String = """
        #!/bin/sh
        # Fit visible XFCE/app windows to the current X screen / workarea.
        # Optional: EXPECTED_W / EXPECTED_H — wait until xdpyinfo matches (rotate fix).
        export DISPLAY="${'$'}{DISPLAY:-:0}"
        expected_w="${'$'}{EXPECTED_W:-}"
        expected_h="${'$'}{EXPECTED_H:-}"

        read_dims() {
          xdpyinfo 2>/dev/null | awk '/dimensions/{print ${'$'}2; exit}'
        }

        dim=""
        i=0
        # Longer poll when we know the target size (orientation / VNC settle).
        max=12
        if [ -n "${'$'}expected_w" ] && [ -n "${'$'}expected_h" ]; then
          max=40
        fi
        while [ "${'$'}i" -lt "${'$'}max" ]; do
          dim=${'$'}(read_dims)
          if [ -n "${'$'}dim" ]; then
            cw=${'$'}{dim%x*}
            ch=${'$'}{dim#*x}
            if [ -z "${'$'}expected_w" ] || [ -z "${'$'}expected_h" ]; then
              break
            fi
            if [ "${'$'}cw" = "${'$'}expected_w" ] && [ "${'$'}ch" = "${'$'}expected_h" ]; then
              break
            fi
            # Also accept swapped axes if Android handed us portrait/landscape mid-flip.
            if [ "${'$'}cw" = "${'$'}expected_h" ] && [ "${'$'}ch" = "${'$'}expected_w" ]; then
              break
            fi
          fi
          i=${'$'}((i + 1))
          sleep 0.12
        done
        [ -n "${'$'}dim" ] || exit 0
        w=${'$'}{dim%x*}
        h=${'$'}{dim#*x}
        case "${'$'}w" in (*[!0-9]*|"") exit 0 ;; esac
        case "${'$'}h" in (*[!0-9]*|"") exit 0 ;; esac
        [ "${'$'}w" -gt 0 ] && [ "${'$'}h" -gt 0 ] || exit 0

        # Prefer EWMH workarea so maximized windows clear panels.
        wa=${'$'}(xprop -root _NET_WORKAREA 2>/dev/null | awk -F'[=,]' '{gsub(/ /,"",${'$'}0); print ${'$'}3,${'$'}4,${'$'}5,${'$'}6; exit}')
        wx=0; wy=0; ww="${'$'}w"; wh="${'$'}h"
        if [ -n "${'$'}wa" ]; then
          set -- ${'$'}wa
          case "${'$'}1" in (*[!0-9]*) ;; *)
            case "${'$'}2" in (*[!0-9]*) ;; *)
              case "${'$'}3" in (*[!0-9]*) ;; *)
                case "${'$'}4" in (*[!0-9]*) ;; *)
                  if [ "${'$'}3" -gt 32 ] && [ "${'$'}4" -gt 32 ]; then
                    wx="${'$'}1"; wy="${'$'}2"; ww="${'$'}3"; wh="${'$'}4"
                  fi
                ;; esac
              ;; esac
            ;; esac
          ;; esac
        fi

        fit_one() {
          id="${'$'}1"
          [ -n "${'$'}id" ] || return 0
          wmctrl -i -r "${'$'}id" -b remove,fullscreen,maximized_vert,maximized_horz >/dev/null 2>&1 || true
          wmctrl -i -r "${'$'}id" -e "0,${'$'}wx,${'$'}wy,${'$'}ww,${'$'}wh" >/dev/null 2>&1 || true
          wmctrl -i -r "${'$'}id" -b add,maximized_vert,maximized_horz >/dev/null 2>&1 || true
        }

        if command -v wmctrl >/dev/null 2>&1; then
          wmctrl -lx 2>/dev/null | while read -r id _ class _; do
            [ -n "${'$'}id" ] || continue
            case "${'$'}class" in
              *xfce4-panel*|*Xfce4-panel*|*xfdesktop*|*Xfdesktop*|*wrapper-2.0*|*Wrapper*|*polybar*|*plank*)
                continue
                ;;
            esac
            fit_one "${'$'}id"
          done
          # Second pass: clamp any still-offscreen geometry after root resize.
          sleep 0.15
          wmctrl -lG 2>/dev/null | while read -r id _ gx gy gw gh _; do
            [ -n "${'$'}id" ] || continue
            case "${'$'}gx" in (*[!0-9-]*|"") continue ;; esac
            case "${'$'}gy" in (*[!0-9-]*|"") continue ;; esac
            case "${'$'}gw" in (*[!0-9]*|"") continue ;; esac
            case "${'$'}gh" in (*[!0-9]*|"") continue ;; esac
            oob=0
            [ "${'$'}gx" -lt -16 ] && oob=1
            [ "${'$'}gy" -lt -16 ] && oob=1
            [ "${'$'}((gx + gw))" -gt "${'$'}((w + 16))" ] && oob=1
            [ "${'$'}((gy + gh))" -gt "${'$'}((h + 16))" ] && oob=1
            [ "${'$'}gw" -gt "${'$'}((w + 32))" ] && oob=1
            [ "${'$'}gh" -gt "${'$'}((h + 32))" ] && oob=1
            [ "${'$'}oob" -eq 1 ] || continue
            cls=${'$'}(wmctrl -lx 2>/dev/null | awk -v i="${'$'}id" '${'$'}1==i {print ${'$'}3; exit}')
            case "${'$'}cls" in
              *xfce4-panel*|*Xfce4-panel*|*xfdesktop*|*Xfdesktop*|*wrapper-2.0*|*Wrapper*)
                continue
                ;;
            esac
            fit_one "${'$'}id"
          done
          exit 0
        fi

        if command -v xdotool >/dev/null 2>&1; then
          xdotool search --onlyvisible --name '' 2>/dev/null | while read -r id; do
            [ -n "${'$'}id" ] || continue
            xdotool windowmove "${'$'}id" "${'$'}wx" "${'$'}wy" >/dev/null 2>&1 || true
            xdotool windowsize "${'$'}id" "${'$'}ww" "${'$'}wh" >/dev/null 2>&1 || true
          done
        fi
    """.trimIndent() + "\n"

    /** Live xfconf tweaks: phone (touch dock) vs docked/VNC (desktop-like). */
    fun applyLayoutScript(mode: String): String {
        val layout = if (mode == "docked" || mode == "vnc") "docked" else "phone"
        return """
            #!/bin/sh
            export DISPLAY="${'$'}{DISPLAY:-:0}"
            export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
            if ! command -v xfconf-query >/dev/null 2>&1; then
              exit 0
            fi
            LAYOUT="$layout"
            # Appearance-aware GTK + modern Papirus icons (Xubuntu-like).
            APPEAR=dark
            [ -f "${'$'}HOME/.config/servaldesk/appearance" ] && \
              APPEAR=${'$'}(tr -d '[:space:]' < "${'$'}HOME/.config/servaldesk/appearance")
            if [ "${'$'}APPEAR" = "light" ]; then
              GTK_THEME=Adwaita
              ICON_PREF="Papirus Papirus-Dark"
              WM_THEME=Default
            else
              GTK_THEME=Adwaita-dark
              ICON_PREF="Papirus-Dark Papirus"
              WM_THEME=ServalDesk-wm
            fi
            xfconf-query -c xsettings -p /Net/ThemeName -n -t string -s "${'$'}GTK_THEME" 2>/dev/null || \
              xfconf-query -c xsettings -p /Net/ThemeName -s "${'$'}GTK_THEME" 2>/dev/null || true
            xfconf-query -c xfwm4 -p /general/theme -n -t string -s "${'$'}WM_THEME" 2>/dev/null || \
              xfconf-query -c xfwm4 -p /general/theme -s "${'$'}WM_THEME" 2>/dev/null || true
            xfconf-query -c xsettings -p /Gtk/FontName -n -t string -s "DejaVu Sans 11" 2>/dev/null || \
              xfconf-query -c xsettings -p /Gtk/FontName -s "DejaVu Sans 11" 2>/dev/null || true
            ICON=""
            for t in ${'$'}ICON_PREF elementary-xfce-darker elementary-xfce Tela-dark Tela Adwaita; do
              if [ -d "${'$'}HOME/.icons/${'$'}t" ] || [ -d "${'$'}HOME/.local/share/icons/${'$'}t" ] || \
                 [ -d "${'$'}PREFIX/share/icons/${'$'}t" ] || [ -d "/usr/share/icons/${'$'}t" ]; then
                ICON="${'$'}t"; break
              fi
            done
            [ -n "${'$'}ICON" ] && {
              xfconf-query -c xsettings -p /Net/IconThemeName -n -t string -s "${'$'}ICON" 2>/dev/null || \
                xfconf-query -c xsettings -p /Net/IconThemeName -s "${'$'}ICON" 2>/dev/null || true
            }
            # Keep maximized windows clear of panels (struts are flaky on X11-on-Android).
            if [ "${'$'}LAYOUT" = "docked" ]; then
              # Desktop density for external / VNC 1080p.
              xfconf-query -c xsettings -p /Xft/DPI -n -t int -s 96 2>/dev/null || \
                xfconf-query -c xsettings -p /Xft/DPI -s 96 2>/dev/null || true
              xfconf-query -c xsettings -p /Gtk/CursorThemeSize -n -t int -s 24 2>/dev/null || \
                xfconf-query -c xsettings -p /Gtk/CursorThemeSize -s 24 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-1/size -s 30 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-1/icon-size -s 22 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-1/length -s 100 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-2/size -s 42 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-2/icon-size -s 32 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-2/length -s 100 2>/dev/null || true
              xfconf-query -c xfwm4 -p /general/title_font -s "DejaVu Sans Bold 10" 2>/dev/null || true
              xfconf-query -c xfwm4 -p /general/use_compositing -s true 2>/dev/null || true
              xfconf-query -c xfwm4 -p /general/box_move -s false 2>/dev/null || true
              xfconf-query -c xfwm4 -p /general/box_resize -s false 2>/dev/null || true
              xfconf-query -c xfwm4 -p /general/margin_top -s 34 2>/dev/null || true
              xfconf-query -c xfwm4 -p /general/margin_bottom -s 50 2>/dev/null || true
            else
              # Phone: larger touch targets + higher DPI.
              xfconf-query -c xsettings -p /Xft/DPI -n -t int -s 120 2>/dev/null || \
                xfconf-query -c xsettings -p /Xft/DPI -s 120 2>/dev/null || true
              xfconf-query -c xsettings -p /Gtk/CursorThemeSize -n -t int -s 32 2>/dev/null || \
                xfconf-query -c xsettings -p /Gtk/CursorThemeSize -s 32 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-1/size -s 36 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-1/icon-size -s 24 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-1/length -s 100 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-2/size -s 56 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-2/icon-size -s 40 2>/dev/null || true
              xfconf-query -c xfce4-panel -p /panels/panel-2/length -s 100 2>/dev/null || true
              xfconf-query -c xfwm4 -p /general/title_font -s "DejaVu Sans Bold 11" 2>/dev/null || true
              xfconf-query -c xfwm4 -p /general/margin_top -s 40 2>/dev/null || true
              xfconf-query -c xfwm4 -p /general/margin_bottom -s 64 2>/dev/null || true
            fi
            # Panel colors from appearance
            if [ "${'$'}APPEAR" = "light" ]; then
              for p in 1 2; do
                xfconf-query -c xfce4-panel -p /panels/panel-${'$'}p/background-style -s 1 2>/dev/null || true
                xfconf-query -c xfce4-panel -p /panels/panel-${'$'}p/background-rgba \
                  -t double -t double -t double -t double \
                  -s 0.965 -s 0.945 -s 0.910 -s 0.960 2>/dev/null || true
              done
            else
              for p in 1 2; do
                xfconf-query -c xfce4-panel -p /panels/panel-${'$'}p/background-style -s 1 2>/dev/null || true
                xfconf-query -c xfce4-panel -p /panels/panel-${'$'}p/background-rgba \
                  -t double -t double -t double -t double \
                  -s 0.055 -s 0.070 -s 0.120 -s 0.920 2>/dev/null || true
              done
            fi
        """.trimIndent() + "\n"
    }

    private fun shellNotifyHelpers(): String = """
        _dd_notify() {
          title="${'$'}1"; body="${'$'}2"
          if command -v notify-send >/dev/null 2>&1; then
            notify-send -a ServalDesk "${'$'}title" "${'$'}body" 2>/dev/null || true
          fi
          if command -v zenity >/dev/null 2>&1; then
            zenity --info --title="${'$'}title" --text="${'$'}body" --width=380 2>/dev/null &
          fi
        }
        _dd_error() {
          title="${'$'}1"; body="${'$'}2"
          if command -v notify-send >/dev/null 2>&1; then
            notify-send -u critical -a ServalDesk "${'$'}title" "${'$'}body" 2>/dev/null || true
          fi
          if command -v zenity >/dev/null 2>&1; then
            zenity --error --title="${'$'}title" --text="${'$'}body" --width=380 2>/dev/null || true
          else
            xfce4-terminal -T "${'$'}title" -e "bash -c 'echo \"${'$'}body\"; echo; read -n1 -p \"Press any key…\"'" 2>/dev/null || true
          fi
        }
        _dd_primary_ip() {
          ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(${'$'}i=="src"){print ${'$'}(i+1); exit}}'
        }
        _dd_all_ips() {
          ip -4 -o addr show scope global 2>/dev/null | awk '{gsub(/\/.*/,"",${'$'}4); print ${'$'}2": "${'$'}4}'
          # USB tether / Wi‑Fi fallbacks on Android
          for iface in wlan0 rndis0 usb0 eth0; do
            addr=${'$'}(ip -4 -o addr show dev "${'$'}iface" 2>/dev/null | awk '{gsub(/\/.*/,"",${'$'}4); print ${'$'}4}')
            [ -n "${'$'}addr" ] && echo "${'$'}iface: ${'$'}addr"
          done
        }
    """.trimIndent()

    private fun vncShareScript(): String = """
        # Share the live ServalDesk X session over VNC at a laptop-sized resolution.
        export DISPLAY="${'$'}{DISPLAY:-:0}"
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        ${shellNotifyHelpers()}

        if ! command -v x11vnc >/dev/null 2>&1; then
          _dd_error "VNC Share" "x11vnc is not installed.

Install once in Terminal:
  pkg install x11vnc

Then tap Share VNC again."
          exit 1
        fi

        mkdir -p "${'$'}HOME/.cache"
        # Ask Android to switch X to 1920x1080 before advertising VNC.
        echo vnc > "${'$'}HOME/.cache/droiddesk-display-mode"
        _dd_notify "VNC Share" "Switching desktop to 1920×1080 for Mac/laptop…
Then starting VNC on port 5901."
        # Give DesktopActivity time to apply exact resolution + remaximize windows.
        sleep 2.5

        pkill -x x11vnc >/dev/null 2>&1 || true
        sleep 0.3
        LOG="${'$'}HOME/.cache/droiddesk-x11vnc.log"
        # LAN-tuned: threads + light defer, client pixmap cache, low poll wait.
        # Phone CPU still caps smoothness at 1080p over Wi‑Fi.
        x11vnc \
          -display :0 \
          -forever -shared \
          -rfbport 5901 -nopw \
          -threads \
          -speeds lan \
          -defer 10 -wait 10 \
          -ncache 10 -ncache_cr \
          -pointer_mode 1 \
          -bg -o "${'$'}LOG" >/dev/null 2>&1
        sleep 0.5
        if ! pgrep -x x11vnc >/dev/null 2>&1; then
          echo phone > "${'$'}HOME/.cache/droiddesk-display-mode"
          _dd_error "VNC Share" "Failed to start x11vnc.
See ${'$'}LOG"
          exit 1
        fi
        IP=${'$'}(_dd_primary_ip)
        [ -z "${'$'}IP" ] && IP="(enable Wi‑Fi or USB tethering)"
        _dd_notify "VNC sharing (1920×1080)" "Connect from Mac / laptop:

  ${'$'}IP:5901

Tip: USB tethering is snappier than Wi‑Fi.
In TigerVNC: F8 → Full screen (optional).
Stop VNC Share when done (restores phone layout)."
    """.trimIndent() + "\n"

    private fun vncStopScript(): String = """
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        ${shellNotifyHelpers()}
        mkdir -p "${'$'}HOME/.cache"
        if pgrep -x x11vnc >/dev/null 2>&1; then
          pkill -x x11vnc >/dev/null 2>&1 || true
        fi
        echo phone > "${'$'}HOME/.cache/droiddesk-display-mode"
        _dd_notify "VNC Share" "Stopped. Restoring phone display layout…"
    """.trimIndent() + "\n"

    private fun vncConnectScript(): String = """
        #!/bin/sh
        export DISPLAY="${'$'}{DISPLAY:-:0}"
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        ${shellNotifyHelpers()}

        VIEWER=""
        for c in vncviewer xtigervncviewer tigervnc; do
          if command -v "${'$'}c" >/dev/null 2>&1; then VIEWER="${'$'}c"; break; fi
        done
        if [ -z "${'$'}VIEWER" ]; then
          _dd_error "VNC Connect" "No VNC viewer found.

Install once in Terminal:
  pkg install tigervnc-viewer"
          exit 1
        fi

        HOST=""
        if command -v zenity >/dev/null 2>&1; then
          HOST=${'$'}(zenity --entry --title="VNC Connect" --text="Host IP or hostname (optional :port):" --entry-text="192.168." 2>/dev/null) || exit 0
        else
          HOST=${'$'}(xfce4-terminal --disable-server -T "VNC Connect" -e "bash -c 'read -rp \"Host IP (optional :port): \" h; echo \"${'$'}h\" > /tmp/droiddesk-vnc-host'" 2>/dev/null; cat /tmp/droiddesk-vnc-host 2>/dev/null)
        fi
        [ -n "${'$'}HOST" ] || exit 0
        case "${'$'}HOST" in
          *:*) TARGET="${'$'}HOST" ;;
          *)   TARGET="${'$'}HOST:5901" ;;
        esac
        exec "${'$'}VIEWER" "${'$'}TARGET"
    """.trimIndent() + "\n"

    private fun showIpScript(): String = """
        #!/bin/sh
        export DISPLAY="${'$'}{DISPLAY:-:0}"
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        ${shellNotifyHelpers()}
        IPS=${'$'}(_dd_all_ips | awk '!seen[${'$'}0]++')
        PRIMARY=${'$'}(_dd_primary_ip)
        if [ -z "${'$'}IPS" ] && [ -z "${'$'}PRIMARY" ]; then
          _dd_error "Network / IP" "No IPv4 address found.
Enable Wi‑Fi or USB tethering, then try again."
          exit 1
        fi
        MSG="Primary: ${'$'}{PRIMARY:-(none)}

${'$'}IPS

VNC share uses port 5901 (Share VNC on the dock).
Pi bridge: run pi-launch_phone.sh on the Pi after USB tethering."
        _dd_notify "Network / IP" "${'$'}MSG"
    """.trimIndent() + "\n"

    private fun cleanupAndroidAppsArtifacts(homeDir: File, binDir: File?) {
        File(homeDir, ".local/share/applications/droiddesk-android-apps.desktop").delete()
        File(homeDir, ".local/bin/droiddesk-android-apps").delete()
        File(homeDir, ".config/xfce4/panel/launcher-27").deleteRecursively()
        binDir?.let { File(it, "droiddesk-android-apps").delete() }
    }

    private fun writeMenuDesktop(
        file: File,
        name: String,
        comment: String,
        exec: String,
        icon: String,
    ) {
        writeLauncher(file, name, comment, exec, icon, categories = "Network;System;Utility;")
    }

    private fun writeLauncher(
        file: File,
        name: String,
        comment: String,
        exec: String,
        icon: String,
        categories: String = "Utility;System;",
    ) {
        file.parentFile?.mkdirs()
        file.writeText(
            """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=$name
            Comment=$comment
            Exec=$exec
            Icon=$icon
            Categories=$categories
            StartupNotify=true
            Terminal=false
            """.trimIndent() + "\n",
        )
    }

    private fun installGtkSettings(homeDir: File) {
        val settings = File(homeDir, ".config/gtk-3.0/settings.ini")
        settings.parentFile?.mkdirs()
        settings.writeText(
            """
            [Settings]
            gtk-theme-name=Adwaita-dark
            gtk-icon-theme-name=Adwaita
            gtk-font-name=DejaVu Sans 11
            gtk-cursor-theme-size=32
            gtk-xft-dpi=120000
            gtk-enable-animations=true
            gtk-application-prefer-dark-theme=true
            """.trimIndent() + "\n",
        )
    }

    /**
     * Lightweight theme package under ~/.themes/ServalDesk (index only).
     * Visual chrome is applied via [installServalChrome] on top of Adwaita-dark
     * so we never ship a broken half-theme.
     */
    private fun installServalDeskTheme(homeDir: File) {
        val themeDir = File(homeDir, ".themes/ServalDesk").apply { mkdirs() }
        File(themeDir, "index.theme").writeText(
            """
            [Desktop Entry]
            Type=X-GNOME-Metatheme
            Name=ServalDesk
            Comment=ServalDesk midnight theme — gold & violet accents
            Encoding=UTF-8

            [X-GNOME-Metatheme]
            GtkTheme=Adwaita-dark
            MetacityTheme=Default
            IconTheme=Adwaita
            CursorTheme=Adwaita
            ButtonLayout=menu:minimize,maximize,close
            """.trimIndent() + "\n",
        )
        File(themeDir, "gtk-3.0").mkdirs()
        File(themeDir, "gtk-3.0/gtk.css").writeText(
            "/* ServalDesk metatheme — chrome lives in ~/.config/gtk-3.0/gtk.css */\n",
        )
    }

    private fun installServalChrome(homeDir: File) {
        val cssFile = File(homeDir, ".config/gtk-3.0/gtk.css")
        cssFile.parentFile?.mkdirs()
        val startMarker = "/* ServalDesk chrome start */"
        val endMarker = "/* ServalDesk chrome end */"
        val legacyStart = "/* DroidDesk mobile panel start */"
        val legacyEnd = "/* DroidDesk mobile panel end */"
        val managedBlock = """
            $startMarker
            /* ServalDesk — midnight / cream / soft violet */
            @define-color theme_bg_color #161B27;
            @define-color theme_fg_color #ECE8E1;
            @define-color theme_base_color #0C1018;
            @define-color theme_text_color #ECE8E1;
            @define-color theme_selected_bg_color #6E56CF;
            @define-color theme_selected_fg_color #FFFFFF;
            @define-color theme_button_bg_color #1E2536;
            @define-color theme_button_fg_color #ECE8E1;
            @define-color insensitive_bg_color #161B27;
            @define-color insensitive_fg_color #6B7388;
            @define-color insensitive_base_color #121722;
            @define-color borders #2C3448;
            @define-color content_view_bg #0C1018;
            @define-color sidebar_bg #121826;
            @define-color serval_void #0C1018;
            @define-color serval_surface #161B27;
            @define-color serval_raised #1E2536;
            @define-color serval_border #2C3448;
            @define-color serval_gold #D4A574;
            @define-color serval_violet #6E56CF;
            @define-color serval_text #ECE8E1;
            @define-color serval_muted #9AA3B5;

            window,
            .background,
            dialog,
            messagedialog {
              background-color: @serval_surface;
              color: @serval_text;
              background-image: none;
            }

            .view,
            .content-view,
            iconview,
            treeview,
            list,
            listview,
            textview,
            textview text {
              background-color: @content_view_bg;
              color: @serval_text;
              background-image: none;
            }
            .view:selected,
            iconview:selected,
            treeview:selected,
            list row:selected,
            .view:selected:focus {
              background-color: @theme_selected_bg_color;
              color: @theme_selected_fg_color;
            }

            toolbar,
            .toolbar,
            .primary-toolbar,
            paned > separator {
              background-image: none;
              background-color: @serval_surface;
              color: @serval_text;
              border-color: @serval_border;
            }

            .sidebar,
            placessidebar,
            .thunar .sidebar,
            stacksidebar {
              background-color: @sidebar_bg;
              color: @serval_text;
              background-image: none;
              border-color: @serval_border;
            }
            .sidebar row:selected,
            placessidebar row:selected,
            .thunar .sidebar row:selected {
              background-color: alpha(@serval_violet, 0.45);
              color: #ffffff;
              border-radius: 8px;
            }

            entry, .entry, spinbutton {
              min-height: 32px;
              border-radius: 8px;
              background-image: none;
              background-color: @serval_raised;
              color: @serval_text;
              border: 1px solid @serval_border;
              padding: 4px 10px;
              box-shadow: none;
            }
            entry:focus {
              border-color: @serval_violet;
            }

            button {
              border-radius: 8px;
              min-height: 32px;
              padding: 5px 12px;
              background-image: none;
              background-color: @serval_raised;
              color: @serval_text;
              border: 1px solid @serval_border;
              box-shadow: none;
              text-shadow: none;
            }
            button:hover {
              background-color: shade(@serval_raised, 1.15);
              border-color: alpha(@serval_violet, 0.5);
            }
            button:checked,
            button:active,
            button.suggested-action {
              background-color: @serval_violet;
              color: #ffffff;
              border-color: shade(@serval_violet, 0.9);
            }

            headerbar, .titlebar {
              background-image: none;
              background-color: @serval_surface;
              color: @serval_text;
              border-bottom: 1px solid @serval_border;
              box-shadow: none;
              text-shadow: none;
            }

            notebook > header {
              background-color: @serval_surface;
              border-color: @serval_border;
            }
            notebook > header tab {
              border-radius: 8px 8px 0 0;
              padding: 7px 12px;
              color: @serval_muted;
              background-image: none;
            }
            notebook > header tab:checked {
              color: @serval_text;
              background-color: @serval_raised;
              box-shadow: inset 0 -2px 0 @serval_gold;
            }

            scrollbar slider {
              min-width: 10px;
              min-height: 10px;
              border-radius: 8px;
              background-color: alpha(@serval_violet, 0.4);
              border: none;
            }

            .thunar,
            .thunar window,
            .thunar .background {
              background-color: @serval_surface;
              color: @serval_text;
            }
            .thunar toolbar,
            .thunar .location-toolbar,
            .thunar pathbar,
            .thunar .path-bar {
              background-color: @serval_raised;
              color: @serval_text;
              background-image: none;
              border-color: @serval_border;
            }
            .thunar .standard-view,
            .thunar .view,
            .thunar iconview {
              background-color: @serval_void;
              color: @serval_text;
            }
            .thunar .sidebar treeview,
            .thunar .sidebar .view {
              background-color: @sidebar_bg;
              color: @serval_text;
            }

            .xfce4-panel {
              color: @serval_text;
              font-weight: 500;
            }
            .xfce4-panel button {
              min-height: 0;
              min-width: 0;
              padding: 4px;
              margin: 0;
              border-radius: 10px;
              border: none;
              background-image: none;
              background-color: transparent;
              color: @serval_text;
              box-shadow: none;
              text-shadow: none;
            }
            .xfce4-panel button:hover {
              background-color: alpha(@serval_violet, 0.25);
            }
            .xfce4-panel button:checked,
            .xfce4-panel button:active {
              background-color: alpha(@serval_violet, 0.4);
              color: @serval_text;
            }
            .xfce4-panel .tasklist button,
            .xfce4-panel .tasklist .button {
              padding: 4px;
              margin: 0 2px;
              min-width: 32px;
              min-height: 32px;
              border-radius: 10px;
            }
            .xfce4-panel .tasklist button image,
            .xfce4-panel .tasklist .button image {
              margin: 0;
              padding: 0;
            }
            .xfce4-panel > menubar,
            .xfce4-panel > widget > box,
            .xfce4-panel button label,
            .xfce4-panel .clock label,
            .xfce4-panel .digital-clock label,
            #clock-button label {
              color: @serval_text;
              opacity: 1;
              text-shadow: none;
            }
            .xfce4-panel .clock label,
            .xfce4-panel .digital-clock label,
            #clock-button label {
              color: @serval_text;
              font-weight: 600;
              letter-spacing: 0.04em;
            }
            .xfce4-panel image,
            .xfce4-panel button image {
              color: @serval_text;
              -gtk-icon-recoloring: true;
              opacity: 1;
            }

            menu,
            .menu,
            .xfce4-panel menu,
            window.popup menu {
              background-color: @serval_surface;
              color: @serval_text;
              border: 1px solid @serval_border;
              border-radius: 12px;
              padding: 6px;
            }
            menu menuitem,
            .menu menuitem,
            .xfce4-panel menu menuitem {
              color: @serval_text;
              padding: 9px 14px;
              border-radius: 8px;
            }
            menu menuitem label,
            .xfce4-panel menu menuitem label,
            menu menuitem image,
            .xfce4-panel menu menuitem image {
              color: @serval_text !important;
            }
            menu menuitem:hover,
            .xfce4-panel menu menuitem:hover {
              background-color: alpha(@serval_violet, 0.32);
              color: #ffffff !important;
            }
            menu menuitem:hover label {
              color: #ffffff !important;
            }

            tooltip {
              background-color: @serval_raised;
              color: @serval_text;
              border: 1px solid @serval_border;
              border-radius: 8px;
            }
            $endMarker
        """.trimIndent()
        var existing = if (cssFile.exists()) cssFile.readText() else ""
        listOf(startMarker to endMarker, legacyStart to legacyEnd).forEach { (s, e) ->
            existing = existing.replace(
                Regex(
                    Regex.escape(s) + ".*?" + Regex.escape(e),
                    setOf(RegexOption.DOT_MATCHES_ALL),
                ),
                "",
            ).trimEnd()
        }
        cssFile.writeText(
            if (existing.isEmpty()) "$managedBlock\n"
            else "$existing\n\n$managedBlock\n",
        )
    }

    /**
     * Build a dark xfwm4 theme from stock Default assets so titlebars
     * match ServalDesk instead of light grey chrome.
     */
    private fun installDarkXfwmTheme(homeDir: File) {
        val dest = File(homeDir, ".themes/ServalDesk-wm/xfwm4").apply { mkdirs() }
        val prefixGuess = homeDir.parentFile?.let { File(it, "usr/share/themes/Default/xfwm4") }
        val sources = listOfNotNull(
            prefixGuess,
            File(homeDir, "../usr/share/themes/Default/xfwm4"),
            File("/data/data/com.servaldesk.app/files/usr/share/themes/Default/xfwm4"),
        ).filter { it.isDirectory }
        val src = sources.firstOrNull()
        if (src != null) {
            src.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val target = File(dest, file.name)
                var data = file.readText(Charsets.ISO_8859_1)
                if (file.name.endsWith(".xpm", ignoreCase = true) ||
                    file.name.equals("themerc", ignoreCase = true)
                ) {
                    data = darkenXfwmColors(data)
                }
                target.writeText(data, Charsets.ISO_8859_1)
            }
        } else {
            writeMinimalXfwmPixmaps(dest)
        }
        File(dest, "themerc").writeText(
            """
            active_text_color=#ECE8E1
            inactive_text_color=#8B93A7
            active_text_shadow_color=#00000000
            inactive_text_shadow_color=#00000000
            title_shadow_active=false
            title_shadow_inactive=false
            full_width_title=true
            button_offset=2
            button_spacing=3
            title_vertical_offset_active=1
            title_vertical_offset_inactive=1
            title_horizontal_offset=1
            """.trimIndent() + "\n",
        )
    }

    private fun darkenXfwmColors(data: String): String {
        var out = data
        val replacements = listOf(
            "#ffffff" to "#1E2536",
            "#fefefe" to "#1E2536",
            "#f6f5f4" to "#1A2030",
            "#f5f5f5" to "#1A2030",
            "#eeeeee" to "#181E2C",
            "#e8e8e8" to "#181E2C",
            "#e0e0e0" to "#161B27",
            "#dcdcdc" to "#161B27",
            "#d3d3d3" to "#141926",
            "#c0c0c0" to "#2C3448",
            "#b8b8b8" to "#2C3448",
            "#a0a0a0" to "#3A4358",
            "#808080" to "#4A5268",
            "#777777" to "#4A5268",
            "#666666" to "#6B7388",
            "#4d4d4d" to "#8B93A7",
            "#333333" to "#ECE8E1",
            "#2e3436" to "#161B27",
            "#dededa" to "#1E2536",
            "#c8c8c4" to "#2C3448",
            "#398ee7" to "#6E56CF",
            "#3584e4" to "#6E56CF",
            "#1a73e8" to "#6E56CF",
        )
        for ((light, dark) in replacements) {
            out = out.replace(light, dark, ignoreCase = true)
        }
        return out
    }

    private fun writeMinimalXfwmPixmaps(dest: File) {
        fun solidXpm(fileName: String, color: String, w: Int = 8, h: Int = 20) {
            val symbol = fileName.substringBefore(".")
            val rows = List(h) { "c".repeat(w) }.joinToString(",\n") { "\"$it\"" }
            File(dest, fileName).writeText(
                """
                /* XPM */
                static char * ${symbol}[] = {
                "$w $h 1 1",
                "c c $color",
                $rows
                };
                """.trimIndent() + "\n",
            )
        }
        val parts = listOf(
            "top-left-active", "top-left-inactive",
            "top-right-active", "top-right-inactive",
            "title-1-active", "title-1-inactive",
            "title-2-active", "title-2-inactive",
            "title-3-active", "title-3-inactive",
            "title-4-active", "title-4-inactive",
            "title-5-active", "title-5-inactive",
            "left-active", "left-inactive",
            "right-active", "right-inactive",
            "bottom-active", "bottom-inactive",
            "bottom-left-active", "bottom-left-inactive",
            "bottom-right-active", "bottom-right-inactive",
            "close-active", "close-inactive", "close-prelight", "close-pressed",
            "hide-active", "hide-inactive", "hide-prelight", "hide-pressed",
            "maximize-active", "maximize-inactive", "maximize-prelight", "maximize-pressed",
            "maximize-toggled-active", "maximize-toggled-inactive",
            "maximize-toggled-prelight", "maximize-toggled-pressed",
            "menu-active", "menu-inactive", "menu-prelight", "menu-pressed",
            "shade-active", "shade-inactive", "shade-prelight", "shade-pressed",
            "stick-active", "stick-inactive", "stick-prelight", "stick-pressed",
            "stick-toggled-active", "stick-toggled-inactive",
            "stick-toggled-prelight", "stick-toggled-pressed",
        )
        for (p in parts) {
            val color = when {
                p.startsWith("close") -> "#C45C5C"
                p.contains("active") -> "#1E2536"
                else -> "#161B27"
            }
            solidXpm("$p.xpm", color)
        }
    }

    private fun panelConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-panel" version="1.0">
          <property name="configver" type="int" value="2"/>
          <property name="panels" type="array">
            <value type="int" value="1"/>
            <value type="int" value="2"/>
            <property name="dark-mode" type="bool" value="true"/>
            <property name="panel-1" type="empty">
              <property name="position" type="string" value="p=9;x=0;y=0"/>
              <property name="length" type="uint" value="100"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="0"/>
              <property name="size" type="uint" value="36"/>
              <property name="icon-size" type="uint" value="24"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.055000"/>
                <value type="double" value="0.070000"/>
                <value type="double" value="0.120000"/>
                <value type="double" value="0.920000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="2"/>
                <value type="int" value="1"/>
                <value type="int" value="3"/>
              </property>
            </property>
            <property name="panel-2" type="empty">
              <property name="position" type="string" value="p=8;x=0;y=0"/>
              <property name="mode" type="uint" value="0"/>
              <property name="length" type="uint" value="100"/>
              <property name="length-adjust" type="bool" value="false"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="0"/>
              <property name="size" type="uint" value="56"/>
              <property name="icon-size" type="uint" value="40"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.055000"/>
                <value type="double" value="0.070000"/>
                <value type="double" value="0.120000"/>
                <value type="double" value="0.920000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="20"/>
                <value type="int" value="21"/>
                <value type="int" value="22"/>
                <value type="int" value="23"/>
                <value type="int" value="26"/>
                <value type="int" value="24"/>
                <value type="int" value="25"/>
              </property>
            </property>
          </property>
          <property name="plugins" type="empty">
            <property name="plugin-1" type="string" value="separator">
              <property name="expand" type="bool" value="true"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-2" type="string" value="tasklist">
              <property name="show-labels" type="bool" value="false"/>
              <property name="show-handle" type="bool" value="false"/>
              <property name="grouping" type="uint" value="0"/>
              <property name="flat-buttons" type="bool" value="true"/>
              <property name="show-tooltips" type="bool" value="true"/>
            </property>
            <property name="plugin-3" type="string" value="clock">
              <property name="mode" type="uint" value="2"/>
              <property name="digital-layout" type="uint" value="3"/>
              <property name="digital-time-format" type="string" value="%R"/>
              <property name="digital-date-format" type="string" value="%a %d %b"/>
              <property name="digital-time-font" type="string" value="DejaVu Sans Bold 12"/>
              <property name="digital-date-font" type="string" value="DejaVu Sans 9"/>
            </property>
            <property name="plugin-20" type="string" value="applicationsmenu">
              <property name="show-button-title" type="bool" value="false"/>
              <property name="button-icon" type="string" value="start-here"/>
            </property>
            <property name="plugin-21" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-terminal.desktop"/>
              </property>
            </property>
            <property name="plugin-22" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-files.desktop"/>
              </property>
            </property>
            <property name="plugin-23" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-browser.desktop"/>
              </property>
            </property>
            <property name="plugin-26" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-vnc-share.desktop"/>
                <value type="string" value="droiddesk-vnc-connect.desktop"/>
                <value type="string" value="droiddesk-vnc-stop.desktop"/>
                <value type="string" value="droiddesk-show-ip.desktop"/>
                <value type="string" value="droiddesk-appearance.desktop"/>
              </property>
            </property>
            <property name="plugin-24" type="string" value="separator">
              <property name="expand" type="bool" value="true"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-25" type="string" value="showdesktop"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun desktopConfig(wallpaperPath: String): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-desktop" version="1.0">
          <property name="last-settings-migration-version" type="uint" value="1"/>
          <property name="desktop-icons" type="empty">
            <property name="icon-size" type="uint" value="48"/>
            <property name="font-size" type="double" value="10.000000"/>
            <property name="use-custom-font-size" type="bool" value="true"/>
            <property name="show-tooltips" type="bool" value="false"/>
            <property name="label-text-shadow" type="bool" value="true"/>
            <property name="file-icons" type="empty">
              <property name="show-home" type="bool" value="true"/>
              <property name="show-filesystem" type="bool" value="false"/>
              <property name="show-removable" type="bool" value="true"/>
              <property name="show-trash" type="bool" value="true"/>
            </property>
          </property>
          <property name="backdrop" type="empty">
            <property name="screen0" type="empty">
              <property name="monitorbuiltin" type="empty">
                <property name="workspace0" type="empty">
                  <property name="color-style" type="int" value="0"/>
                  <property name="image-style" type="int" value="5"/>
                  <property name="last-image" type="string" value="$wallpaperPath"/>
                </property>
              </property>
            </property>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xsettingsConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xsettings" version="1.0">
          <property name="Net" type="empty">
            <property name="ThemeName" type="string" value="Adwaita-dark"/>
            <property name="IconThemeName" type="string" value="Papirus-Dark"/>
            <property name="EnableEventSounds" type="bool" value="false"/>
            <property name="EnableInputFeedbackSounds" type="bool" value="false"/>
          </property>
          <property name="Xft" type="empty">
            <property name="DPI" type="int" value="120"/>
            <property name="Antialias" type="int" value="1"/>
            <property name="Hinting" type="int" value="1"/>
            <property name="HintStyle" type="string" value="hintslight"/>
            <property name="RGBA" type="string" value="rgb"/>
          </property>
          <property name="Gtk" type="empty">
            <property name="FontName" type="string" value="DejaVu Sans 11"/>
            <property name="MonospaceFontName" type="string" value="DejaVu Sans Mono 11"/>
            <property name="CursorThemeSize" type="int" value="32"/>
            <property name="DecorationLayout" type="string" value="menu:minimize,maximize,close"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xfwm4Config(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfwm4" version="1.0">
          <property name="general" type="empty">
            <property name="theme" type="string" value="ServalDesk-wm"/>
            <property name="title_font" type="string" value="DejaVu Sans Bold 11"/>
            <property name="title_alignment" type="string" value="center"/>
            <property name="button_layout" type="string" value="O|HMC"/>
            <property name="easy_click" type="string" value="Alt"/>
            <property name="snap_to_border" type="bool" value="true"/>
            <property name="snap_to_windows" type="bool" value="true"/>
            <property name="snap_width" type="int" value="16"/>
            <property name="wrap_windows" type="bool" value="false"/>
            <property name="wrap_workspaces" type="bool" value="false"/>
            <property name="box_move" type="bool" value="false"/>
            <property name="box_resize" type="bool" value="false"/>
            <property name="raise_with_any_button" type="bool" value="true"/>
            <property name="click_to_focus" type="bool" value="true"/>
            <property name="focus_delay" type="int" value="0"/>
            <property name="double_click_distance" type="int" value="10"/>
            <property name="double_click_time" type="int" value="400"/>
            <property name="tile_on_move" type="bool" value="true"/>
            <property name="use_compositing" type="bool" value="true"/>
            <property name="show_frame_shadow" type="bool" value="true"/>
            <property name="show_popup_shadow" type="bool" value="true"/>
            <property name="frame_opacity" type="int" value="100"/>
            <property name="inactive_opacity" type="int" value="92"/>
            <property name="move_opacity" type="int" value="88"/>
            <property name="resize_opacity" type="int" value="88"/>
            <property name="margin_top" type="int" value="40"/>
            <property name="margin_bottom" type="int" value="64"/>
            <property name="margin_left" type="int" value="0"/>
            <property name="margin_right" type="int" value="0"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

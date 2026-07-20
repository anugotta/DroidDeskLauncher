package com.servaldesk.app.runtime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.io.File

/**
 * ServalDesk appearance: dark / light skins, high-contrast chrome, and
 * custom place icons so folders never disappear into the background.
 */
object ServalDeskAppearance {
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"
    private const val PREF_REL = ".config/servaldesk/appearance"
    private const val CSS_DARK_REL = ".config/servaldesk/gtk-dark.css"
    private const val CSS_LIGHT_REL = ".config/servaldesk/gtk-light.css"

    fun currentMode(homeDir: File): String {
        val pref = File(homeDir, PREF_REL)
        val raw = pref.takeIf { it.isFile }?.readText()?.trim()?.lowercase().orEmpty()
        return if (raw == MODE_LIGHT) MODE_LIGHT else MODE_DARK
    }

    fun setMode(homeDir: File, mode: String) {
        val pref = File(homeDir, PREF_REL)
        pref.parentFile?.mkdirs()
        pref.writeText(if (mode == MODE_LIGHT) MODE_LIGHT else MODE_DARK)
    }

    fun install(homeDir: File) {
        File(homeDir, ".config/servaldesk").mkdirs()
        if (!File(homeDir, PREF_REL).exists()) setMode(homeDir, MODE_DARK)
        installIconTheme(homeDir)
        File(homeDir, CSS_DARK_REL).writeText(chromeCss(MODE_DARK))
        File(homeDir, CSS_LIGHT_REL).writeText(chromeCss(MODE_LIGHT))
        apply(homeDir, currentMode(homeDir))
    }

    /** Best-effort Papirus install (Xubuntu-like modern icons). */
    fun installIconsScript(): String = """
        #!/bin/sh
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        MARK="${'$'}HOME/.config/servaldesk/papirus-installed"
        mkdir -p "${'$'}HOME/.config/servaldesk" "${'$'}HOME/.icons" "${'$'}HOME/.local/share/icons"
        has_papirus() {
          for t in Papirus Papirus-Dark; do
            [ -d "${'$'}HOME/.icons/${'$'}t" ] && return 0
            [ -d "${'$'}HOME/.local/share/icons/${'$'}t" ] && return 0
            [ -d "${'$'}PREFIX/share/icons/${'$'}t" ] && return 0
            [ -d "/usr/share/icons/${'$'}t" ] && return 0
          done
          return 1
        }
        if has_papirus; then
          echo ok > "${'$'}MARK"
          exit 0
        fi
        # Termux / proot package first.
        if command -v pkg >/dev/null 2>&1; then
          pkg install -y papirus-icon-theme >/dev/null 2>&1 || true
        fi
        if command -v apt-get >/dev/null 2>&1; then
          apt-get install -y papirus-icon-theme >/dev/null 2>&1 || true
        fi
        if has_papirus; then
          echo ok > "${'$'}MARK"
          exit 0
        fi
        # Official Papirus installer into user icon dirs.
        if command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1; then
          TMP="${'$'}HOME/.cache/servaldesk-papirus"
          mkdir -p "${'$'}TMP"
          URL="https://github.com/PapirusDevelopmentTeam/papirus-icon-theme/archive/refs/heads/master.tar.gz"
          ARCHIVE="${'$'}TMP/papirus.tgz"
          if command -v curl >/dev/null 2>&1; then
            curl -fsSL --connect-timeout 20 --max-time 180 -o "${'$'}ARCHIVE" "${'$'}URL" || true
          else
            wget -q -O "${'$'}ARCHIVE" "${'$'}URL" || true
          fi
          if [ -f "${'$'}ARCHIVE" ] && [ -s "${'$'}ARCHIVE" ]; then
            tar -xzf "${'$'}ARCHIVE" -C "${'$'}TMP" 2>/dev/null || true
            SRC=${'$'}(find "${'$'}TMP" -maxdepth 2 -type d -name 'papirus-icon-theme-*' | head -n1)
            if [ -n "${'$'}SRC" ]; then
              for t in Papirus Papirus-Dark Papirus-Light; do
                if [ -d "${'$'}SRC/${'$'}t" ]; then
                  rm -rf "${'$'}HOME/.icons/${'$'}t"
                  cp -a "${'$'}SRC/${'$'}t" "${'$'}HOME/.icons/${'$'}t"
                fi
              done
            fi
          fi
          rm -rf "${'$'}TMP"
        fi
        if has_papirus; then
          echo ok > "${'$'}MARK"
          APPEAR=dark
          [ -f "${'$'}HOME/.config/servaldesk/appearance" ] && \
            APPEAR=${'$'}(tr -d '[:space:]' < "${'$'}HOME/.config/servaldesk/appearance")
          ICONS=Papirus-Dark
          [ "${'$'}APPEAR" = "light" ] && ICONS=Papirus
          if command -v xfconf-query >/dev/null 2>&1; then
            xfconf-query -c xsettings -p /Net/IconThemeName -n -t string -s "${'$'}ICONS" 2>/dev/null || \
              xfconf-query -c xsettings -p /Net/IconThemeName -s "${'$'}ICONS" 2>/dev/null || true
          fi
          if command -v notify-send >/dev/null 2>&1; then
            notify-send -a ServalDesk "Icons" "Papirus icon theme installed." 2>/dev/null || true
          fi
          exit 0
        fi
        echo fail > "${'$'}MARK"
        exit 1
    """.trimIndent() + "\n"

    /** Copy active skin into GTK user CSS + settings.ini. */
    fun apply(homeDir: File, mode: String) {
        val resolved = if (mode == MODE_LIGHT) MODE_LIGHT else MODE_DARK
        setMode(homeDir, resolved)
        val src = File(homeDir, if (resolved == MODE_LIGHT) CSS_LIGHT_REL else CSS_DARK_REL)
        val dest = File(homeDir, ".config/gtk-3.0/gtk.css")
        dest.parentFile?.mkdirs()
        val startMarker = "/* ServalDesk chrome start */"
        val endMarker = "/* ServalDesk chrome end */"
        val legacyMarkers = listOf(
            startMarker to endMarker,
            "/* DroidDesk mobile panel start */" to "/* DroidDesk mobile panel end */",
        )
        var existing = if (dest.exists()) dest.readText() else ""
        legacyMarkers.forEach { (start, end) ->
            existing = existing.replace(
                Regex(
                    Regex.escape(start) + ".*?" + Regex.escape(end),
                    setOf(RegexOption.DOT_MATCHES_ALL),
                ),
                "",
            ).trimEnd()
        }
        val block = src.takeIf { it.isFile }?.readText() ?: chromeCss(resolved)
        dest.writeText(
            if (existing.isEmpty()) block.trimEnd() + "\n"
            else existing.trimEnd() + "\n\n" + block.trimEnd() + "\n",
        )

        val preferDark = resolved == MODE_DARK
        val iconTheme = resolveIconThemeName(homeDir, resolved)
        File(homeDir, ".config/gtk-3.0/settings.ini").writeText(
            """
            [Settings]
            gtk-theme-name=${if (preferDark) "Adwaita-dark" else "Adwaita"}
            gtk-icon-theme-name=$iconTheme
            gtk-font-name=DejaVu Sans 11
            gtk-cursor-theme-size=32
            gtk-xft-dpi=120000
            gtk-enable-animations=true
            gtk-application-prefer-dark-theme=$preferDark
            """.trimIndent() + "\n",
        )
    }

    private fun resolveIconThemeName(homeDir: File, mode: String): String {
        val preferred = if (mode == MODE_LIGHT) {
            listOf("Papirus", "Papirus-Dark", "elementary-xfce", "Adwaita")
        } else {
            listOf("Papirus-Dark", "Papirus", "elementary-xfce-darker", "Adwaita")
        }
        val roots = listOf(
            File(homeDir, ".icons"),
            File(homeDir, ".local/share/icons"),
            File(System.getenv("PREFIX") ?: "", "share/icons"),
            File("/usr/share/icons"),
        )
        for (name in preferred) {
            if (roots.any { File(it, name).isDirectory }) return name
        }
        return preferred.first()
    }

    fun switchScript(): String = """
        #!/bin/sh
        export DISPLAY="${'$'}{DISPLAY:-:0}"
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        MODE="${'$'}{1:-}"
        PREF="${'$'}HOME/.config/servaldesk/appearance"
        CUR=dark
        [ -f "${'$'}PREF" ] && CUR=${'$'}(tr -d '[:space:]' < "${'$'}PREF")
        if [ -z "${'$'}MODE" ]; then
          if [ "${'$'}CUR" = "light" ]; then MODE=dark; else MODE=light; fi
        fi
        case "${'$'}MODE" in light|dark) ;; *) MODE=dark ;; esac
        mkdir -p "${'$'}HOME/.config/servaldesk" "${'$'}HOME/.config/gtk-3.0"
        echo "${'$'}MODE" > "${'$'}PREF"
        if [ "${'$'}MODE" = "light" ]; then
          SRC="${'$'}HOME/.config/servaldesk/gtk-light.css"
          THEME=Adwaita
          ICONS=Papirus
          PREFER=false
          # Warm paper panels
          PR=0.965; PG=0.945; PB=0.910; PA=0.96
          WALL=""
          for c in \
            "${'$'}HOME/.local/share/backgrounds/droiddesk/valley.jpg" \
            "${'$'}HOME/.local/share/backgrounds/droiddesk/ocean.jpg" \
            "${'$'}HOME/.local/share/wallpapers/valley.jpg"
          do
            [ -f "${'$'}c" ] && WALL="${'$'}c" && break
          done
          WM=Default
        else
          SRC="${'$'}HOME/.config/servaldesk/gtk-dark.css"
          THEME=Adwaita-dark
          ICONS=Papirus-Dark
          PREFER=true
          PR=0.055; PG=0.070; PB=0.120; PA=0.92
          WALL=""
          for c in \
            "${'$'}HOME/.local/share/backgrounds/droiddesk/city-night.jpg" \
            "${'$'}HOME/.local/share/backgrounds/droiddesk/mountains.jpg" \
            "${'$'}HOME/.local/share/wallpapers/city-night.jpg"
          do
            [ -f "${'$'}c" ] && WALL="${'$'}c" && break
          done
          WM=ServalDesk-wm
        fi
        # Fall back if Papirus is not installed yet.
        for t in "${'$'}ICONS" Papirus-Dark Papirus Adwaita; do
          if [ -d "${'$'}HOME/.icons/${'$'}t" ] || [ -d "${'$'}HOME/.local/share/icons/${'$'}t" ] || \
             [ -d "${'$'}PREFIX/share/icons/${'$'}t" ] || [ -d "/usr/share/icons/${'$'}t" ]; then
            ICONS="${'$'}t"; break
          fi
        done
        if [ -f "${'$'}SRC" ]; then
          # Replace managed chrome block if present, else append.
          DEST="${'$'}HOME/.config/gtk-3.0/gtk.css"
          if [ -f "${'$'}DEST" ] && grep -q "ServalDesk chrome start" "${'$'}DEST" 2>/dev/null; then
            awk '
              BEGIN{skip=0}
              /ServalDesk chrome start/{skip=1; next}
              /ServalDesk chrome end/{skip=0; next}
              !skip{print}
            ' "${'$'}DEST" > "${'$'}DEST.tmp" 2>/dev/null || cp "${'$'}DEST" "${'$'}DEST.tmp"
            cat "${'$'}DEST.tmp" "${'$'}SRC" > "${'$'}DEST"
            rm -f "${'$'}DEST.tmp"
          else
            cat "${'$'}SRC" > "${'$'}DEST"
          fi
        fi
        cat > "${'$'}HOME/.config/gtk-3.0/settings.ini" <<EOF
[Settings]
gtk-theme-name=${'$'}THEME
gtk-icon-theme-name=${'$'}ICONS
gtk-font-name=DejaVu Sans 11
gtk-cursor-theme-size=32
gtk-xft-dpi=120000
gtk-enable-animations=true
gtk-application-prefer-dark-theme=${'$'}PREFER
EOF
        if command -v xfconf-query >/dev/null 2>&1; then
          xfconf-query -c xsettings -p /Net/ThemeName -n -t string -s "${'$'}THEME" 2>/dev/null || \
            xfconf-query -c xsettings -p /Net/ThemeName -s "${'$'}THEME" 2>/dev/null || true
          xfconf-query -c xsettings -p /Net/IconThemeName -n -t string -s "${'$'}ICONS" 2>/dev/null || \
            xfconf-query -c xsettings -p /Net/IconThemeName -s "${'$'}ICONS" 2>/dev/null || true
          xfconf-query -c xfwm4 -p /general/theme -n -t string -s "${'$'}WM" 2>/dev/null || \
            xfconf-query -c xfwm4 -p /general/theme -s "${'$'}WM" 2>/dev/null || true
          for p in 1 2; do
            xfconf-query -c xfce4-panel -p /panels/panel-${'$'}p/background-style -s 1 2>/dev/null || true
            xfconf-query -c xfce4-panel -p /panels/panel-${'$'}p/background-rgba \
              -t double -t double -t double -t double \
              -s "${'$'}PR" -s "${'$'}PG" -s "${'$'}PB" -s "${'$'}PA" 2>/dev/null || true
          done
          if [ -f "${'$'}WALL" ]; then
            xfconf-query -c xfce4-desktop -p /backdrop/screen0/monitorbuiltin/workspace0/last-image \
              -n -t string -s "${'$'}WALL" 2>/dev/null || \
              xfconf-query -c xfce4-desktop -p /backdrop/screen0/monitorbuiltin/workspace0/last-image \
              -s "${'$'}WALL" 2>/dev/null || true
          fi
        fi
        # Reload shell chrome (apps already open may need a reopen).
        if command -v xfce4-panel >/dev/null 2>&1; then
          xfce4-panel -r >/dev/null 2>&1 || true
        fi
        if command -v xfdesktop >/dev/null 2>&1; then
          xfdesktop --reload >/dev/null 2>&1 || true
        fi
        LABEL="Dark"
        [ "${'$'}MODE" = "light" ] && LABEL="Light"
        if command -v notify-send >/dev/null 2>&1; then
          notify-send -a ServalDesk "Appearance" "Switched to ${'$'}LABEL mode.
Reopen open windows to refresh fully." 2>/dev/null || true
        fi
    """.trimIndent() + "\n"

    fun chromeCss(mode: String): String {
        val light = mode == MODE_LIGHT
        // Distinct layers — text and fill always paired for contrast.
        val voidC = if (light) "#F4F0E8" else "#1A2233"
        val surface = if (light) "#FFFcf7" else "#242C40"
        val raised = if (light) "#F0EBE1" else "#2E3750"
        val border = if (light) "#D5CBB8" else "#4A556E"
        val text = if (light) "#1A1F2E" else "#F3EFE6"
        val muted = if (light) "#5C6578" else "#A8B0C0"
        val gold = if (light) "#B8842C" else "#E0B06A"
        val violet = if (light) "#5B45C0" else "#8B74E8"
        val sidebar = if (light) "#EDE6DA" else "#1E2638"
        val content = if (light) "#FFFcf7" else "#222B3E"
        val panelFg = if (light) "#1A1F2E" else "#F3EFE6"
        val selectBg = if (light) "#7C6BC8" else "#A78BFA"
        val selectFg = if (light) "#FFFFFF" else "#1A1F2E"
        val hoverBg = if (light) "#9B8AD8" else "#8B7AC8"
        val hoverShade = if (light) "0.96" else "1.12"
        return """
            /* ServalDesk chrome start */
            /* mode=$mode — paired bg/fg everywhere (no white-on-white / black-on-black) */
            @define-color bg_color $surface;
            @define-color fg_color $text;
            @define-color base_color $content;
            @define-color text_color $text;
            @define-color selected_bg_color $selectBg;
            @define-color selected_fg_color $selectFg;
            @define-color theme_bg_color $surface;
            @define-color theme_fg_color $text;
            @define-color theme_base_color $content;
            @define-color theme_text_color $text;
            @define-color theme_selected_bg_color $selectBg;
            @define-color theme_selected_fg_color $selectFg;
            @define-color theme_button_bg_color $raised;
            @define-color theme_button_fg_color $text;
            @define-color theme_unfocused_bg_color $surface;
            @define-color theme_unfocused_fg_color $muted;
            @define-color theme_unfocused_base_color $content;
            @define-color theme_unfocused_text_color $muted;
            @define-color theme_unfocused_selected_bg_color $selectBg;
            @define-color theme_unfocused_selected_fg_color #FFFFFF;
            @define-color insensitive_bg_color $surface;
            @define-color insensitive_fg_color $muted;
            @define-color insensitive_base_color $voidC;
            @define-color borders $border;
            @define-color unfocused_borders $border;
            @define-color content_view_bg $content;
            @define-color sidebar_bg $sidebar;
            @define-color serval_void $voidC;
            @define-color serval_surface $surface;
            @define-color serval_raised $raised;
            @define-color serval_border $border;
            @define-color serval_gold $gold;
            @define-color serval_violet $violet;
            @define-color serval_text $text;
            @define-color serval_muted $muted;
            @define-color serval_panel_fg $panelFg;

            /* Universal readable text — never inherit a light fg onto a light base */
            label,
            .label {
              color: @serval_text;
              text-shadow: none;
              opacity: 1;
            }
            .background label,
            dialog label,
            notebook label,
            frame label,
            scrolledwindow label,
            viewport label,
            box label,
            grid label {
              color: @serval_text;
            }
            label:disabled,
            .label:disabled,
            label:insensitive {
              color: @serval_muted;
              opacity: 1;
            }

            window,
            .background,
            dialog,
            messagedialog,
            .app-notification {
              background-color: @serval_surface;
              color: @serval_text;
              background-image: none;
              border-color: @serval_border;
            }

            /* Settings / XFCE dialogs: force content layers (not every box —
               that would paint over transparent panels) */
            notebook,
            notebook > stack,
            notebook > stack > *,
            stack > scrolledwindow,
            scrolledwindow,
            scrolledwindow > viewport,
            viewport,
            frame,
            .frame,
            frame > border,
            .dialog-vbox,
            .dialog-action-area,
            .content-view,
            .view,
            iconview,
            treeview,
            list,
            listview,
            list row,
            textview,
            textview text,
            calendar,
            .search-bar {
              background-color: @serval_surface;
              color: @serval_text;
              background-image: none;
              border-color: @serval_border;
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
              -gtk-icon-style: regular;
            }
            /* List/tree selection (sidebars, details view) */
            treeview:selected,
            treeview:selected:focus,
            list row:selected,
            treeview.view:selected,
            .cell:selected,
            .sidebar row:selected {
              background-color: $selectBg;
              color: $selectFg;
              border-radius: 8px;
              border: none;
              outline-style: none;
            }
            list row:selected label,
            treeview:selected label,
            treeview:selected:focus label,
            .sidebar row:selected label,
            .cell:selected {
              color: $selectFg;
              background-color: transparent;
            }

            /*
             * Icon-grid / menu selection: bright tint only (no rings).
             * Colors are mode-aware ($selectBg / $hoverBg).
             */
            iconview:selected,
            iconview:selected:focus,
            .iconview:selected,
            .thunar iconview:selected,
            .thunar iconview:selected:focus,
            .thunar .standard-view:selected {
              background-image: none;
              background-color: $selectBg;
              color: $selectFg;
              border: none;
              border-radius: 12px;
              outline-style: none;
              outline-width: 0;
              outline-color: transparent;
              box-shadow: none;
            }
            iconview:selected label,
            iconview:selected:focus label,
            .iconview:selected label,
            .thunar iconview:selected label,
            .thunar iconview:selected:focus label,
            .thunar .standard-view:selected label {
              color: $selectFg;
              background-color: transparent;
              background-image: none;
              border: none;
              border-radius: 0;
              box-shadow: none;
              outline-style: none;
              outline-width: 0;
              text-shadow: none;
              padding: 2px 0 0 0;
            }
            iconview:hover,
            .thunar iconview:hover,
            .thunar .standard-view:hover {
              background-image: none;
              background-color: $hoverBg;
              border-radius: 12px;
              border: none;
              outline-style: none;
              color: $selectFg;
            }

            /* Kill Adwaita focus rings / yellow outlines */
            iconview:focus,
            .thunar iconview:focus,
            .thunar .view:focus,
            .thunar .standard-view:focus,
            *:selected:focus {
              outline-style: none;
              outline-width: 0;
              box-shadow: none;
              border-style: none;
            }

            /* Thunar sidebar */
            .thunar .sidebar .view:selected,
            .thunar .sidebar treeview:selected,
            .thunar .sidebar treeview:selected:focus {
              background-image: none;
              background-color: $selectBg;
              color: $selectFg;
              border: none;
              border-radius: 8px;
              outline-style: none;
              outline-width: 0;
              box-shadow: none;
            }
            .thunar .sidebar .view:selected label,
            .thunar .sidebar treeview:selected label {
              color: $selectFg;
              background-color: transparent;
              border: none;
            }

            /* App menus / popovers — same tint, no white scroll caps */
            menu menuitem:hover,
            .menu menuitem:hover,
            .xfce4-panel menu menuitem:hover,
            popover modelbutton:hover {
              background-color: $selectBg;
              color: $selectFg;
            }
            menu menuitem:hover label,
            .menu menuitem:hover label {
              color: $selectFg !important;
            }
            overshoot.top,
            overshoot.bottom,
            overshoot.left,
            overshoot.right,
            undershoot.top,
            undershoot.bottom,
            undershoot.left,
            undershoot.right {
              background-color: transparent;
              background-image: none;
              border: none;
              box-shadow: none;
            }
            scrollbar,
            scrollbar trough,
            scrollbar.horizontal,
            scrollbar.vertical {
              background-color: transparent;
              border: none;
              background-image: none;
            }

            /* Tree/sidebars: every cell stays readable */
            treeview,
            treeview.view,
            .sidebar treeview,
            .sidebar .view {
              color: @serval_text;
              background-color: @sidebar_bg;
            }
            treeview.view:hover,
            .sidebar row:hover {
              background-color: alpha(@serval_violet, 0.18);
              color: @serval_text;
            }

            toolbar,
            .toolbar,
            .primary-toolbar,
            actionbar,
            .action-bar,
            searchbar {
              background-image: none;
              background-color: @serval_raised;
              color: @serval_text;
              border-color: @serval_border;
            }
            toolbar label,
            actionbar label {
              color: @serval_text;
            }

            .sidebar,
            placessidebar,
            .thunar .sidebar,
            stacksidebar,
            .navigation-sidebar {
              background-color: @sidebar_bg;
              color: @serval_text;
              background-image: none;
              border-right: 1px solid @serval_border;
            }
            .sidebar label,
            placessidebar label {
              color: @serval_text;
            }
            .sidebar row:selected,
            placessidebar row:selected,
            .thunar .sidebar row:selected {
              background-color: alpha(@serval_violet, 0.35);
              color: #ffffff;
              border-radius: 8px;
            }
            .sidebar row:selected label {
              color: #ffffff;
            }

            paned > separator {
              background-color: @serval_border;
              min-width: 2px;
              min-height: 2px;
            }

            entry, .entry, spinbutton, spinbutton entry {
              min-height: 32px;
              border-radius: 8px;
              background-image: none;
              background-color: @serval_raised;
              color: @serval_text;
              border: 1px solid @serval_border;
              padding: 4px 10px;
              box-shadow: none;
              caret-color: @serval_text;
            }
            entry:focus {
              border-color: @serval_violet;
            }
            entry selection,
            textview text selection {
              background-color: @serval_violet;
              color: #ffffff;
            }

            button,
            .button,
            combobox > .linked > button,
            combobox button,
            dropdown,
            .combo {
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
            button label,
            combobox label {
              color: @serval_text;
            }
            button:hover {
              background-color: shade(@serval_raised, $hoverShade);
              border-color: alpha(@serval_violet, 0.55);
            }
            button:checked,
            button:active,
            button.suggested-action {
              background-color: @serval_violet;
              color: #ffffff;
              border-color: shade(@serval_violet, 0.9);
            }
            button:checked label,
            button.suggested-action label {
              color: #ffffff;
            }

            checkbutton,
            radiobutton {
              color: @serval_text;
            }
            checkbutton label,
            radiobutton label {
              color: @serval_text;
            }
            checkbutton check,
            radiobutton radio {
              background-color: @serval_raised;
              border: 1px solid @serval_border;
              color: @serval_text;
            }
            checkbutton check:checked,
            radiobutton radio:checked {
              background-color: @serval_violet;
              border-color: @serval_violet;
              color: #ffffff;
            }

            switch {
              background-color: @serval_raised;
              border: 1px solid @serval_border;
              color: @serval_text;
            }
            switch:checked {
              background-color: @serval_violet;
            }

            scale trough {
              background-color: @serval_raised;
              border-color: @serval_border;
            }
            scale highlight {
              background-color: @serval_violet;
            }
            scale slider {
              background-color: @serval_gold;
              border: 1px solid @serval_border;
            }

            progressbar trough {
              background-color: @serval_raised;
            }
            progressbar progress {
              background-color: @serval_violet;
            }

            menubar,
            .menubar {
              background-image: none;
              background-color: @serval_surface;
              color: @serval_text;
              border: none;
              box-shadow: none;
              text-shadow: none;
            }
            menubar > menuitem,
            .menubar > menuitem {
              color: @serval_text;
            }
            menubar > menuitem:hover,
            .menubar > menuitem:hover {
              background-color: $selectBg;
              color: $selectFg;
            }
            menubar > menuitem label {
              color: inherit;
            }

            headerbar, .titlebar {
              background-image: none;
              background-color: @serval_surface;
              color: @serval_text;
              border-bottom: 1px solid @serval_border;
              box-shadow: none;
              text-shadow: none;
            }
            headerbar label,
            .titlebar label {
              color: @serval_text;
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
              background-color: transparent;
            }
            notebook > header tab label {
              color: @serval_muted;
            }
            notebook > header tab:checked {
              color: @serval_text;
              background-color: @serval_raised;
              box-shadow: inset 0 -2px 0 @serval_gold;
            }
            notebook > header tab:checked label {
              color: @serval_text;
            }
            notebook > stack > box,
            notebook > stack > scrolledwindow,
            notebook stack frame {
              background-color: @serval_surface;
              color: @serval_text;
            }

            scrollbar slider {
              min-width: 10px;
              min-height: 10px;
              border-radius: 8px;
              background-color: alpha(@serval_violet, 0.45);
              border: none;
            }

            separator,
            .separator {
              background-color: @serval_border;
              min-height: 1px;
              min-width: 1px;
            }

            /* XFCE settings apps (Power Manager, Appearance, etc.) */
            .xfce4-settings-manager-dialog,
            .xfce4-settings-editor,
            .xfce4-power-manager-settings,
            window.xfce4-power-manager-settings,
            #xfce4-power-manager-settings,
            dialog.xfce {
              background-color: @serval_surface;
              color: @serval_text;
            }
            .xfce4-power-manager-settings label,
            .xfce4-settings-manager-dialog label,
            window.xfce4-power-manager-settings label {
              color: @serval_text;
            }

            /* Thunar */
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
              border-bottom: 1px solid @serval_border;
            }
            .thunar .standard-view,
            .thunar .view,
            .thunar iconview {
              background-color: @content_view_bg;
              color: @serval_text;
              -gtk-icon-style: regular;
            }
            .thunar .sidebar treeview,
            .thunar .sidebar .view {
              background-color: @sidebar_bg;
              color: @serval_text;
              -gtk-icon-style: regular;
            }
            iconview,
            .iconview,
            .view.icon {
              -gtk-icon-style: regular;
            }

            /* Terminal / xfce4-terminal */
            .terminal-window,
            .terminal-screen,
            vte-terminal {
              background-color: @serval_void;
              color: @serval_text;
            }

            .xfce4-panel {
              color: @serval_panel_fg;
              font-weight: 500;
              border-color: @serval_border;
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
              color: @serval_panel_fg;
              box-shadow: none;
              text-shadow: none;
            }
            .xfce4-panel button:hover {
              background-color: alpha(#A78BFA, 0.22);
              border: none;
            }
            .xfce4-panel button:checked,
            .xfce4-panel button:active {
              background-color: alpha(#A78BFA, 0.36);
              border: none;
              color: @serval_panel_fg;
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
              color: @serval_panel_fg;
              opacity: 1;
              text-shadow: none;
            }
            .xfce4-panel .clock label,
            .xfce4-panel .digital-clock label,
            #clock-button label {
              color: @serval_panel_fg;
              font-weight: 600;
              letter-spacing: 0.04em;
            }
            .xfce4-panel image,
            .xfce4-panel button image {
              color: @serval_panel_fg;
              -gtk-icon-recoloring: true;
              opacity: 1;
            }

            menu,
            .menu,
            .xfce4-panel menu,
            window.popup menu,
            popover,
            .popover,
            tooltip {
              background-color: @serval_surface;
              color: @serval_text;
              border: 1px solid @serval_border;
              border-radius: 12px;
              padding: 6px;
            }
            menu menuitem,
            .menu menuitem,
            .xfce4-panel menu menuitem,
            popover label {
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
              background-color: alpha(@serval_violet, 0.28);
              color: #ffffff !important;
            }
            menu menuitem:hover label {
              color: #ffffff !important;
            }

            tooltip {
              border-radius: 8px;
            }
            tooltip label {
              color: @serval_text;
            }

            /* Desktop icons — soft label chip so text never merges into wallpaper */
            XfdesktopIconView {
              -XfdesktopIconView-label-alpha: 90;
              color: #FFFFFF;
            }
            XfdesktopIconView .label,
            .xfdesktop-icon-view .label {
              color: #FFFFFF;
              background-color: alpha(#0C1018, 0.45);
              text-shadow: 0 1px 2px alpha(#000000, 0.85);
              border-radius: 6px;
              padding: 1px 6px;
              border: none;
            }
            XfdesktopIconView:active,
            .xfdesktop-icon-view:selected {
              background-color: $selectBg;
              border-radius: 10px;
              outline-style: none;
            }
            XfdesktopIconView:active .label,
            .xfdesktop-icon-view:selected .label {
              background-color: transparent;
              color: $selectFg;
              border: none;
              border-radius: 0;
              text-shadow: none;
            }
            /* ServalDesk chrome end */
        """.trimIndent() + "\n"
    }

    /** Bright branded place icons — readable on both dark and light views. */
    private fun installIconTheme(homeDir: File) {
        val root = File(homeDir, ".icons/ServalDesk").apply { mkdirs() }
        File(root, "index.theme").writeText(
            """
            [Icon Theme]
            Name=ServalDesk
            Comment=ServalDesk high-contrast places
            Inherits=Papirus-Dark,Papirus,Adwaita,hicolor
            Directories=48x48/places,64x64/places,128x128/places,scalable/places

            [48x48/places]
            Size=48
            Context=Places
            Type=Fixed

            [64x64/places]
            Size=64
            Context=Places
            Type=Fixed

            [128x128/places]
            Size=128
            Context=Places
            Type=Fixed

            [scalable/places]
            Size=128
            Context=Places
            Type=Scalable
            MinSize=16
            MaxSize=512
            """.trimIndent() + "\n",
        )
        for (size in listOf(48, 64, 128)) {
            val dir = File(root, "${size}x${size}/places").apply { mkdirs() }
            writeFolderPng(File(dir, "folder.png"), size, style = FolderStyle.STANDARD)
            writeFolderPng(File(dir, "folder-documents.png"), size, style = FolderStyle.STANDARD)
            writeFolderPng(File(dir, "user-home.png"), size, style = FolderStyle.HOME)
            writeFolderPng(File(dir, "user-desktop.png"), size, style = FolderStyle.DESKTOP)
            writeFolderPng(File(dir, "folder-download.png"), size, style = FolderStyle.STANDARD)
            writeDrivePng(File(dir, "drive-harddisk.png"), size)
            writeDrivePng(File(dir, "drive-harddisk-system.png"), size)
        }
        File(root, "scalable/places").mkdirs()
    }

    private enum class FolderStyle { STANDARD, HOME, DESKTOP }

    private fun writeFolderPng(file: File, size: Int, style: FolderStyle) {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = size * 0.08f
        val body = RectF(pad, size * 0.28f, size - pad, size - pad)
        val tab = RectF(pad, size * 0.16f, size * 0.48f, size * 0.34f)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000
        }
        canvas.drawRoundRect(
            RectF(body.left + 2, body.top + 3, body.right + 2, body.bottom + 3),
            size * 0.08f, size * 0.08f, shadow,
        )

        val folderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, tab.top, 0f, body.bottom,
                intArrayOf(0xFFF0C56A.toInt(), 0xFFE8A23A.toInt(), 0xFFC4892E.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(tab, size * 0.06f, size * 0.06f, folderPaint)
        canvas.drawRoundRect(body, size * 0.1f, size * 0.1f, folderPaint)

        val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, body.top, 0f, body.top + size * 0.25f,
                intArrayOf(0x66FFFFFF, 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(body, size * 0.1f, size * 0.1f, shine)

        when (style) {
            FolderStyle.HOME -> {
                val h = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
                val cx = size / 2f
                val cy = size * 0.58f
                val path = Path().apply {
                    moveTo(cx, cy - size * 0.14f)
                    lineTo(cx + size * 0.16f, cy)
                    lineTo(cx + size * 0.10f, cy)
                    lineTo(cx + size * 0.10f, cy + size * 0.14f)
                    lineTo(cx - size * 0.10f, cy + size * 0.14f)
                    lineTo(cx - size * 0.10f, cy)
                    lineTo(cx - size * 0.16f, cy)
                    close()
                }
                canvas.drawPath(path, h)
            }
            FolderStyle.DESKTOP -> {
                val m = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
                val monitor = RectF(size * 0.32f, size * 0.42f, size * 0.68f, size * 0.62f)
                canvas.drawRoundRect(monitor, size * 0.03f, size * 0.03f, m)
                canvas.drawRect(size * 0.46f, size * 0.62f, size * 0.54f, size * 0.70f, m)
                canvas.drawRect(size * 0.38f, size * 0.70f, size * 0.62f, size * 0.74f, m)
                val screen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6E56CF.toInt() }
                canvas.drawRoundRect(
                    RectF(monitor.left + size * 0.03f, monitor.top + size * 0.03f,
                        monitor.right - size * 0.03f, monitor.bottom - size * 0.03f),
                    size * 0.02f, size * 0.02f, screen,
                )
            }
            FolderStyle.STANDARD -> Unit
        }

        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun writeDrivePng(file: File, size: Int) {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val body = RectF(size * 0.12f, size * 0.28f, size * 0.88f, size * 0.72f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, body.top, 0f, body.bottom,
                intArrayOf(0xFF9AA3B5.toInt(), 0xFF6B7388.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(body, size * 0.08f, size * 0.08f, paint)
        val led = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34D399.toInt() }
        canvas.drawCircle(size * 0.78f, size * 0.50f, size * 0.05f, led)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }
}

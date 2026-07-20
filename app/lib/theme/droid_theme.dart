import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// ServalDesk Design System
///
/// Midnight void + serval-gold + electric violet — inspired by the animal's
/// night coat and amber eyes, not generic indigo cyberpunk.
class DroidTheme {
  DroidTheme._();

  // ── Core Colors ──
  static const Color background = Color(0xFF070B14);
  static const Color surface = Color(0xFF141A28);
  static const Color surfaceLight = Color(0xFF1C2438);
  static const Color surfaceBorder = Color(0xFF2A3348);
  static const Color cardBg = Color(0xFF161D2E);

  // ── Accent Colors ──
  static const Color primary = Color(0xFFE8A23A); // Serval gold
  static const Color primaryLight = Color(0xFFF0B95A);
  static const Color secondary = Color(0xFF9B6DFF); // Electric violet
  static const Color accent = Color(0xFF2DD4BF); // Teal spark
  static const Color warning = Color(0xFFF0B95A);
  static const Color error = Color(0xFFF07178);
  static const Color success = Color(0xFF34D399);

  // ── Text Colors ──
  static const Color textPrimary = Color(0xFFF3F0EA);
  static const Color textSecondary = Color(0xFF9AA3B5);
  static const Color textMuted = Color(0xFF6B7388);
  static const Color textDim = Color(0xFF4A5268);

  // ── Gradients ──
  static const LinearGradient primaryGradient = LinearGradient(
    colors: [Color(0xFFE8A23A), Color(0xFF9B6DFF)],
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );

  static const LinearGradient backgroundGradient = LinearGradient(
    colors: [Color(0xFF070B14), Color(0xFF12182A), Color(0xFF1A1430)],
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
  );

  static const LinearGradient cardGradient = LinearGradient(
    colors: [Color(0xFF1C2438), Color(0xFF141A28)],
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );

  // ── Distro Colors ──
  static const Color ubuntuColor = Color(0xFFE95420);
  static const Color alpineColor = Color(0xFF0D597F);
  static const Color kaliColor = Color(0xFF367BF0);

  // ── Radii ──
  static const double radiusSm = 8.0;
  static const double radiusMd = 14.0;
  static const double radiusLg = 18.0;
  static const double radiusXl = 28.0;

  // ── Spacing ──
  static const double spaceSm = 8.0;
  static const double spaceMd = 16.0;
  static const double spaceLg = 24.0;
  static const double spaceXl = 32.0;
  static const double space2xl = 48.0;

  // ── Typography ──
  static TextStyle get headingXl => GoogleFonts.outfit(
        fontSize: 32,
        fontWeight: FontWeight.w800,
        color: textPrimary,
        letterSpacing: -0.6,
      );

  static TextStyle get headingLg => GoogleFonts.outfit(
        fontSize: 24,
        fontWeight: FontWeight.w700,
        color: textPrimary,
        letterSpacing: -0.4,
      );

  static TextStyle get headingMd => GoogleFonts.outfit(
        fontSize: 20,
        fontWeight: FontWeight.w600,
        color: textPrimary,
      );

  static TextStyle get headingSm => GoogleFonts.outfit(
        fontSize: 16,
        fontWeight: FontWeight.w600,
        color: textPrimary,
      );

  static TextStyle get bodyLg => GoogleFonts.outfit(
        fontSize: 16,
        fontWeight: FontWeight.w400,
        color: textSecondary,
        height: 1.6,
      );

  static TextStyle get bodyMd => GoogleFonts.outfit(
        fontSize: 14,
        fontWeight: FontWeight.w400,
        color: textSecondary,
        height: 1.5,
      );

  static TextStyle get bodySm => GoogleFonts.outfit(
        fontSize: 12,
        fontWeight: FontWeight.w400,
        color: textMuted,
      );

  static TextStyle get mono => GoogleFonts.jetBrainsMono(
        fontSize: 13,
        fontWeight: FontWeight.w400,
        color: textPrimary,
      );

  static TextStyle get monoSm => GoogleFonts.jetBrainsMono(
        fontSize: 11,
        fontWeight: FontWeight.w400,
        color: textMuted,
      );

  static TextStyle get label => GoogleFonts.outfit(
        fontSize: 11,
        fontWeight: FontWeight.w600,
        color: textMuted,
        letterSpacing: 1.3,
      );

  // ── ThemeData ──
  static ThemeData get themeData => ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: background,
        primaryColor: primary,
        colorScheme: const ColorScheme.dark(
          primary: primary,
          secondary: secondary,
          surface: surface,
          error: error,
        ),
        appBarTheme: AppBarTheme(
          backgroundColor: Colors.transparent,
          elevation: 0,
          centerTitle: false,
          titleTextStyle: headingMd,
          iconTheme: const IconThemeData(color: textPrimary),
        ),
        cardTheme: CardThemeData(
          color: cardBg,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusMd),
            side: const BorderSide(color: surfaceBorder, width: 1),
          ),
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            backgroundColor: primary,
            foregroundColor: background,
            elevation: 0,
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(radiusMd),
            ),
            textStyle: GoogleFonts.outfit(
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            foregroundColor: textPrimary,
            side: const BorderSide(color: surfaceBorder),
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(radiusMd),
            ),
            textStyle: GoogleFonts.outfit(
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      );

  // ── Light skin ──
  static const Color lightBackground = Color(0xFFF4F0E8);
  static const Color lightSurface = Color(0xFFFFFCF7);
  static const Color lightCard = Color(0xFFEDE6DA);
  static const Color lightBorder = Color(0xFFD5CBB8);
  static const Color lightText = Color(0xFF1A1F2E);
  static const Color lightMuted = Color(0xFF5C6578);

  static ThemeData get lightThemeData => ThemeData(
        brightness: Brightness.light,
        scaffoldBackgroundColor: lightBackground,
        primaryColor: primary,
        colorScheme: const ColorScheme.light(
          primary: primary,
          secondary: secondary,
          surface: lightSurface,
          error: error,
          onPrimary: Color(0xFF1A1F2E),
          onSurface: lightText,
        ),
        appBarTheme: AppBarTheme(
          backgroundColor: Colors.transparent,
          elevation: 0,
          centerTitle: false,
          titleTextStyle: GoogleFonts.outfit(
            fontSize: 20,
            fontWeight: FontWeight.w600,
            color: lightText,
          ),
          iconTheme: const IconThemeData(color: lightText),
        ),
        cardTheme: CardThemeData(
          color: lightCard,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusMd),
            side: const BorderSide(color: lightBorder, width: 1),
          ),
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            backgroundColor: primary,
            foregroundColor: const Color(0xFF1A1F2E),
            elevation: 0,
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(radiusMd),
            ),
            textStyle: GoogleFonts.outfit(
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            foregroundColor: lightText,
            side: const BorderSide(color: lightBorder),
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(radiusMd),
            ),
            textStyle: GoogleFonts.outfit(
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      );
}

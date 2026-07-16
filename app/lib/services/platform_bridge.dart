import 'package:flutter/services.dart';

/// Platform channel bridge to communicate with the Kotlin native layer.
///
/// All heavy work (bootstrap extraction, native pkg install, process management)
/// runs on the Kotlin side. Flutter calls into Kotlin via MethodChannel and
/// receives callbacks.
class DroidDeskPlatform {
  static const _channel = MethodChannel('com.droiddesk/core');

  // Callback handlers (set by the UI layer)
  static Function(double progress, String status)? onDownloadProgress;
  static Function(double progress, String status)? onExtractProgress;
  static Function(double progress, String status)? onInstallProgress;
  static Function(String text)? onTerminalOutput;

  /// Initialize platform channel listeners
  static void init() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onDownloadProgress':
          final args = call.arguments as Map;
          onDownloadProgress?.call(
            (args['progress'] as num).toDouble(),
            args['status'] as String,
          );
          break;
        case 'onExtractProgress':
          final args = call.arguments as Map;
          onExtractProgress?.call(
            (args['progress'] as num).toDouble(),
            args['status'] as String,
          );
          break;
        case 'onInstallProgress':
          final args = call.arguments as Map;
          onInstallProgress?.call(
            (args['progress'] as num).toDouble(),
            args['status'] as String,
          );
          break;
        case 'onTerminalOutput':
          final args = call.arguments as Map;
          onTerminalOutput?.call(args['text'] as String);
          break;
      }
    });
  }

  // ── Runtime Status ──

  static Future<Map<String, dynamic>> getRuntimeStatus() async {
    final result = await _channel.invokeMethod('getRuntimeStatus');
    return Map<String, dynamic>.from(result);
  }

  // ── Device Info ──

  static Future<Map<String, dynamic>> getDeviceInfo() async {
    final result = await _channel.invokeMethod('getDeviceInfo');
    return Map<String, dynamic>.from(result);
  }

  // ── Bootstrap ──

  static Future<void> setupBootstrap() async {
    await _channel.invokeMethod('setupBootstrap');
  }

  // ── Native Desktop Environment Installation (non-root fallback) ──

  static Future<bool> installDesktopNative() async {
    final result = await _channel.invokeMethod('installDesktopNative');
    return result as bool? ?? false;
  }

  // ── Root / chroot support ──

  static Future<bool> checkRoot() async {
    final result = await _channel.invokeMethod('checkRoot');
    return result as bool? ?? false;
  }

  static Future<void> resetRootCache() async {
    await _channel.invokeMethod('resetRootCache');
  }

  // ── Rootfs Management (chroot mode) ──

  static Future<void> downloadRootfs(String distro) async {
    await _channel.invokeMethod('downloadRootfs', {'distro': distro});
  }

  static Future<void> extractRootfs() async {
    await _channel.invokeMethod('extractRootfs');
  }

  static Future<void> installDesktopEnvironment(String de, {String type = 'minimal'}) async {
    await _channel.invokeMethod('installDesktopEnvironment', {
      'de': de,
      'type': type,
    });
  }

  // ── Linux Session ──

  static Future<void> startLinux({
    String de = 'xfce4',
    String mode = 'x11',
    int width = 1920,
    int height = 1080,
  }) async {
    await _channel.invokeMethod('startLinux', {
      'de': de,
      'mode': mode,
      'width': width,
      'height': height,
    });
  }

  static Future<void> stopLinux() async {
    await _channel.invokeMethod('stopLinux');
  }

  static Future<void> launchDesktopActivity() async {
    await _channel.invokeMethod('launchDesktopActivity');
  }

  static Future<String> executeCommand(String command) async {
    final result = await _channel.invokeMethod('executeCommand', {
      'command': command,
    });
    return result as String? ?? '';
  }

  static Future<void> interruptCommand() async {
    await _channel.invokeMethod('interruptCommand');
  }

  // ── Battery Optimization ──

  static Future<void> requestBatteryOptimization() async {
    await _channel.invokeMethod('requestBatteryOptimization');
  }

  static Future<bool> isBatteryOptimized() async {
    final result = await _channel.invokeMethod('isBatteryOptimized');
    return result as bool? ?? true;
  }
}

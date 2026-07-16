import 'package:flutter/material.dart';
import 'package:droiddesk/services/platform_bridge.dart';

/// Central state management for the entire DroidDesk app.
class AppState extends ChangeNotifier {
  // ── Setup State ──
  bool _isBootstrapped = false;
  bool _isRunning = false;
  bool _hasRoot = false;
  String _installedDistro = '';
  String _installedDE = '';
  String _selectedDistro = 'ubuntu';
  String _selectedDE = 'xfce4';
  String _installType = 'minimal'; // 'minimal' or 'full'
  int _setupStep = 0; // 0=welcome, 1=distro, 2=de, 3=install, 4=done

  // ── Download/Install Progress ──
  double _downloadProgress = 0.0;
  String _downloadStatus = '';
  double _extractProgress = 0.0;
  String _extractStatus = '';
  bool _isDownloading = false;
  bool _isExtracting = false;
  bool _isInstallingDE = false;
  String? _statusMessage;

  // Terminal history
  final List<String> _terminalOutput = ['DroidDesk Linux Terminal\nType commands below.\n'];
  List<String> get terminalOutput => _terminalOutput;

  // ── Device Info ──
  Map<String, dynamic> _deviceInfo = {};

  // ── Error State ──
  String? _errorMessage;

  // ── Getters ──
  bool get isBootstrapped => _isBootstrapped;
  bool get isRunning => _isRunning;
  bool get hasRoot => _hasRoot;
  String get installedDistro => _installedDistro;
  String get installedDE => _installedDE;
  String get selectedDistro => _selectedDistro;
  String get selectedDE => _selectedDE;
  String get installType => _installType;
  int get setupStep => _setupStep;
  double get downloadProgress => _downloadProgress;
  String get downloadStatus => _downloadStatus;
  double get extractProgress => _extractProgress;
  String get extractStatus => _extractStatus;
  String? get statusMessage => _statusMessage;
  bool get isDownloading => _isDownloading;
  bool get isExtracting => _isExtracting;
  bool get isInstallingDE => _isInstallingDE;
  Map<String, dynamic> get deviceInfo => _deviceInfo;
  String? get errorMessage => _errorMessage;

  bool get isSetupComplete => _isBootstrapped && _installedDE.isNotEmpty;
  bool get isDEInstalled => _installedDE.isNotEmpty;

  String get gpuType {
    final vendor = _deviceInfo['gpuVendor']?.toString() ?? '';
    if (vendor.contains('adreno')) return 'Adreno (Snapdragon)';
    if (vendor.contains('mali')) return 'Mali (MediaTek/Exynos)';
    if (vendor.contains('powervr')) return 'PowerVR';
    return 'Unknown GPU';
  }

  // ── Initialization ──

  Future<void> initialize() async {
    // Set up progress callbacks
    DroidDeskPlatform.onDownloadProgress = (progress, status) {
      _downloadProgress = progress;
      _downloadStatus = status;
      if (progress < 0) {
        _isDownloading = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isDownloading = false;
      }
      notifyListeners();
    };

    DroidDeskPlatform.onExtractProgress = (progress, status) {
      _extractProgress = progress;
      _extractStatus = status;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onInstallProgress = (progress, status) {
      _extractProgress = progress; // reusing extract progress state for UI
      _extractStatus = status;
      _statusMessage = status;
      _isInstallingDE = progress >= 0 && progress < 1.0;
      if (progress < 0) {
        _isExtracting = false;
        _isInstallingDE = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        _isInstallingDE = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onTerminalOutput = (text) {
      if (_terminalOutput.isEmpty) _terminalOutput.add('');

      final cleanedText = text.replaceAll(RegExp(r'.*\r(?!\n)'), '');
      final lines = cleanedText.split('\n');

      for (int i = 0; i < lines.length; i++) {
        if (i == 0) {
          _terminalOutput[_terminalOutput.length - 1] += lines[i];
        } else {
          _terminalOutput.add(lines[i]);
        }
      }
      notifyListeners();
    };

    await refreshStatus();
    await loadDeviceInfo();
  }

  Future<void> refreshStatus() async {
    try {
      final status = await DroidDeskPlatform.getRuntimeStatus();
      _isBootstrapped = status['isBootstrapped'] == true;
      _isRunning = status['isRunning'] == true;
      _hasRoot = status['hasRoot'] == true;
      _installedDistro = status['distro']?.toString() ?? '';
      _installedDE = status['installedDE']?.toString() ?? '';

      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to get runtime status: $e';
      notifyListeners();
    }
  }

  Future<void> loadDeviceInfo() async {
    try {
      _deviceInfo = await DroidDeskPlatform.getDeviceInfo();
      notifyListeners();
    } catch (e) {
      // Non-fatal — continue without device info
    }
  }

  // ── Setup Flow ──

  void setSelectedDistro(String distro) {
    _selectedDistro = distro;
    notifyListeners();
  }

  void setSelectedDE(String de) {
    _selectedDE = de;
    notifyListeners();
  }

  void setInstallType(String type) {
    _installType = type;
    notifyListeners();
  }

  void setSetupStep(int step) {
    _setupStep = step;
    _errorMessage = null;
    notifyListeners();
  }

  /// Main setup entry point. Chooses chroot (rooted) or native Termux path.
  Future<void> runSetup() async {
    try {
      _errorMessage = null;
      _setupStep = 3;
      notifyListeners();

      // Detect root and choose path
      _hasRoot = await DroidDeskPlatform.checkRoot();
      notifyListeners();

      if (_hasRoot) {
        await _runChrootSetup();
      } else {
        await _runNativeSetup();
      }

      _setupStep = 4;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Setup failed: $e';
      _isDownloading = false;
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
    }
  }

  Future<void> _runNativeSetup() async {
    _statusMessage = 'Extracting native Termux bootstrap...';
    notifyListeners();
    await DroidDeskPlatform.setupBootstrap();

    _isExtracting = true;
    _extractProgress = 0.0;
    _statusMessage = 'Installing Desktop Environment via native pkg...';
    notifyListeners();
    await DroidDeskPlatform.installDesktopNative();
    _isExtracting = false;

    await refreshStatus();
  }

  Future<void> _runChrootSetup() async {
    _statusMessage = 'Downloading Ubuntu rootfs...';
    _isDownloading = true;
    _downloadProgress = 0.0;
    notifyListeners();
    await DroidDeskPlatform.downloadRootfs(_selectedDistro);

    _isDownloading = false;
    _isExtracting = true;
    _extractProgress = 0.0;
    _statusMessage = 'Extracting rootfs...';
    notifyListeners();
    await DroidDeskPlatform.extractRootfs();

    _statusMessage = 'Installing desktop environment (this may take a while)...';
    _isInstallingDE = true;
    notifyListeners();
    await DroidDeskPlatform.installDesktopEnvironment(_selectedDE, type: _installType);
    _isExtracting = false;
    _isInstallingDE = false;

    await refreshStatus();
  }

  Future<void> runExtraction() async {
    // Handled inside runSetup for chroot mode.
    // Kept for API compatibility.
    _extractProgress = 1.0;
    _extractStatus = 'Extraction handled by setup flow';
    notifyListeners();
  }

  Future<void> installDesktopEnvironment() async {
    try {
      _isExtracting = true;
      _extractProgress = 0.0;
      _statusMessage = 'Installing Desktop Environment...';
      _errorMessage = null;
      notifyListeners();

      if (_hasRoot) {
        await DroidDeskPlatform.installDesktopEnvironment(_selectedDE, type: _installType);
      } else {
        await DroidDeskPlatform.installDesktopNative();
      }

      _isExtracting = false;
      _isInstallingDE = false;
      await refreshStatus();
    } catch (e) {
      _errorMessage = 'Installation failed: $e';
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
    }
  }

  // ── Session Control ──

  Future<void> startLinux({String mode = 'x11', int width = 1920, int height = 1080}) async {
    try {
      _errorMessage = null;
      await DroidDeskPlatform.startLinux(de: _selectedDE, mode: mode, width: width, height: height);
      _isRunning = true;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to start: $e';
      notifyListeners();
    }
  }

  Future<void> launchDesktopActivity() async {
    try {
      await DroidDeskPlatform.launchDesktopActivity();
    } catch (e) {
      _errorMessage = 'Failed to launch desktop activity: $e';
      notifyListeners();
    }
  }

  Future<void> stopLinux() async {
    try {
      await DroidDeskPlatform.stopLinux();
      _isRunning = false;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to stop: $e';
      notifyListeners();
    }
  }

  Future<String> executeCommand(String command) async {
    try {
      _terminalOutput.add('\$ $command\n');
      notifyListeners();
      return await DroidDeskPlatform.executeCommand(command);
    } catch (e) {
      return "Error executing command: $e";
    }
  }

  void appendTerminalOutput(String output) {
    if (_terminalOutput.isEmpty) _terminalOutput.add('');
    _terminalOutput[_terminalOutput.length - 1] += output;
    notifyListeners();
  }

  void clearTerminal() {
    _terminalOutput.clear();
    _terminalOutput.add('DroidDesk Linux Terminal\nType commands below.\n');
    notifyListeners();
  }

  Future<void> interruptCommand() async {
    try {
      await DroidDeskPlatform.interruptCommand();
    } catch (e) {
      debugPrint("Error interrupting command: $e");
    }
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }
}

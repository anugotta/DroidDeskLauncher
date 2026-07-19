import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:servaldesk/theme/droid_theme.dart';
import 'package:servaldesk/state/app_state.dart';

class DEInstallScreen extends StatefulWidget {
  const DEInstallScreen({super.key});

  @override
  State<DEInstallScreen> createState() => _DEInstallScreenState();
}

class _DEInstallScreenState extends State<DEInstallScreen> {
  bool _started = false;
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addPostFrameCallback((_) {
      _startInstall();
    });
  }

  void _startInstall() {
    if (_started) return;
    _started = true;
    context.read<AppState>().installDesktopEnvironment();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _launchUrl() async {
    final url = Uri.parse('https://www.youtube.com/orailnoor');
    if (await canLaunchUrl(url)) {
      await launchUrl(url, mode: LaunchMode.externalApplication);
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();

    // Auto-scroll terminal
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 100),
          curve: Curves.easeOut,
        );
      }
    });

    final isDone = state.isDEInstalled;
    final hasError = state.errorMessage != null;

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // Background Gradient
          Positioned.fill(
            child: Container(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [Color(0xFF0F172A), Colors.black, Color(0xFF020617)],
                ),
              ),
            ),
          ),

          SafeArea(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // ── Header ──
                  Text(
                    isDone
                        ? 'Installation Complete'
                        : hasError
                        ? 'Installation Failed'
                        : 'Configuring\nLinux Workstation',
                    style: DroidTheme.headingXl.copyWith(color: Colors.white),
                    textAlign: TextAlign.center,
                  ).animate().fadeIn(duration: 500.ms).slideY(begin: -0.2),

                  const SizedBox(height: 8),
                  Text(
                    isDone
                        ? 'Your Linux environment is ready.'
                        : 'Downloading and configuring system packages.',
                    style: DroidTheme.bodyMd.copyWith(color: Colors.white60),
                    textAlign: TextAlign.center,
                  ).animate().fadeIn(delay: 200.ms),

                  const Spacer(flex: 1),

                  // ── Terminal Output Window ──
                  if (!isDone)
                    Expanded(
                      flex: 3,
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.5),
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(
                            color: DroidTheme.surfaceBorder.withValues(
                              alpha: 0.3,
                            ),
                          ),
                        ),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(12),
                          child: ListView.builder(
                            controller: _scrollController,
                            padding: const EdgeInsets.all(12),
                            itemCount: state.terminalOutput.length,
                            itemBuilder: (context, index) {
                              return Padding(
                                padding: const EdgeInsets.only(bottom: 2),
                                child: Text(
                                  state.terminalOutput[index],
                                  style: const TextStyle(
                                    fontFamily: 'monospace',
                                    fontSize: 10,
                                    color: Colors.greenAccent,
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                      ),
                    ).animate().fadeIn(delay: 400.ms),

                  const SizedBox(height: 24),

                  // ── Progress Bar ──
                  if (!isDone && !hasError)
                    Column(
                      children: [
                        ClipRRect(
                          borderRadius: BorderRadius.circular(8),
                          child: LinearProgressIndicator(
                            value: state.extractProgress.clamp(0.0, 1.0),
                            backgroundColor: DroidTheme.surfaceLight.withValues(
                              alpha: 0.2,
                            ),
                            valueColor: const AlwaysStoppedAnimation<Color>(
                              DroidTheme.primary,
                            ),
                            minHeight: 8,
                          ),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          state.extractStatus.isNotEmpty
                              ? state.extractStatus
                              : 'Extracting packages...',
                          style: DroidTheme.monoSm.copyWith(
                            color: Colors.white70,
                          ),
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),

                  // ── Completion / Error Buttons ──
                  if (isDone)
                    ElevatedButton(
                      onPressed: () {
                        state.refreshStatus();
                        Navigator.pop(context);
                      },
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        backgroundColor: DroidTheme.primary,
                      ),
                      child: Text(
                        'Return to Home',
                        style: DroidTheme.headingSm.copyWith(
                          color: Colors.white,
                        ),
                      ),
                    ).animate().scale(
                      curve: Curves.elasticOut,
                      duration: 800.ms,
                    ),

                  if (hasError)
                    ElevatedButton(
                      onPressed: () {
                        Navigator.pop(context);
                      },
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        backgroundColor: DroidTheme.error,
                      ),
                      child: Text(
                        'Go Back',
                        style: DroidTheme.headingSm.copyWith(
                          color: Colors.white,
                        ),
                      ),
                    ),

                  const Spacer(flex: 1),

                  // ── Footer ──
                  if (!isDone)
                    Center(
                      child: OutlinedButton.icon(
                        onPressed: _launchUrl,
                        icon: const Icon(
                          Icons.code_rounded,
                          size: 18,
                          color: DroidTheme.primary,
                        ),
                        label: Text(
                          'Support Open Source',
                          style: DroidTheme.bodySm.copyWith(
                            color: Colors.white70,
                          ),
                        ),
                        style: OutlinedButton.styleFrom(
                          side: BorderSide(
                            color: DroidTheme.primary.withValues(alpha: 0.3),
                          ),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(30),
                          ),
                          padding: const EdgeInsets.symmetric(
                            horizontal: 24,
                            vertical: 12,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

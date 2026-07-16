import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/screens/setup/de_picker.dart';

class InstallTypePickerScreen extends StatelessWidget {
  const InstallTypePickerScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SizedBox(height: 16),
                
                Text('Installation Type', style: DroidTheme.headingXl)
                    .animate()
                    .fadeIn(duration: 400.ms)
                    .slideX(begin: -0.1, duration: 400.ms),

                const SizedBox(height: 8),
                Text(
                  'Choose how much space to use and which apps to include out of the box.',
                  style: DroidTheme.bodyMd,
                )
                    .animate()
                    .fadeIn(delay: 100.ms, duration: 400.ms),

                const SizedBox(height: 24),

                // ── Minimal Option ──
                _buildOptionCard(
                  title: 'Minimal',
                  description: 'Basic desktop experience. Includes XFCE4, Terminal, File Manager, and core utilities. Perfect for limited storage.',
                  size: '~1.2 GB',
                  icon: Icons.speed_rounded,
                  color: DroidTheme.secondary,
                  isSelected: state.installType == 'minimal',
                  onTap: () => state.setInstallType('minimal'),
                ).animate().fadeIn(delay: 200.ms).slideY(begin: 0.1),

                const SizedBox(height: 16),

                // ── Full Option ──
                _buildOptionCard(
                  title: 'Full Workstation',
                  description: 'Complete suite. Includes LibreOffice, GIMP, VS Code, Node.js, Git, multimedia tools, and more.',
                  size: '~2.5 GB',
                  icon: Icons.work_rounded,
                  color: DroidTheme.primary,
                  isSelected: state.installType == 'full',
                  onTap: () => state.setInstallType('full'),
                ).animate().fadeIn(delay: 300.ms).slideY(begin: 0.1),

                const Spacer(),

                // ── Navigation ──
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Row(
                    children: [
                      OutlinedButton(
                        onPressed: () => Navigator.pop(context),
                        child: const Text('Back'),
                      ),
                      const Spacer(),
                      ElevatedButton(
                        onPressed: () {
                          Navigator.of(context).push(
                            PageRouteBuilder(
                              pageBuilder: (context, animation, secondaryAnimation) => const DEPickerScreen(),
                              transitionsBuilder: (context, animation, secondaryAnimation, child) {
                                return FadeTransition(
                                  opacity: animation,
                                  child: child,
                                );
                              },
                              transitionDuration: const Duration(milliseconds: 300),
                            ),
                          );
                        },
                        child: const Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text('Next'),
                            SizedBox(width: 4),
                            Icon(Icons.arrow_forward_rounded, size: 18),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildOptionCard({
    required String title,
    required String description,
    required String size,
    required IconData icon,
    required Color color,
    required bool isSelected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: isSelected ? DroidTheme.surfaceLight : DroidTheme.cardBg,
          borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
          border: Border.all(
            color: isSelected ? color.withValues(alpha: 0.5) : DroidTheme.surfaceBorder,
            width: isSelected ? 1.5 : 1,
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 44,
              height: 44,
              margin: const EdgeInsets.only(top: 4),
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(icon, color: color, size: 22),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(title, style: DroidTheme.headingSm.copyWith(fontSize: 16)),
                      const Spacer(),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: DroidTheme.textDim.withValues(alpha: 0.15),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(size, style: DroidTheme.monoSm.copyWith(fontSize: 11)),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(description, style: DroidTheme.bodySm.copyWith(height: 1.3)),
                ],
              ),
            ),
            const SizedBox(width: 10),
            AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              width: 20,
              height: 20,
              margin: const EdgeInsets.only(top: 14),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: isSelected ? color : Colors.transparent,
                border: Border.all(
                  color: isSelected ? color : DroidTheme.textDim,
                  width: 2,
                ),
              ),
              child: isSelected ? const Icon(Icons.check, size: 12, color: Colors.white) : null,
            ),
          ],
        ),
      ),
    );
  }
}

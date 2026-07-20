import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:servaldesk/theme/droid_theme.dart';
import 'package:servaldesk/state/app_state.dart';
import 'package:servaldesk/services/platform_bridge.dart';
import 'package:servaldesk/screens/welcome_screen.dart';
import 'package:servaldesk/screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  ServalDeskPlatform.init();

  runApp(
    ChangeNotifierProvider(
      create: (_) => AppState(),
      child: const ServalDeskApp(),
    ),
  );
}

class ServalDeskApp extends StatefulWidget {
  const ServalDeskApp({super.key});

  @override
  State<ServalDeskApp> createState() => _ServalDeskAppState();
}

class _ServalDeskAppState extends State<ServalDeskApp> {
  @override
  void initState() {
    super.initState();
    // Initialize platform bridge and load state
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AppState>().initialize();
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ServalDesk',
      debugShowCheckedModeBanner: false,
      theme: DroidTheme.lightThemeData,
      darkTheme: DroidTheme.themeData,
      themeMode: ThemeMode.system,
      home: Consumer<AppState>(
        builder: (context, state, _) {
          // Route to setup wizard or home based on bootstrap state
          if (state.isSetupComplete) {
            return const HomeScreen();
          }
          return const WelcomeScreen();
        },
      ),
    );
  }
}

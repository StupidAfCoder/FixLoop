import 'package:flutter/material.dart';

void main() {
  runApp(const FixLoopApp());
}

class FixLoopApp extends StatelessWidget {
  const FixLoopApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Fix Loop',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF176B65)),
        useMaterial3: true,
        inputDecorationTheme: const InputDecorationTheme(
          border: OutlineInputBorder(),
        ),
      ),
      home: const LoginPlaceholderPage(),
    );
  }
}

/// A visual starting point only; no credentials are collected or sent.
class LoginPlaceholderPage extends StatelessWidget {
  const LoginPlaceholderPage({super.key});

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;

    return Scaffold(
      backgroundColor: colors.surface,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 36),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 460),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: CircleAvatar(
                      radius: 26,
                      backgroundColor: colors.primaryContainer,
                      child: Icon(Icons.loop_rounded, color: colors.onPrimaryContainer),
                    ),
                  ),
                  const SizedBox(height: 28),
                  Text('Fix Loop', style: text.headlineLarge),
                  const SizedBox(height: 8),
                  Text(
                    'Report it. Track it. Close the loop.',
                    style: text.titleMedium?.copyWith(color: colors.onSurfaceVariant),
                  ),
                  const SizedBox(height: 32),
                  Text('Welcome', style: text.headlineSmall),
                  const SizedBox(height: 8),
                  Text(
                    'A calmer way to report campus issues and follow their progress.',
                    style: text.bodyLarge?.copyWith(color: colors.onSurfaceVariant),
                  ),
                  const SizedBox(height: 24),
                  const TextField(
                    enabled: false,
                    decoration: InputDecoration(labelText: 'College email'),
                  ),
                  const SizedBox(height: 16),
                  const TextField(
                    enabled: false,
                    obscureText: true,
                    decoration: InputDecoration(labelText: 'Password'),
                  ),
                  const SizedBox(height: 20),
                  const FilledButton(
                    onPressed: null,
                    child: Text('Sign in'),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'Early preview: sign-in and complaint reporting are not connected yet.',
                    style: text.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
                  ),
                  const SizedBox(height: 32),
                  const Divider(),
                  const SizedBox(height: 12),
                  Text(
                    'Privacy in the design',
                    style: text.titleSmall,
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Your identity is intended to be hidden from handlers by default, '
                    'but the system will retain it for accountability.',
                    style: text.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

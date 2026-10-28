import 'package:fixloop_mobile/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('starter screen makes it clear that login is not available', (
    tester,
  ) async {
    await tester.pumpWidget(const FixLoopApp());

    expect(find.text('Fix Loop'), findsOneWidget);
    expect(find.text('Early preview: sign-in and complaint reporting are not connected yet.'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNull);
    for (final field in tester.widgetList<TextField>(find.byType(TextField))) {
      expect(field.enabled, isFalse);
    }
  });
}

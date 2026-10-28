# Flutter starter

This is a **UI-only placeholder**, not a working login or a demo with real data.
The fields and sign-in button are deliberately disabled. There is no API client
or credential storage yet.

The target SDK is Flutter **3.41.9** / Dart **3.11.5**. With that SDK installed,
from this folder run:

```sh
flutter create --no-pub --platforms=android,web --project-name fixloop_mobile --org com.fixloop .
flutter pub get
flutter analyze
flutter test
flutter run -d chrome       # or select your Android device with flutter devices
```

The Android and web host projects are intentionally not committed yet: the
first command generates the standard host files using your installed Flutter
version. It repairs missing files and, without `--overwrite`, does not replace
our existing Dart source, pubspec or test. Review and commit the generated host
files once the team agrees on the Android application ID. Do not commit
`local.properties`, signing keys, or credentials.

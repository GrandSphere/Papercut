# Papercut

Papercut is a PDF viewer for Android.

## License

Papercut is licensed under the GNU General Public License v3.0 (GPL-3.0).

MuPDF is licensed under the AGPL.

Copyright © 2025 Grand Sphere Studios

## Features

- PDF viewing: Open PDF and other documents.
- Folios: Keep often used documents in a folio.
- Bookmarks: Mark pages.
- Combine and rearrange: Merge reorder pages.
- Share: Choose pages to share.
- Themes: Custom document colouring.

## Technical Information

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Architecture**: MVVM
- **Database**: Room
- **PDF engine**: MuPDF 1.28.0
- **Minimum SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 15 (API 35)
- **Build**: Gradle 8.9, JDK 17

## Getting Started

### Prerequisites

- JDK 17
- Android SDK command-line tools (`cmdline-tools`, `platform-tools`, `emulator`, `platforms;android-35`, `build-tools`)
- `ANDROID_HOME` set, or `sdk.dir` in `local.properties`
- An arm64-v8a device or emulator

### Build

1. Clone the repository:

   ```bash
   git clone https://github.com/GrandSphere/Papercut.git
   cd Papercut
   ```

2. Build the debug APK (use `gradlew.bat` on Windows):

   ```bash
   ./gradlew assembleDebug
   ```

   The APK is written to `app/build/outputs/apk/debug/Papercut-(version)-debug.apk`.

   Release builds (`./gradlew assembleRelease`) require a `keystore.properties` file in the repository root.

### Debug on emulator

1. Install SDK packages:

   ```bash
   sdkmanager "platform-tools" "emulator" "platforms;android-35" "build-tools;35.0.0" "system-images;android-35;google_apis;arm64-v8a"
   ```

2. Create an AVD named `Pixel_10`. If the `pixel_10` hardware profile is missing, use another Pixel profile and keep the AVD name `Pixel_10`:

   ```bash
   avdmanager create avd -n Pixel_10 -d pixel_10 -k "system-images;android-35;google_apis;arm64-v8a"
   ```

3. Start the emulator:

   ```bash
   emulator -avd Pixel_10
   ```

4. Install the debug build:

   ```bash
   ./gradlew installDebug
   ```

## Known Issues

Report issues at https://github.com/GrandSphere/Papercut/issues.

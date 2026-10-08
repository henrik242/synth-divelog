# Synth Divelog

A dive log for Android, desktop and iOS that downloads straight from your dive
computer.

Licence: [MPL-2.0](LICENSE)

## Features

- **Download from dive computers**: Shearwater Predator and Petrel 1 over
  Bluetooth, Suunto Zoop/Vyper and HelO2/Vyper2 over the USB cable. New dives
  only, or a chosen number.
- **File import/export**: Subsurface XML, UDDF and MacDive XML. Duplicates are
  skipped, and the same dive from two computers is merged.
- **Cloud import/export**: your Subsurface cloud logbook (Android and desktop).
- **Browse and edit**: dive profiles, search and sort, merge and split dives,
  sites on a map, buddies, tags and dive computers. Metric or imperial units.
- **Tools**: gas blender for nitrox and trimix, tank buoyancy, and MOD/END.

## Development

Kotlin Multiplatform with Compose Multiplatform. Logic and UI are shared; each
platform adds a thin entry point.

| Module | Role |
|---|---|
| `:core:model` | Domain types. |
| `:core:db` | Database and repositories. |
| `:core:divecomputer` | Dive-computer protocols and parsers. |
| `:core:transport` | Bluetooth and serial transports per platform. |
| `:core:formats` | Logbook file formats. |
| `:core:gas` | The calculators behind the Tools tab. |
| `:ui` | Shared screens. |
| `:app:android`, `:app:desktop`, `iosApp/` | Platform entry points. |

Protocol notes, written against real hardware, are in
[`docs/protocol/`](docs/protocol/README.md).

```sh
./gradlew jvmTest                     # tests (what CI runs)
./gradlew :app:android:assembleDebug  # Android debug APK
./gradlew :app:desktop:run            # desktop app (needs JDK 25)

# iOS simulator: build the framework, then the Xcode app
./gradlew :ui:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

Needs an Android SDK (`sdk.dir` in `local.properties` or `ANDROID_HOME`). JDK 17
builds everything except the desktop app, which needs JDK 25.

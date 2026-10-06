# Synth Divelog

A dive log built on Kotlin Multiplatform with its own pure-Kotlin dive-computer
stack. One codebase runs on Android, desktop (JVM) and iOS.

- Application id: `no.synth.divelog`
- Licence: [MPL-2.0](LICENSE)

## Features

- **Download from dive computers** over the shared "Add dives" flow:
  - Shearwater **Predator** and **Petrel 1** over classic Bluetooth (verified on the real devices).
  - Suunto **Zoop/Vyper** and **HelO2/D9** over the USB cable (HelO2 verified on hardware).
  - Incremental "new dives only" or a chosen amount; long downloads survive backgrounding via an Android foreground service.
- **File import/export**: Subsurface XML, UDDF and MacDive XML. Import shows progress, skips duplicates, and auto-merges copies of the same dive (e.g. one logged on two computers), keeping the richer site/buddies/notes/tags.
- **Subsurface cloud** sync (git over HTTPS), import and export.
- **Browse and edit**: profile graph, search/sort, dive detail with prev/next, per-dive merge/split/delete, and autosaving editors for dives, sites, buddies and tags.
- **Sites** with an interactive map (MapLibre) to pick coordinates and an overview map; **dive computers** page with per-computer merge; **tags**; units toggle; a top-bar breadcrumb.

Storage uses fixed integer units (depth mm, pressure mbar, temperature mK,
duration s, gas permille); the raw download blob is kept so a record can be
re-parsed after a parser fix.

## Modules

| Module | Role |
|---|---|
| `:core:model` | Domain types and fixed-unit values. No other-module deps. |
| `:core:db` | SQLDelight schema, queries, repositories. |
| `:core:divecomputer` | Transport interface, protocols, parsers. Pure common Kotlin. |
| `:core:transport` | `expect`/`actual` transports: jSerialComm (jvm), USB-serial + classic-BT RFCOMM (android). |
| `:core:formats` | File formats over a neutral `DiveLog`. |
| `:ui` | Shared Compose Multiplatform screens; logbook I/O, cloud sync, download orchestration. |
| `:app:android` | Android entry point. |
| `:app:desktop` | Desktop (JVM) Compose entry point. |
| `iosApp/` | iOS SwiftUI app hosting the shared Compose UI (`:ui` iOS framework). |

Shared modules declare a `jvm()` target (so common tests run on the JVM) and an
Android target. Android/JVM APIs stay out of `:core:model`, `:core:divecomputer`,
`:core:formats` and `:ui` commonMain.

## Protocol documentation

Download protocols, written against real hardware, live in
[`docs/protocol/`](docs/protocol/README.md): a shared Shearwater
[transport layer](docs/protocol/transport.md) plus one doc per device family
([Predator](docs/protocol/shearwater-predator.md),
[Petrel 1](docs/protocol/shearwater-petrel1.md),
[Suunto serial](docs/protocol/suunto-serial.md),
[Zoop/Vyper](docs/protocol/suunto-zoop.md),
[HelO2/D9](docs/protocol/suunto-helo2.md)).

## Toolchain

Kotlin 2.4.20, Compose Multiplatform 1.12.1, SQLDelight 2.4.0,
AGP 9.4.1 / Gradle 9.8.0. Android compileSdk 37, minSdk 33.

JDK 17+ builds everything except the desktop app: its MapLibre map renders
through the Java FFM API, so the **desktop build and run need JDK 25** (plus the
`--enable-native-access=ALL-UNNAMED` flag, already wired).

## Build and test

```sh
# Common tests on the JVM (what CI runs)
./gradlew jvmTest

# Android debug APK
./gradlew :app:android:assembleDebug

# Desktop app (JVM) - needs JDK 25
./gradlew :app:desktop:run

# iOS app (simulator): build the framework, then the Xcode app
./gradlew :ui:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

Requires a local Android SDK; point `local.properties` (`sdk.dir=...`) or
`ANDROID_HOME` at it.

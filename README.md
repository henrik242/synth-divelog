# Synth Divelog

A simple dive log built on Kotlin Multiplatform with its own pure-Kotlin dive
computer stack. Android first; desktop (JVM) and iOS follow from the same
codebase.

- Application id: `no.synth.divelog`
- Licence: [MPL-2.0](LICENSE)

## Status

Version 1 (Android) complete: M0-M4, plus a statistics screen.

- M0 skeleton, M1 model + SQLDelight storage, M2 Shearwater download, M3 Android
  UI, M4 file import/export.
- Downloads dives from a Shearwater **Predator and Petrel 1** over Bluetooth (both
  verified against the real devices), imports them (skipping duplicates, with a
  merge-review step for a dive recorded by two computers), and lets you browse and
  edit dives, sites and buddies with a profile graph, search/sort, units toggle,
  editable dive number and per-dive merge/split/delete. Long downloads survive
  backgrounding via a foreground service, and you can pull just the latest N dives.

Storage uses fixed integer units (depth mm, pressure mbar, temperature mK,
duration s, gas permille); the raw download blob is kept so a record can be
re-parsed after a parser fix.

M5 desktop (JVM) and M6 iOS: first cuts run. Both reuse the shared UI and core -
browse/edit dives, sites and buddies over a native database (desktop also does
import/export/re-parse). Bluetooth download stays Android-only until a serial/USB
transport lands.

Later work (not started; some need a decision first): M7 UDCF / divelogs.de DLD,
M8 cloud sync, M9 Suunto download over USB cable (Zoop and HelO2; see
[docs/protocol](docs/protocol/suunto-serial.md)). iOS file import/export via
document pickers is a follow-up to M6.

## Modules

| Module | Role |
|---|---|
| `:core:model` | Domain types and fixed-unit values. No other-module deps. |
| `:core:db` | SQLDelight schema, queries, repositories (from M1). |
| `:core:divecomputer` | Transport interface, protocols, parsers. Pure common Kotlin. |
| `:core:transport` | `expect`/`actual` transports (Android RFCOMM first). |
| `:ui` | Shared Compose Multiplatform screens and view models. |
| `:app:android` | Android entry point. |
| `:app:desktop` | Desktop (JVM) Compose entry point. |
| `iosApp/` | iOS SwiftUI app hosting the shared Compose UI (`:ui` iOS framework). |

Shared modules declare both a `jvm()` target (so common tests run on the JVM)
and an Android target. Android APIs stay out of `:core:model`,
`:core:divecomputer` and `:ui`.

## Protocol documentation

Download protocols, written against real hardware, live in
[`docs/protocol/`](docs/protocol/README.md): a shared
[transport layer](docs/protocol/transport.md) (link, SLIP framing, command set)
plus one doc per device type
([Predator](docs/protocol/shearwater-predator.md),
[Petrel 1](docs/protocol/shearwater-petrel1.md)).

## Toolchain

Kotlin 2.4.20, Compose Multiplatform 1.12.1, SQLDelight 2.3.2,
AGP 9.4.0 / Gradle 9.8.0, JDK 17+. Android compileSdk 36, minSdk 26.

## Build and test

```sh
# Common tests on the JVM (what CI runs)
./gradlew jvmTest

# Android debug APK
./gradlew :app:android:assembleDebug

# Desktop app (JVM)
./gradlew :app:desktop:run

# iOS app (simulator): build the framework, then the Xcode app
./gradlew :ui:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

Requires a local Android SDK; point `local.properties` (`sdk.dir=...`) or
`ANDROID_HOME` at it.

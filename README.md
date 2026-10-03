# Synth Divelog

A simple dive log built on Kotlin Multiplatform with its own pure-Kotlin dive
computer stack. Android first; desktop (JVM) and iOS follow from the same
codebase.

- Application id: `no.synth.divelog`
- Licence: [MPL-2.0](LICENSE)

## Status

Version 1 (Android) complete: M0-M3, plus a statistics screen.

- M0 skeleton, M1 model + SQLDelight storage, M2 Shearwater Predator download
  (verified against a real device), M3 Android UI.
- The app downloads dives from a Shearwater Predator over Bluetooth, imports
  them (skipping duplicates, with a merge-review step for a dive recorded by two
  computers), and lets you browse and edit dives, sites and buddies with a
  profile graph, search/sort, units toggle and per-dive merge/split/delete.

Storage uses fixed integer units (depth mm, pressure mbar, temperature mK,
duration s, gas permille); the raw download blob is kept so a record can be
re-parsed after a parser fix.

Later work (not started; some need a decision first): M4 Subsurface XML / UDDF
import-export, M5 desktop, M6 iOS, M7 UDCF / divelogs.de DLD.

## Modules

| Module | Role |
|---|---|
| `:core:model` | Domain types and fixed-unit values. No other-module deps. |
| `:core:db` | SQLDelight schema, queries, repositories (from M1). |
| `:core:divecomputer` | Transport interface, protocols, parsers. Pure common Kotlin. |
| `:core:transport` | `expect`/`actual` transports (Android RFCOMM first). |
| `:ui` | Shared Compose Multiplatform screens and view models. |
| `:app:android` | Android entry point. |

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
```

Requires a local Android SDK; point `local.properties` (`sdk.dir=...`) or
`ANDROID_HOME` at it.

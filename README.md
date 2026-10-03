# Synth Divelog

A simple dive log built on Kotlin Multiplatform with its own pure-Kotlin dive
computer stack. Android first; desktop (JVM) and iOS follow from the same
codebase.

- Application id: `no.synth.divelog`
- Licence: [MPL-2.0](LICENSE)

## Status

M1 (model and storage) in progress on top of M0. The Android app launches to a
placeholder screen; the domain model, SQLDelight schema, repositories and the
import/duplicate/merge/split logic are in place and covered by JVM tests.

Storage uses fixed integer units (depth mm, pressure mbar, temperature mK,
duration s, gas permille); the raw download blob is kept so a record can be
re-parsed after a parser fix. Duplicate detection is per device + fingerprint;
an overlapping dive from another computer can be merged into one dive with two
records; dives can be split and manually merged.

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

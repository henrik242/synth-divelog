# Synth Divelog

A simple dive log built on Kotlin Multiplatform with its own pure-Kotlin dive
computer stack. Android first; desktop (JVM) and iOS follow from the same
codebase.

- Application id: `no.synth.divelog`
- Licence: [MPL-2.0](LICENSE)

## Status

M0 (skeleton). The Android app launches to a placeholder screen and common
tests run on the JVM without an emulator.

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

# Synth Divelog - working notes

Kotlin Multiplatform dive log (`no.synth.divelog`) with its own pure-Kotlin
dive-computer stack, for Android, desktop (JVM) and iOS.

## Architecture

Logic and UI live in `commonMain`; platform code is `expect`/`actual` or a thin
per-target entry point.

| Module | Role |
|---|---|
| `:core:model` | Domain types and fixed-unit values. No deps on other modules. |
| `:core:db` | SQLDelight schema, queries, repositories (`Flow`-based). |
| `:core:divecomputer` | `Transport` interface, `DiveComputerKind`, protocols and parsers (pure common). |
| `:core:transport` | Platform transports, no iOS target. Common `SerialLineTransport` (half-duplex line discipline) over jSerialComm (jvm) or usb-serial-for-android; Bluetooth via `BluetoothRfcommTransport` (android) and `MacRfcommTransport` (macOS). |
| `:core:formats` | File formats (Subsurface XML, UDDF, MacDive XML, Subsurface git tree) over a neutral `DiveLog`. |
| `:core:gas` | Calculators for the Tools tab: gas blender (real-gas Z), tank buoyancy, MOD/END, Buhlmann ZHL-16C/GF planner. |
| `:core:logbook` | App services without Compose: `AppServices`, settings, `LogbookIo` import/export (incl. Shearwater Cloud SQLite), cloud sync (JGit, `CloudGit`), `DownloadController`. |
| `:ui` | Shared Compose Multiplatform screens. |
| `:app:android`, `:app:desktop`, `iosApp/` | Per-platform entry points. iOS has no download or cloud sync (`cloud = null`). |

- **Keep Android/JVM APIs out of commonMain** in every shared module. JDK code both JVM and Android use goes in `jvmAndAndroidMain`.
- Shared modules apply the `synth.kmp-library` convention plugin (`build-logic/`): `jvm()` (so common tests run on the JVM), Android, and the `jvmAndAndroid` source set. Each module adds its own iOS targets.
- Storage uses fixed integer units: depth mm, pressure mbar, temperature mK, duration s, gas permille. Conversion constants (`MM_PER_FOOT`, `ATM_BAR`, `ZERO_CELSIUS_MK`, ...) live in `core/model` `units/Constants.kt`.
- Each downloaded dive keeps its raw blob plus a format id, so it can be re-parsed after a parser fix (`DiveComputerKind.parserFor`).
- `DiveComputerKind` ties a model family to its `SerialParams`, protocol and parser. Each protocol owns its line settings as `SERIAL_PARAMS` (`SuuntoVyperProtocol`, `SuuntoVyper2Protocol`, `ShearwaterLink`).

## Conventions

- **Never use the Kotlin `!!` operator.** Use `?.`/`?:`/`let`/local-val smart-casts, or `requireNotNull(x){...}`. Remove any `!!` you touch.
- No emojis or em-dashes in commits or code comments; plain ASCII hyphens. Terse commit messages (subject line when possible).
- Do not commit personal dive data or memory-dump binaries.
- **Licence:** GPL-2.0-or-later. Code derived from Subsurface is allowed; such a file starts with `// SPDX-License-Identifier: GPL-2.0-only` and a comment naming the Subsurface source it follows (see `core/gas` `Buhlmann.kt`).

## Platform gotchas

- **JDK 25:** MapLibre Compose (desktop map) renders through the Java FFM API, so `:ui` and `:app:desktop` use `jvmToolchain(25)` and run with `--enable-native-access=ALL-UNNAMED`. `:ui` still emits Java 17 bytecode. Gradle itself runs on JDK 17+ (CI uses 21). The map is one commonMain composable, `ui/.../sites/SiteLocationMap.kt`.
- **macOS serial:** use the `cu.*` port, not `tty.*` (the dial-in node blocks on open under jSerialComm).
- **Shearwater Petrel/Predator** speak classic Bluetooth SPP, not BLE. On macOS the paired device's `cu.*` node never brings the link up; the desktop connects through the bundled Swift helper `app/desktop/native/rfcomm-bridge.swift` (`MacRfcommTransport`). Test without the app: `./gradlew :app:desktop:shearwaterCapture --args="PETREL 3"`.
- **Suunto HelO2 (Vyper2 family)** ignores a command sent less than ~500 ms after its previous reply; `txIdleMs = 600` handles it. When a serial download is flaky, check per-command timing before blaming the cable. Details in `docs/protocol/suunto-helo2.md`.
- **DB migrations:** SQLDelight `.sqm` files bump `Schema.version`; Android/iOS drivers auto-migrate, the jvm `createDatabase` (`DriverFactory.jvm.kt`) tracks `PRAGMA user_version` by hand. Add a `.sqm` when changing the schema.
- The Android debug build's application id is `no.synth.divelog.debug`.

## Build and test

```sh
./gradlew jvmTest                     # common tests on the JVM (CI)
./gradlew :app:android:assembleDebug  # Android debug APK
./gradlew :app:desktop:run            # desktop app
./gradlew :ui:compileKotlinIosSimulatorArm64   # iOS compile check
./gradlew :app:desktop:suuntoCapture --args="VYPER2 proto <port>"   # Suunto download on real hardware
SYNTH_DIVELOG_SIMULATOR=1 ./gradlew :app:desktop:run   # adds a simulated HelO2 port
adb shell am start -n no.synth.divelog.debug/no.synth.divelog.MainActivity --ez simulator true   # same, debug Android
```

There is no `compileDebugKotlinAndroid` task on `:ui`; use the app assemble task
to exercise Android. Protocol notes and the real-device test guide live in
[`docs/protocol/`](docs/protocol/README.md).

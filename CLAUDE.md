# Synth Divelog - working notes

Kotlin Multiplatform dive log (`no.synth.divelog`) with its own pure-Kotlin
dive-computer stack, for Android, desktop (JVM) and iOS.

## Architecture

Logic and UI live in `commonMain`; platform code is `expect`/`actual` or a thin
per-target entry point.

| Module | Role |
|---|---|
| `:core:model` | Domain types and fixed-unit values. No deps on other modules. |
| `:core:db` | SQLDelight schema, queries, repositories. |
| `:core:divecomputer` | Transport interface, protocols, parsers (pure common). |
| `:core:transport` | `expect`/`actual` transports: jSerialComm (jvm), USB-serial + classic-BT RFCOMM (android). |
| `:core:formats` | File formats (Subsurface XML, UDDF, MacDive XML, git-tree) over a neutral `DiveLog`. |
| `:core:gas` | Diving calculators (partial-pressure gas blender with real-gas Z, tank buoyancy, MOD/END, Buhlmann ZHL-16C/GF deco planner after Subsurface's), pure common. Shown under the Tools tab. |
| `:core:logbook` | App services without Compose: `AppServices`, settings, `LogbookIo` import/export, cloud sync (JGit), serial-download orchestration. |
| `:ui` | Shared Compose Multiplatform screens; screens follow the data through repository `Flow`s. |
| `:app:android`, `:app:desktop`, `iosApp/` | Per-platform entry points. |

- **Keep Android/JVM APIs out of `:core:model`, `:core:divecomputer`, `:core:formats`, `:core:gas`, `:core:logbook` and `:ui` commonMain.** Shared modules apply the `synth.kmp-library` convention plugin (`build-logic/`), which declares `jvm()` so common tests run on the JVM.
- Physical unit constants (`MM_PER_FOOT`, `ATM_BAR`, `ZERO_CELSIUS_MK`, ...) live in `core/model` `units/Constants.kt`.
- Storage uses fixed integer units: depth mm, pressure mbar, temperature mK, duration s, gas permille.
- Each downloaded dive keeps its raw blob plus a format id, so it can be re-parsed after a parser fix.
- A dive computer's serial line settings live in its protocol's `SERIAL_PARAMS` (`SerialParams`), applied by the platform transport.

## Conventions

- **Never use the Kotlin `!!` operator.** Use `?.`/`?:`/`let`/local-val smart-casts, or `requireNotNull(x){...}`. Remove any `!!` you touch.
- No emojis or em-dashes in commits or code comments; plain ASCII hyphens. Terse commit messages (subject line when possible).
- Do not commit personal dive data or memory-dump binaries.

## Platform gotchas

- **Desktop map** uses MapLibre Compose (`org.maplibre.compose`), which renders through the Java FFM API: the jvm build/run need **JDK 25** (`jvmToolchain(25)`) and `--enable-native-access=ALL-UNNAMED`. The map is one commonMain composable in `ui/.../sites/SiteLocationMap.kt`.
- **macOS serial:** use the `cu.*` port, not `tty.*` (the dial-in node blocks on open under jSerialComm).
- **Shearwater Petrel/Predator** speak classic Bluetooth SPP (a serial port), not BLE. On macOS the paired device's `cu.*` node no longer brings the link up; the desktop connects through the bundled Swift helper `app/desktop/native/rfcomm-bridge.swift` (`MacRfcommTransport`). Test without the app: `./gradlew :app:desktop:shearwaterCapture --args="PETREL 3"`.
- **Suunto HelO2 (Vyper2 family)** ignores a command sent less than ~500 ms after its previous reply; `txIdleMs = 600` handles it. When a serial download is flaky, check per-command timing before blaming the cable. Details in `docs/protocol/suunto-helo2.md`.
- **DB migrations:** SQLDelight `.sqm` files bump `Schema.version`; Android/iOS drivers auto-migrate, the jvm `createDatabase` (`DriverFactory.jvm.kt`) tracks `PRAGMA user_version` by hand. Add a `.sqm` when changing the schema.

## Build and test

```sh
./gradlew jvmTest                     # common tests on the JVM (CI)
./gradlew :app:android:assembleDebug  # Android debug APK
./gradlew :app:desktop:run            # desktop app (needs JDK 25 for the map)
./gradlew :ui:compileKotlinIosSimulatorArm64   # iOS compile check
./gradlew :app:desktop:suuntoCapture --args="VYPER2 proto <port>"   # Suunto download on real hardware
```

There is no `compileDebugKotlinAndroid` task on `:ui`; use the app assemble task
to exercise Android. Protocol notes written against real hardware live in
[`docs/protocol/`](docs/protocol/README.md).

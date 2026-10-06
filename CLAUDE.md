# Synth Divelog - working notes

A Kotlin Multiplatform dive log (`no.synth.divelog`) with its own pure-Kotlin
dive-computer stack. Targets Android, desktop (JVM) and iOS from one codebase.

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
| `:ui` | Shared Compose Multiplatform screens; also hosts `LogbookIo`, cloud sync, serial-download orchestration. |
| `:app:android`, `:app:desktop`, `iosApp/` | Per-platform entry points. |

**Keep Android/JVM APIs out of `:core:model`, `:core:divecomputer`, `:core:formats` and `:ui` commonMain.** Shared modules declare a `jvm()` target so common tests run on the JVM.

Storage uses fixed integer units: depth mm, pressure mbar, temperature mK,
duration s, gas permille. The raw download blob is kept so a record can be
re-parsed after a parser fix.

## Conventions (project-specific)

- **Never use the Kotlin `!!` operator.** Use `?.`/`?:`/`let`/local-val smart-casts, or `requireNotNull(x){...}`. Remove any `!!` you touch.
- **Originality:** these are clean-room implementations. Do not name "Subsurface" or "libdivecomputer" in code, comments or commit messages - the only exception is user-facing strings like "Subsurface XML" / "Subsurface cloud".
- No emojis or em-dashes in commits or code comments; plain ASCII hyphens. Terse commit messages (subject line when possible).
- Do not commit personal dive data or memory-dump binaries.

## Platform gotchas

- **Desktop map** uses MapLibre Compose (`org.maplibre.compose`), which renders through the Java FFM API: the jvm build/run need **JDK 25** (`jvmToolchain(25)`) and `--enable-native-access=ALL-UNNAMED`. The map is one commonMain composable in `ui/.../sites/SiteLocationMap.kt`.
- **macOS serial:** use the `cu.*` port, not `tty.*` (the dial-in node blocks on open under jSerialComm).
- **Shearwater Petrel/Predator** speak classic Bluetooth SPP (a serial port), not BLE. On macOS the paired device appears as a `cu.*` port.
- **DB migrations:** SQLDelight `.sqm` files bump `Schema.version`; Android/iOS drivers auto-migrate, the jvm `DriverFactory` tracks `PRAGMA user_version` by hand. Add a `.sqm` when changing the schema.

## Build and test

```sh
./gradlew jvmTest                     # common tests on the JVM (CI)
./gradlew :app:android:assembleDebug  # Android debug APK
./gradlew :app:desktop:run            # desktop app (needs JDK 25 for the map)
./gradlew :ui:compileKotlinIosSimulatorArm64   # iOS compile check
```

There is no `compileDebugKotlinAndroid` task on `:ui`; use the app assemble task
to exercise Android. Protocol notes written against real hardware live in
[`docs/protocol/`](docs/protocol/README.md).

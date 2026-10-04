# App icon concepts

Six candidate app icons for Synth Divelog. Each SVG is 1024x1024 with a filled
background and the key shape kept inside the ~66% adaptive-icon safe zone. Palette:
primary #00687B, dark variant #54D7F3, secondary #5A5B9E, tertiary #9C4332.

## Concepts

- `01-wave.svg` - rolling swell with a foam crest, teal background.
- `02-gauge.svg` - analog depth/pressure dial, single needle into the deep range.
- `03-mask.svg` - diver mask skirt with a single lens and nose pocket.
- `04-bubbles.svg` - air bubbles rising and expanding.
- `05-sonar.svg` - depth sounder ping with a bottom return blip.
- `06-droplet.svg` - single water droplet on an indigo background.

## Preview

Open `preview.html` in a browser. It shows every concept masked as a squircle and a
circle (the two adaptive-icon shapes), on light and dark surfaces, at 96px and 48px.
The SVGs are embedded, so the file works on its own.

192px PNGs are in `png/`, rendered with macOS Quick Look (`qlmanage`) because the
installed ImageMagick has no librsvg delegate and drops gradients and arc paths.
To regenerate:

    qlmanage -t -s 1024 -o /tmp/ql design/icons/*.svg
    for f in design/icons/*.svg; do b=$(basename "$f" .svg); \
      magick "/tmp/ql/$(basename "$f").png" -resize 192x192 "design/icons/png/$b.png"; done

## Applying a chosen icon

### Android (adaptive icon)

Current setup:

- `app/android/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and
  `ic_launcher_round.xml` reference a background color and a foreground drawable.
- Foreground: `app/android/src/main/res/drawable/ic_launcher_foreground.xml`
  (a 108dp vector drawable, white motif, transparent elsewhere).
- Background: `@color/ic_launcher_background` in
  `app/android/src/main/res/values/colors.xml` (currently `#6750A4`).

Easiest path (Android Studio): New > Image Asset > Launcher Icons (Adaptive and
Legacy). Set Foreground to the chosen SVG, set the Background color to the icon's
background color (for example `#00687B`, or `#5A5B9E` for the droplet). It rescales
into the safe zone and writes the adaptive XML plus all mipmap densities.

Manual path:

1. The adaptive foreground is a separate layer over the color background, so it must
   be the motif only. Delete the `<rect ... fill="url(#bg)"/>` from the chosen SVG
   first, and note that vector drawables use a flat background layer, not the SVG
   gradient.
2. In Android Studio, File > New > Vector Asset > Local file, pick the edited SVG,
   and save over `drawable/ic_launcher_foreground.xml`. Keep the motif within the
   inner 72dp of the 108dp canvas.
3. Set the background in `values/colors.xml`:
   `<color name="ic_launcher_background">#00687B</color>`.
4. The adaptive XML also sets `<monochrome>` to the same foreground. Themed icons
   want a single-color silhouette; either point `<monochrome>` at a flat white
   silhouette variant or drop that line.

### Desktop (jvm / jpackage)

`app/desktop/build.gradle.kts` sets the macOS icon:
`macOS { iconFile.set(project.file("packaging/AppIcon.icns")) }`. Replace
`app/desktop/packaging/AppIcon.icns`. Build a 1024 PNG and convert with iconutil:

    qlmanage -t -s 1024 -o /tmp design/icons/01-wave.svg
    mkdir AppIcon.iconset
    for s in 16 32 128 256 512; do \
      magick /tmp/01-wave.svg.png -resize ${s}x${s}   AppIcon.iconset/icon_${s}x${s}.png; \
      magick /tmp/01-wave.svg.png -resize $((s*2))x$((s*2)) AppIcon.iconset/icon_${s}x${s}@2x.png; done
    iconutil -c icns AppIcon.iconset -o app/desktop/packaging/AppIcon.icns

macOS does not round app icons, so the full-bleed square shows as-is. Add a rounded
rect and margin first if you want the standard macOS look. Only Dmg and Deb targets
are configured; the Deb (Linux) target has no icon wired.

### iOS

No app icon is wired yet. `iosApp/iosApp` has `Info.plist`, `iOSApp.swift`,
`ContentView.swift` and no asset catalog. To add one: create
`iosApp/iosApp/Assets.xcassets` with an `AppIcon` app-icon set, add it to the Xcode
target, and set the build setting `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`.

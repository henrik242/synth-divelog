# Privacy policy

Synth Divelog is a dive log for Android, iOS and desktop. This policy covers all versions of the
app.

Last updated: 2026-10-10 (crash reports added)

## Summary

The app has no user accounts, no analytics, no ads and no tracking. The only thing the developer
receives is a crash report if the app crashes on Android or iOS, and you can turn that off. Your
logbook stays on your device unless you choose to sync or export it.

## Data stored on your device

- Dives you download from a dive computer or enter by hand: dive profiles, depths, times, gases,
  tanks, sites (including their coordinates), buddies, notes and the raw data from the dive
  computer.
- Dive computers you have connected and their serial numbers.
- Settings, including the login for the Subsurface cloud if you enter one.

This data is kept in the app's private storage. On Android, it can be included in your device's
own backup (Google backup) if you have that turned on.

## Network connections

The app only connects to the following services, and only when you use the feature that needs
them:

- **Map tiles** from [OpenFreeMap](https://openfreemap.org) (`tiles.openfreemap.org`), when you
  view a map of dive sites. As with any web request, the map server sees your IP address and which
  map area is loaded.
- **Crash reports** to [Firebase Crashlytics](https://firebase.google.com/products/crashlytics)
  (Google), on Android and iOS, when the app crashes. A report holds the error and where in the
  code it happened, the app version, the device model and operating system version, and a random
  installation ID. It holds no dive data, no location and nothing that identifies you. Reports are
  used only to find and fix bugs. Turn them off under Settings > Privacy > Send crash reports.
  Google's handling is described in the
  [Firebase privacy information](https://firebase.google.com/support/privacy).
- **Subsurface cloud** (`ssrf-cloud-eu.subsurface-divelog.org`), only if you enter a cloud login
  and sync. Your email address, password and logbook are sent to that service. It is run by the
  Subsurface project, not by this app, and its own privacy policy applies.

## Dive computers

Downloading dives uses Bluetooth or USB to talk to your dive computer. The app does not use
Bluetooth to scan for or find your location, and it does not request location access.

## Import and export

Files you import or export (Subsurface, UDDF, MacDive, Shearwater Cloud) are read and written only
where you choose. What you do with an exported file is up to you.

## Children

The app is not directed at children and collects no data from anyone.

## Changes

Changes to this policy are published in this file. Its history is in the
[git log](https://github.com/henrik242/synth-divelog/commits/main/PRIVACY.md).

## Contact

Questions about this policy: open an issue at
<https://github.com/henrik242/synth-divelog/issues>.

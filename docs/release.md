# Releasing to Google Play and the App Store

Two manual GitHub Actions workflows, run from the Actions tab on `main`:

- **Release Android** builds a signed AAB, uploads it to a Play track (`internal` by default),
  tags the commit and attaches the AAB to a GitHub release.
- **Release iOS** builds a signed IPA, uploads it to App Store Connect (TestFlight; tick
  "Submit for App Store review" to also submit), tags the commit and attaches the IPA.

Both can run on the same commit; the second adds its file to the existing release.

Both workflows use the `production` GitHub environment, so the secrets below go there (or in
repository secrets). Fastlane is pinned in `Gemfile.lock` and runs through Bundler; bump it with
`bundle update fastlane`.

## Versions

- Tag `v<commit count>`, e.g. `v183`.
- Android: `versionCode` = commit count, `versionName` = `"<count>.<sha> <date>"`.
- iOS: `CFBundleVersion` = commit count, `CFBundleShortVersionString` = `1.0.<count>`
  (the "Set Build Number" build phase).

## Secrets

### Android

| Secret | What |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Upload keystore, `base64 -i upload.jks \| pbcopy` |
| `SIGNING_STORE_PASSWORD` | Keystore password (PKCS12, so also the key password) |
| `SIGNING_KEY_ALIAS` | Key alias |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Play service account key, base64 |

Locally, `SIGNING_STORE_FILE` and the two values above can go in `local.properties` instead;
without them `bundleRelease` produces an unsigned bundle.

### iOS

| Secret | What |
|---|---|
| `DISTRIBUTION_CERTIFICATE_BASE64` | Apple Distribution `.p12`, base64 |
| `DISTRIBUTION_CERTIFICATE_PASSWORD` | Password set when exporting the `.p12` |
| `PROVISIONING_PROFILE_BASE64` | App Store profile for `no.synth.divelog`, base64 |
| `ASC_KEY_ID` | App Store Connect API key id |
| `ASC_ISSUER_ID` | API key issuer id |
| `ASC_KEY_CONTENT` | The `.p8` key, base64 |
| `ASC_CONTACT_FIRST_NAME`, `ASC_CONTACT_LAST_NAME` | App Review contact (only checked when submitting) |
| `ASC_CONTACT_PHONE` | `+countrycode...`, e.g. `+4712345678` |
| `ASC_CONTACT_EMAIL` | |

The Where release secrets for the distribution certificate and API key can be reused (same
team); the provisioning profile is per app.

## First-time setup

### Google Play

1. Create an upload keystore (keep it and its passwords somewhere safe):
   `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`
2. In Play Console, create the app `no.synth.divelog` and enrol in Play App Signing.
3. Build a signed bundle locally (`./gradlew :app:android:bundleRelease` with the signing values in
   `local.properties`) and upload the first AAB by hand to the internal testing track. Fastlane
   cannot create an app or make its first upload.
4. Fill in the required store listing, content rating, data safety, target audience and privacy
   policy URL.
5. Service account: Google Cloud Console -> IAM -> Service Accounts -> create -> Keys -> JSON.
   Enable the Google Play Android Developer API, then invite the service account email in Play
   Console -> Users and permissions with release rights for the app.
6. A new personal developer account needs a closed test with enough testers for the required
   period before it can publish to production; run the workflow with `alpha` (closed testing)
   until then.

While the app has never been published, Play only accepts draft releases: uncheck "Release on
the track" for those runs.

### App Store

1. Apple Developer -> Identifiers: register the bundle id `no.synth.divelog`.
2. Profiles: new App Store Connect distribution profile for it with the distribution certificate;
   base64 it into `PROVISIONING_PROFILE_BASE64`.
3. App Store Connect -> Apps -> New App with that bundle id.
4. Before the first review submission, fill in the listing by hand: description, keywords,
   screenshots, privacy policy URL, age rating, app privacy answers. Until then run the workflow
   without "Submit for App Store review" and test through TestFlight.

The Xcode project uses automatic signing with team `358L7UXVJ4` for local builds; the release lane
switches the app target to manual signing with the profile from the secrets.

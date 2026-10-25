# Releasing

Releases are signed APKs on GitHub. A `vX.Y.Z` tag runs `.github/workflows/release.yml`, which
calls `scripts/build-release.sh`: it builds the release APK, signs it with the release key,
refuses to go on unless the signature matches the certificate pinned in `release-cert.sha256`,
and publishes the APK, its SHA-256 and the notes from `RELEASE_NOTES.md`. The app's update check
reads that release through GitHub's `releases/latest`, which skips drafts and pre-releases.

## One-time setup

The release key is the app's identity. Android installs an update only when it's signed by the
same key as the installed app, so a lost key means every user has to uninstall to move to a new
one. Make it once, keep two copies somewhere safe (a password manager and an offline backup), and
never commit it (`.gitignore` already refuses `*.jks` and `*.keystore`).

1. Make the key (the passwords are your choice; keytool asks for them):

   ```sh
   keytool -genkeypair -keystore droidective-release.jks -alias droidective \
     -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Droidective"
   ```

2. Pin its certificate. The fingerprint is public, so it's committed. Droidective for Mac pins the
   same value (`PhoneAppReleases.signingCertificateSHA256` in ADBKit): its device menu installs and
   updates this app from the latest release and refuses an APK signed by any other key. A new key
   therefore needs a Mac release carrying the new pin before the first APK signed with it.

   ```sh
   keytool -list -v -keystore droidective-release.jks -alias droidective \
     | sed -n 's/^.*SHA256: //p' > release-cert.sha256
   ```

3. Give the workflow the key, as repository secrets:

   ```sh
   base64 -i droidective-release.jks | gh secret set RELEASE_KEYSTORE_BASE64
   gh secret set RELEASE_KEYSTORE_PASSWORD
   gh secret set RELEASE_KEY_ALIAS --body droidective
   gh secret set RELEASE_KEY_PASSWORD
   ```

If Play ever happens, upload this same key to Play App Signing, so a Play install and a GitHub
install update each other.

4. Give it Sentry, for crash reports from the `droidective-android` project in the `ro-is`
   organization. The DSN goes into the APK at build time; the auth token uploads the R8 mapping
   and source context, so a crash's stack trace reads as source rather than R8's short names. Make
   the token under Settings ▸ Auth Tokens ▸ Organization Tokens.

   ```sh
   gh secret set SENTRY_DSN --body "<the project's DSN, from Settings ▸ Client Keys>"
   gh secret set SENTRY_AUTH_TOKEN
   ```

   A release builds without either, with a warning: no DSN means the APK sends nothing, and no
   token means stack traces stay obfuscated. Debug builds and builds without the DSN never report.

## Where the key is

Made on 2026-10-19 (RSA 4096, valid to 2126, certificate SHA-256 in `release-cert.sha256`):

- The keystore: `~/.droidective/release/droidective-release.jks` on the Mac it was made on (mode
  600), alias `droidective`.
- The password: that Mac's login Keychain, item "Droidective Android release key password". A
  second copy of the keystore, base64, is the item "Droidective Android release keystore (base64)".
- A copy of the keystore with a README, in `~/Desktop/Droidective Android release key/` on that
  Mac. The password isn't in it; the README says how to read it from the Keychain.
- GitHub: the four repository secrets above (plus Sentry's two, which aren't the key).

Every copy outside GitHub is on one Mac, so move one somewhere else (a password manager or an
offline drive), with the password. To build a release locally:

```sh
export DROIDECTIVE_KEYSTORE=~/.droidective/release/droidective-release.jks
export DROIDECTIVE_KEYSTORE_PASSWORD="$(security find-generic-password -a droidective \
  -s "Droidective Android release key password" -w)"
export DROIDECTIVE_KEY_ALIAS=droidective DROIDECTIVE_KEY_PASSWORD="$DROIDECTIVE_KEYSTORE_PASSWORD"
scripts/build-release.sh v0.1.0
```

## Each release

1. On a branch, bump `droidective.versionName` and `droidective.versionCode` in
   `gradle.properties`, and add a `## X.Y.Z` section to `RELEASE_NOTES.md`. CI fails a version
   with no notes.
2. Merge the PR, then tag the merge commit and push the tag:

   ```sh
   git tag v0.1.0 <merge commit>
   git push origin v0.1.0
   ```

3. Watch the Release workflow. It fails without publishing anything if the tag and
   `versionName` disagree, the notes are missing, or the APK isn't signed by the pinned key.

To build a release on your own machine, set `DROIDECTIVE_KEYSTORE` (the `.jks` path),
`DROIDECTIVE_KEYSTORE_PASSWORD`, `DROIDECTIVE_KEY_ALIAS` and `DROIDECTIVE_KEY_PASSWORD`, then run
`scripts/build-release.sh v0.1.0`. The output lands in `build/release/`.

#!/usr/bin/env bash
# Builds the release APK for a tag, signs it with the release key, checks the signature against
# the pinned certificate, and writes the APK, its SHA-256 and the release notes to build/release/.
# The release workflow runs this; so can you, with the same four variables set.
set -euo pipefail

tag=${1:?usage: build-release.sh vX.Y.Z}
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

fail() {
  echo "build-release: $*" >&2
  exit 1
}

version=$(sed -n 's/^droidective\.versionName=//p' gradle.properties)
[ "$tag" = "v$version" ] ||
  fail "tag $tag doesn't match droidective.versionName=$version in gradle.properties"

for var in DROIDECTIVE_KEYSTORE DROIDECTIVE_KEYSTORE_PASSWORD DROIDECTIVE_KEY_ALIAS \
  DROIDECTIVE_KEY_PASSWORD; do
  [ -n "${!var:-}" ] || fail "$var is not set; see docs/releasing.md"
done
[ -f "$DROIDECTIVE_KEYSTORE" ] || fail "no keystore at $DROIDECTIVE_KEYSTORE"

pin=$(tr -d '[:space:]:' <release-cert.sha256 2>/dev/null | tr '[:upper:]' '[:lower:]' || true)
[ ${#pin} -eq 64 ] ||
  fail "release-cert.sha256 must hold the release certificate's SHA-256; see docs/releasing.md"

notes=$(scripts/release-notes.sh "$tag")

# Neither stops a release: without the DSN the APK sends no crash reports, and without the token
# its crashes arrive with R8's names in their stack traces.
[ -n "${SENTRY_DSN:-}" ] ||
  echo "build-release: warning: SENTRY_DSN is not set, so this APK sends no crash reports" >&2
[ -n "${SENTRY_AUTH_TOKEN:-}" ] ||
  echo "build-release: warning: SENTRY_AUTH_TOKEN is not set, so the R8 mapping isn't uploaded" >&2

./gradlew --console=plain :app:assembleRelease

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
apksigner=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name apksigner | sort -V | tail -1)
[ -n "$apksigner" ] || fail "no apksigner under $sdk/build-tools"

apk=app/build/outputs/apk/release/app-release.apk
[ -f "$apk" ] || fail "the build made no signed APK at $apk; is the signing config set?"
"$apksigner" verify --min-sdk-version 30 "$apk"

# apksigner names signers "Signer #1" or "V2 Signer" depending on its version and the schemes the
# APK carries; whatever the names, there must be exactly one certificate.
signed=$("$apksigner" verify --print-certs "$apk" |
  sed -n 's/^.*[Ss]igner.*: certificate SHA-256 digest: //p; s/^Signer #[0-9]* certificate SHA-256 digest: //p' |
  sort -u)
[ "$(grep -c . <<<"$signed")" -eq 1 ] || fail "expected one signing certificate, found: ${signed:-none}"
[ "$signed" = "$pin" ] ||
  fail "signed by certificate $signed, but release-cert.sha256 pins $pin. Wrong keystore?"

out=build/release
mkdir -p "$out"
find "$out" -mindepth 1 -maxdepth 1 -type f -delete
name=droidective-android-$version.apk
cp "$apk" "$out/$name"
(cd "$out" && shasum -a 256 "$name" >"$name.sha256")
printf '%s\n' "$notes" >"$out/notes.md"
echo "build-release: $out/$name, signed by $signed"

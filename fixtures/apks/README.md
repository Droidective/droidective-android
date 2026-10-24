# bundletool .apks

`droidective-debug.toc.pb` and `droidective-debug.entries.txt` are the table of contents and the
file list of a real `.apks`, made 2026-10-19 with bundletool 1.18.3 (the jar the Mac bundles):
`build-apks --bundle=app-debug.aab` on this app's debug bundle. It has two variants, one for
API 30–31 and one for 32 and later, whose files end in `_2`.

# A signing certificate

`test-debug-cert.pem` is the public certificate of a throwaway key made 2026-10-20 with `keytool
-genkeypair -dname "CN=Android Debug,O=Android,C=US" -validity 10950`, the way Android Studio makes
a debug key. Its private key was never kept. SHA-256
`DB:01:6D:44:C6:BF:73:1A:DB:43:17:B7:21:E3:82:19:EB:BB:66:A0:68:DB:36:15:C9:A2:16:65:72:94:A3:04`.

# A dex header

`droidective-release-classes.dex.head` is the first 112 bytes, the header, of `classes.dex` in
a release build of this app made 2026-10-24 on top of 0.20.0 while #154 was written (R8,
minSdk 30). `dexdump -f` from
build-tools 37.0.0 reads it as version 039, file size 5062000, 21519 strings, 6575 types, 19118
fields, 26387 methods and 5408 class definitions.

# A compiled manifest

`droidective-release-manifest.bin` is `AndroidManifest.xml` as aapt2 compiled it into this app's
release APK, a build made 2026-10-24 on top of 0.20.0 whose manifest is 0.20.0's, and
`droidective-release-manifest.xml` is the merged manifest AGP gave aapt2 for it
(`app/build/intermediates/packaged_manifests/release/…`). The compiled one has a UTF-16 string
pool, 76 references, and the numbers aapt2 makes of `launchMode`, `protectionLevel`,
`windowSoftInputMode` and `foregroundServiceType`.

# DropBox entries

`adb shell dumpsys dropbox --print <tag>` from the `dv-api36` emulator (API 36), 2026-10-19:
Java crashes of this app and of Shizuku's manager (`data_app_crash`), of Settings and SystemUI
(`system_app_crash`), a SIGSEGV sent to this app's debug build (`data_app_native_crash`), and the
ANR that followed while debuggerd dumped it (`data_app_anr`, cut to its first 450 lines; the
app reads entries through a byte cap too). Each entry follows a `====` line and a date line;
what `DropBoxManager.Entry.getText` returns starts at the line after the date.

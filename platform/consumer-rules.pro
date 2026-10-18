# Shizuku starts ShellUserService by class name in a separate process, so R8 must keep its
# name and constructor even though nothing in the app references them directly.
-keep class com.droidective.platform.shell.ShellUserService { <init>(...); }

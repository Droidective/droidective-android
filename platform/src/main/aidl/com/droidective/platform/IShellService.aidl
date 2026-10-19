package com.droidective.platform;

import android.os.ParcelFileDescriptor;

// Runs in the process Shizuku starts as the shell user (or root, when Shizuku runs as root).
interface IShellService {
    // Shizuku reserves this transaction code for tearing the service down.
    void destroy() = 16777114;

    // Runs `sh -c command` and returns the read end of a pipe carrying its result, framed by
    // ResultFrame. Not a Bundle: a reply that size can overflow binder's buffer.
    ParcelFileDescriptor exec(String command, long timeoutMillis) = 1;

    // Starts `sh -c command` and returns the read end of a pipe carrying its stdout and stderr.
    // Closing that end stops the command.
    ParcelFileDescriptor stream(String command) = 2;
}

package com.droidective.platform;

import android.os.Bundle;

// Runs in the process Shizuku starts as the shell user (or root, when Shizuku runs as root).
interface IShellService {
    // Shizuku reserves this transaction code for tearing the service down.
    void destroy() = 16777114;

    // Runs `sh -c command`. The bundle holds "exit", "stdout" and "stderr".
    Bundle exec(String command, long timeoutMillis) = 1;
}

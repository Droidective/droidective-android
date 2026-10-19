package com.droidective.android.surface

import android.net.Uri
import android.provider.Settings
import com.droidective.core.toggles.Probe
import com.droidective.core.toggles.SettingScope

/** The content URI Android notifies when this setting changes, whoever changed it. */
fun Probe.Setting.uri(): Uri =
    when (scope) {
        SettingScope.Global -> Settings.Global.getUriFor(key)
        SettingScope.Secure -> Settings.Secure.getUriFor(key)
        SettingScope.SystemPublic,
        SettingScope.SystemPrivate -> Settings.System.getUriFor(key)
    }

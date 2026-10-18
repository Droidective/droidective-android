package com.droidective.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.droidective.core.toggles.SettingRead
import com.droidective.core.toggles.SettingScope
import com.droidective.core.toggles.SettingsStore

/** The Settings provider through the public API. Private System keys are the shell's job. */
class AndroidSettingsStore(private val context: Context) : SettingsStore {
    private val resolver
        get() = context.contentResolver

    override fun get(scope: SettingScope, key: String): SettingRead =
        try {
            SettingRead.Value(
                when (scope) {
                    SettingScope.Global -> Settings.Global.getString(resolver, key)
                    SettingScope.Secure -> Settings.Secure.getString(resolver, key)
                    SettingScope.SystemPublic,
                    SettingScope.SystemPrivate -> Settings.System.getString(resolver, key)
                }
            )
        } catch (_: SecurityException) {
            // Android 12+ throws for hidden keys not marked readable, such as
            // reduce_bright_colors_activated, even when the app may write them.
            SettingRead.Unreadable
        }

    override fun put(scope: SettingScope, key: String, value: String): Boolean =
        try {
            when (scope) {
                SettingScope.Global -> Settings.Global.putString(resolver, key, value)
                SettingScope.Secure -> Settings.Secure.putString(resolver, key, value)
                SettingScope.SystemPublic -> Settings.System.putString(resolver, key, value)
                SettingScope.SystemPrivate -> false
            }
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            // Android throws this for private System keys and refused Secure ones.
            false
        }

    override fun canWrite(scope: SettingScope): Boolean =
        when (scope) {
            SettingScope.Global,
            SettingScope.Secure ->
                context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED
            SettingScope.SystemPublic -> Settings.System.canWrite(context)
            SettingScope.SystemPrivate -> false
        }
}

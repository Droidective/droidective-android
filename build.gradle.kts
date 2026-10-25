// Declaring the Kotlin plugins here pins the Kotlin version for every module, including
// the Android ones, which use AGP's built-in Kotlin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.sentry.android.gradle) apply false
}

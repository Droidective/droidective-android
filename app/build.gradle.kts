plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.sentry.android.gradle)
}

// Like the release key, Sentry's DSN and auth token never enter the repo: the release workflow
// passes them in from secrets. A build without the DSN reports nothing, so a fork or a local build
// never sends crashes to this project. Without the token a release build still stamps the ProGuard
// UUID that matches a crash to its mapping file, and uploads nothing.
val sentryDsn = providers.environmentVariable("SENTRY_DSN").getOrElse("").trim()
val sentryUploads =
    providers.environmentVariable("SENTRY_AUTH_TOKEN").map { it.isNotBlank() }.getOrElse(false)

android {
    namespace = "com.droidective.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.droidective.android"
        minSdk = 30
        targetSdk = 37
        versionCode = providers.gradleProperty("droidective.versionCode").get().toInt()
        versionName = providers.gradleProperty("droidective.versionName").get()
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
    }

    // The release key never enters the repo. The release workflow (or a release build on your
    // machine) hands it over in these variables; without them a release build comes out unsigned.
    val keystore = providers.environmentVariable("DROIDECTIVE_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("DROIDECTIVE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("DROIDECTIVE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("DROIDECTIVE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // Dependabot owns version bumps; lint's "newer version" checks would fail the build
        // on every upstream release instead.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 16+ sandbox reaches into these JDK internals.
        unitTests.all {
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
            )
        }
    }
}

kotlin { compilerOptions { allWarningsAsErrors = true } }

ktfmt { kotlinLangStyle() }

sentry {
    org = "ro-is"
    projectName = "droidective-android"
    ignoredBuildTypes = setOf("debug")
    telemetry = false
    // The dependency is declared below, core only; the plugin would add NDK and Session Replay.
    autoInstallation { enabled = false }
    // Bytecode tracing would wrap every file read and log call, and this app reads other apps'
    // files and logs: their paths and lines must never become span names or breadcrumbs.
    tracingInstrumentation { enabled = false }
    autoUploadProguardMapping = sentryUploads
    includeSourceContext = sentryUploads
    autoUploadSourceContext = sentryUploads
}

dependencies {
    implementation(project(":platform"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.glance.appwidget)
    implementation(libs.work.runtime.ktx)
    implementation(libs.sentry.android.core)

    testImplementation(testFixtures(project(":core")))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

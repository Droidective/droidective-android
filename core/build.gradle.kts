import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin on the JVM: this module cannot import android.*, so every rule, table and
// parser here is testable in seconds without an emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.android.lint)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

ktfmt { kotlinLangStyle() }

lint {
    warningsAsErrors = true
    abortOnError = true
    disable += setOf("GradleDependency", "NewerVersionAvailable")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val fixtures = rootProject.layout.projectDirectory.dir("fixtures")

tasks.test {
    useJUnitPlatform()
    inputs.dir(fixtures)
    systemProperty("fixtures.dir", fixtures.asFile.absolutePath)
}

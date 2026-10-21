plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9 compiles Kotlin itself ("built-in Kotlin") with whichever Kotlin
    // Gradle plugin is on the build classpath; declaring it here pins that to
    // the same version as the Compose compiler plugin below.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// The string check (docs/I18N.md, BETA-PLAN gate 1) runs before every
// module's unit tests, so `./gradlew testDebugUnitTest` - the command the
// docs and every agent already run - fails on a new user-visible literal
// without anyone having to remember a second command. It is the repo's
// tools/i18n.py (python3 3.11 or newer, standard library only, the checker
// imported from the pinned wheel), run from the repo root because the
// allow-list, the baseline and .i18n-check.toml are written relative to it.
// No declared outputs, so it runs every time: about a second, and a cached
// "up to date" would hide a finding in a file Gradle does not track.
val i18nCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails on user-visible literals outside the string catalogs."
    workingDir = rootDir.parentFile
    commandLine("python3", "tools/i18n.py", "check")
}

subprojects {
    tasks.configureEach {
        if (name == "testDebugUnitTest") dependsOn(i18nCheck)
    }
}

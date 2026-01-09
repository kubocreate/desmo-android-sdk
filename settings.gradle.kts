pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Plugin versions for standalone SDK builds
    // (When used as a submodule, versions come from parent classpath)
    plugins {
        id("com.android.library") version "8.2.0"
        id("org.jetbrains.kotlin.android") version "1.9.24"
        id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
        id("com.google.devtools.ksp") version "1.9.24-1.0.20"
        id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "desmo-android-sdk"

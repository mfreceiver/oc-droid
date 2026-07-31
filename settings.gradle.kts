pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ocdroid"
include(":app")
// §sm-hardening B10: custom detekt rules module (sole-writer encapsulation gate).
// Pure Kotlin JVM module compiled BEFORE :app; provides a custom RuleSetProvider
// that :app loads via detektPlugins. Must not depend on Android.
include(":detekt-rules")

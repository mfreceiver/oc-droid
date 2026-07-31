// §sm-hardening B10: custom detekt rules module.
// Pure Kotlin JVM module — hosts the SessionListState sole-writer encapsulation
// gate (SessionStatusDirectWriteRule). Loaded by :app via
// detektPlugins(project(":detekt-rules")). No Android dependency; detekt-api is
// compileOnly (provided by the detekt runtime in :app).
plugins {
    // Version resolved once at the root (kotlin-compose + kotlin-jvm both pinned
    // to libs.versions.kotlin=2.2.10 via apply-false); apply without a version
    // here to avoid the "already on classpath with unknown version" conflict.
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.junit)
}

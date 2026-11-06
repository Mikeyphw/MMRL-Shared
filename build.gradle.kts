import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
    `java-library`
    `maven-publish`
}

group = "io.github.mikeyphw.mmrl"
version = "0.1.0-n05a"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    // Keep Java and Kotlin bytecode targets aligned for Android consumers.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

publishing {
    publications {
        create<MavenPublication>("sharedCore") {
            from(components["java"])
            artifactId = "mmrl-shared-core"
            pom {
                name.set("MMRL Shared Core")
                description.set("Pure Kotlin release, digest, GitHub workflow and sorting contracts")
                url.set("https://github.com/Mikeyphw/MMRL-Shared")
            }
        }
    }
    repositories {
        maven {
            name = "Staging"
            url = uri(layout.buildDirectory.dir("staging-maven"))
        }
    }
}

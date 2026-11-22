import com.vanniktech.maven.publish.DeploymentValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.jetbrains.dokka)
}

group = "one.felsen.fsrskt"
// The publish workflow passes the release tag as -PreleaseVersion.
version = providers.gradleProperty("releaseVersion").getOrElse("1.1.1-dev")
description = "This project is a Kotlin Multiplatform library that implements the FSRS-6 (Free Spaced Repetition Scheduler) algorithm."

kotlin {
    // Dumps the public API to library/api/. CI fails if it differs: run ./gradlew updateKotlinAbi and update API.md.
    // On Linux, the iOS targets cannot be built and are kept from the committed klib dump.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }
    androidLibrary {
        namespace = "org.jetbrains.kotlinx.multiplatform.library.template"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosArm64()
    iosSimulatorArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

dependencies {
    // Dokka plugin for Android SDK links in the docs; not a runtime dependency of the library.
    dokkaPlugin(libs.android.documentation.plugin)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    // signAllPublications()

    coordinates("one.felsen.fsrskt", "fsrskt", version.toString())

    pom {
        name.set("FsrsKt")
        description.set("This project is a Kotlin Multiplatform library that implements the FSRS-6 (Free Spaced Repetition Scheduler) algorithm.")
        inceptionYear.set("2026")
        url.set("https://github.com/therockyt/fsrskt/")
        licenses {
            license {
                name.set("GNU GENERAL PUBLIC LICENSE, Version 3")
                url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                distribution.set("https://www.gnu.org/licenses/gpl-3.0.html")
            }
        }
        developers {
            developer {
                id.set("TheRockYT")
                name.set("TheRockYT")
                url.set("https://github.com/therockyt/")
            }
        }
        scm {
            url.set("https://github.com/therockyt/fsrskt/")
            connection.set("scm:git:git://github.com/therockyt/fsrskt.git")
            developerConnection.set("scm:git:ssh://git@github.com/therockyt/fsrskt.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ywegel/FsrsKt")
            credentials(PasswordCredentials::class)
        }
    }
}

dokka {
    pluginsConfiguration.html {
        moduleName.set(rootProject.name)
        moduleVersion.set(project.version.toString())

        footerMessage.set("Made with <3")
        separateInheritedMembers.set(true)
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

/*
 * The known-answer vectors (copied verbatim from the desktop repo's
 * scripts/fixtures/remote-vectors.json) are compiled into commonTest as a
 * string constant. commonTest has no cross-platform resource loader, and a
 * constant works identically on the JVM host test, the Android device test and
 * an iOS simulator test.
 */
val generateRemoteVectors = tasks.register("generateRemoteVectors") {
    val source = layout.projectDirectory.file("src/commonTest/resources/remote-vectors.json")
    val outDir = layout.buildDirectory.dir("generated/remoteVectors/kotlin")
    inputs.file(source)
    outputs.dir(outDir)
    doLast {
        val json = source.asFile.readText()
        require(!json.contains("\"\"\"")) { "remote-vectors.json cannot be embedded in a raw string" }
        val escaped = json.replace("$", "\${'$'}")
        val file = outDir.get().file("com/skidsense/mobile/rc/RemoteVectorsJson.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// Generated from src/commonTest/resources/remote-vectors.json. Do not edit.\n" +
                "package com.skidsense.mobile.rc\n\n" +
                "internal const val REMOTE_VECTORS_JSON: String = \"\"\"" + escaped + "\"\"\"\n"
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    android {
        namespace = "com.skidsense.mobile.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.ExperimentalStdlibApi")
        optIn.add("kotlin.time.ExperimentalTime")
        // Nonces are explicit by design (counter nonces, spec §5), which the library flags as delicate.
        optIn.add("dev.whyoleg.cryptography.DelicateCryptographyApi")
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.bouncycastle.bcprov)
            implementation(libs.zxing.android.embedded)
            implementation(libs.zxing.core)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            // Apple (CommonCrypto) + CryptoKit: CryptoKit carries X25519, HKDF and AES-GCM on iOS.
            implementation(libs.cryptography.provider.optimal)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.kotlinxJson)
            api(libs.cryptography.core)
        }
        commonTest {
            kotlin.srcDir(generateRemoteVectors)
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        // The on-device crypto test: the same known answers, on a real API
        // level, because that is the only place the X25519 story can be checked.
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.testRunner)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

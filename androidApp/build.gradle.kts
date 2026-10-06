import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

/*
 * Release signing, handed in through the environment by CI
 * (.github/workflows/build.yml) and never committed. Without
 * SKIDSENSE_KEYSTORE a release build stays unsigned, exactly as before.
 *
 * It matters more than usual here: the device's pairing key lives in this
 * app's Keystore, and Android refuses to update an app whose signature
 * changed — the only way past that is an uninstall, which takes the key and
 * every pairing with it. So every build meant for a phone must carry the
 * same, stable signature.
 */
val releaseKeystore = providers.environmentVariable("SKIDSENSE_KEYSTORE").orNull
    ?.let { file(it) }
    ?.takeIf { it.isFile }
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.skidsense.mobile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.skidsense.mobile"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // CI numbers its builds (SKIDSENSE_VERSION_CODE = the run number), so a
        // newer artifact always installs as an update over an older one.
        versionCode = providers.environmentVariable("SKIDSENSE_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("SKIDSENSE_VERSION_NAME").orNull ?: "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("SKIDSENSE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("SKIDSENSE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("SKIDSENSE_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

}
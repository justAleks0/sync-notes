plugins {
    // AGP 9 has built-in Kotlin support, so no kotlin-android plugin — but the
    // Compose compiler plugin is still applied separately.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Single source of truth for the app version — the release script rewrites this line,
// and UpdateChecker compares it against the newest GitHub release tag.
val appVersionName = "0.11.0"

// Android needs a monotonically increasing integer, so derive one from the version.
// 0.1.0 -> 100, 1.2.3 -> 10203.
val appVersionCode = appVersionName.split(".")
    .map { it.toIntOrNull() ?: 0 }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

android {
    namespace = "com.justaleks.syncnotes"
    // AndroidX 1.19 / lifecycle 2.11 require compiling against API 37.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.justaleks.syncnotes"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the debug key on purpose: sideloaded updates only install
            // over an existing app if both APKs share a signature, and every build so
            // far came from this machine's debug keystore. Swap this for a real release
            // keystore before distributing to anyone else — see README.
            signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.kotlinx.coroutines.play.services)

    // Markdown rendering, with Coil fetching the images referenced from note bodies.
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Google sign-in goes through Credential Manager; Play Services Auth is the
    // backend that actually knows about the accounts on the device.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

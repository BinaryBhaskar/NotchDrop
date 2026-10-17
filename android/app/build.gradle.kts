import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release key lives outside the repo: ~/.notchdrop-keys/keystore.properties, or the file named by
// ND_KEYSTORE_PROPERTIES (CI writes it from secrets). Without it, release builds use the debug key.
val releaseKeys: Properties? = (System.getenv("ND_KEYSTORE_PROPERTIES")?.let(::File)
    ?: File(System.getProperty("user.home"), ".notchdrop-keys/keystore.properties"))
    .takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.amurot.notchdrop"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.amurot.notchdrop"
        minSdk = 29
        // Play requires 36 for new apps and updates. Stay below 37, where Android 17's local-network permission applies.
        targetSdk = 36
        versionCode = 5
        versionName = "1.2.1"
        // 64-bit ARM covers modern phones and Apple-silicon emulators; keeps ML Kit's native libs small.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (releaseKeys != null) create("release") {
            storeFile = File(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    // github: the APK on GitHub Releases, which checks there for updates. play: Google Play, which updates it itself
    // (Play policy forbids any other update path).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    buildTypes {
        // Installs next to the real app as "NotchDrop Dev" and listens on its own port, so both can be on one phone.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "NotchDrop Dev")
            resValue("string", "share_label", "NotchDrop Dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeys != null) "release" else "debug")
            resValue("string", "app_name", "NotchDrop")
            resValue("string", "share_label", "Send to Mac")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    testOptions {
        unitTests.all {
            it.environment("ND_MAC_BIN", rootProject.file("../mac/.build/debug/NotchDrop").absolutePath)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

import java.util.Properties

// A Maps SDK key belonging to this project can be supplied via the
// MAPS_API_KEY environment variable or an ignored local.properties file.
val mapsLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val mapsApiKey = System.getenv("MAPS_API_KEY")
    ?: mapsLocalProperties.getProperty("MAPS_API_KEY", "")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing config is read from keystore.properties, which is gitignored along with the
// keystore itself. Anyone holding both can ship an update that installs over a user's copy,
// so neither belongs in a public repo. Release builds fall back to unsigned when the file
// is absent, so a clone still builds.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.debashis.carlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.qcardash.launcher"
        // The head unit is Android 11 / SDK 30 exactly. No compatibility shims needed.
        minSdk = 29
        targetSdk = 30
        versionCode = 3
        versionName = "1.2"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // targetSdk 30 is deliberate: the head unit IS Android 11 / SDK 30, and matching it
        // exactly avoids every behaviour shim. ExpiredTargetSdkVersion exists to enforce
        // Play Store policy, and this app is sideloaded, not published there.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No Google Play services dependencies. Keep the launcher small and framework-only.
dependencies {
    implementation("com.google.android.gms:play-services-maps:20.1.0")
}

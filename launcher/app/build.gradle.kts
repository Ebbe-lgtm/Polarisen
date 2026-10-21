plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "se.isbjorn.home"
    compileSdk = 34

    defaultConfig {
        applicationId = "se.isbjorn.home"
        minSdk = 28
        targetSdk = 28 // deliberate: the TV runs Android 9 and this app is sideloaded, never published to Play
        versionCode = 2
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // shortcut: debug-signed release, fine for a sideloaded personal launcher; use a real keystore if ever distributed
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        disable += "ExpiredTargetSdkVersion" // Play-only rule; targetSdk 28 is deliberate (see above)
    }
    // Uncompressed fonts are mmapped straight from the APK instead of inflated once per weight at startup
    androidResources { noCompress += "ttf" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

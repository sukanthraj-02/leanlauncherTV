plugins {
    id("com.android.application")
}

android {
    namespace = "com.sukanth.leanlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sukanth.leanlauncher"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Kotlin's jvmTarget defaults to android.compileOptions.targetCompatibility.
// androidx.profileinstaller arrives transitively and registers a receiver plus a startup
// provider. Neither helps a launcher, and both cost work at boot.
configurations.configureEach {
    exclude(group = "androidx.profileinstaller")
}

dependencies {
    // RecyclerView is the only UI dependency. androidx.activity and core-ktx were removed:
    // the activities are plain android.app.Activity, so the lifecycle, savedstate and
    // viewmodel tree is not needed and never enters the process.
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Small, dependency-free ADB network client used only for the optional on-device
    // one-press switch. It never becomes a general-purpose terminal.
    implementation("com.tananaev:adblib:1.3")

    testImplementation("junit:junit:4.13.2")
}
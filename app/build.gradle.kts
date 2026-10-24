import java.util.Properties

plugins {
    id("com.android.application")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
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

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
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
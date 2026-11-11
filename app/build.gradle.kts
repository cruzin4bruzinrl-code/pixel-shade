plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "se.rob.pixelpanel"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.rob.pixelpanel"
        minSdk = 31
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        buildConfigField("String", "UPDATE_REPO", "\"cruzin4bruzinrl-code/pixel-shade\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../signing/release.jks")
            storePassword = "pixelpanel"
            keyAlias = "pixelpanel"
            keyPassword = "pixelpanel"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.palette:palette:1.0.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

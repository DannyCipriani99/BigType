plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bigtype.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bigtype.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "3.3"
    }

    signingConfigs {
        create("bigtype") {
            storeFile = file("bigtype.keystore")
            storePassword = "bigtype-sign"
            keyAlias = "bigtype"
            keyPassword = "bigtype-sign"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("bigtype")
        }
        release {
            signingConfig = signingConfigs.getByName("bigtype")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

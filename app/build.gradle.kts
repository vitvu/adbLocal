plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vvtool.adblocal"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.vvtool.adblocal"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            isShrinkResources = true
            // Signed with the debug key so the APK can be installed directly.
            // Replace with your own signingConfig for store releases.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

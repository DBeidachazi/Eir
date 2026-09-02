plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "icu.dbeidachazi.eir"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "icu.dbeidachazi.eir"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(files("libs/samsung-health-sensor-api-1.4.1.aar"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.play.services.wearable)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)
    implementation(libs.health.services.client)
    implementation("com.google.guava:guava:33.4.0-android")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}

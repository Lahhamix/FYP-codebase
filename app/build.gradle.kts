plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.ble_viewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ble_viewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Vector drawables are commonly used with Compose icons
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        // Ensure this version is compatible with your Kotlin plugin version.
        // Refer to the Compose-Kotlin compatibility map if you get a build error.
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    // Add packaging options, required for Compose
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Keep your existing dependencies
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")

    // --- START: ADDED COMPOSE DEPENDENCIES ---

    // Import the Compose BOM to manage library versions
    // You could also add this to your libs.versions.toml for better management
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX Activity for Compose integration
    implementation("androidx.activity:activity-compose:1.9.0")

    // Core Compose libraries (versions are managed by the BOM)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3") // Using Material3 is recommended
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Debug-only dependencies for tooling support (e.g., @Preview)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- END: ADDED COMPOSE DEPENDENCIES ---

    // Keep your existing test dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // Add Compose test dependencies if needed for UI testing
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

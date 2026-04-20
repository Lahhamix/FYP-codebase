import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.ble_viewer"
    compileSdk = 34

    val localProperties = Properties().apply {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
    }

    val sendGridApiKey = (localProperties.getProperty("SENDGRID_API_KEY") ?: "").trim()
    val sendGridFromEmail = (localProperties.getProperty("SENDGRID_FROM_EMAIL") ?: "").trim()
    val sendGridApiBaseUrl = (localProperties.getProperty("SENDGRID_API_BASE_URL") ?: "https://api.sendgrid.com").trim()
    val sharedEmailBackendBaseUrl = providers.gradleProperty("EMAIL_BACKEND_BASE_URL").orNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val emailBackendBaseUrlDebug = (
        providers.gradleProperty("EMAIL_BACKEND_BASE_URL_DEBUG").orNull
            ?: localProperties.getProperty("EMAIL_BACKEND_BASE_URL_DEBUG")
            ?: sharedEmailBackendBaseUrl
            ?: localProperties.getProperty("EMAIL_BACKEND_BASE_URL")
            ?: "http://10.0.2.2:3000"
    ).trim()
    val emailBackendBaseUrlRelease = (
        providers.gradleProperty("EMAIL_BACKEND_BASE_URL_RELEASE").orNull
            ?: localProperties.getProperty("EMAIL_BACKEND_BASE_URL_RELEASE")
            ?: sharedEmailBackendBaseUrl
            ?: "https://your-backend.example.com"
    ).trim()
    val autoShareRecipientFallback = (localProperties.getProperty("AUTO_SHARE_RECIPIENT_FALLBACK") ?: "").trim()

    defaultConfig {
        applicationId = "com.example.ble_viewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "SENDGRID_API_KEY", "\"$sendGridApiKey\"")
        buildConfigField("String", "SENDGRID_FROM_EMAIL", "\"$sendGridFromEmail\"")
        buildConfigField("String", "SENDGRID_API_BASE_URL", "\"$sendGridApiBaseUrl\"")
        buildConfigField("String", "AUTO_SHARE_RECIPIENT_FALLBACK", "\"$autoShareRecipientFallback\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "EMAIL_BACKEND_BASE_URL", "\"$emailBackendBaseUrlDebug\"")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "EMAIL_BACKEND_BASE_URL", "\"$emailBackendBaseUrlRelease\"")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
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
        buildConfig = true
        compose = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("com.google.android.gms:play-services-auth:21.1.1")
    implementation("com.github.angads25:toggle:1.1.0")
    implementation("com.github.yalantis:ucrop:2.2.8")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Charting library
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

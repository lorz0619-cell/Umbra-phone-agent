import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties =
    Properties().apply {
        val propertiesFile = rootProject.file("local.properties")
        if (propertiesFile.exists()) {
            propertiesFile.inputStream().use { load(it) }
        }
    }
val bundledApiKey = localProperties.getProperty("umbra.apiKey", "")
val releaseSigningPropertiesFile = rootProject.file("../.release-secrets/keystore.properties")
val releaseSigningProperties =
    Properties().apply {
        if (releaseSigningPropertiesFile.exists()) {
            releaseSigningPropertiesFile.inputStream().use { load(it) }
        }
    }
val releaseSigningConfigured =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !releaseSigningProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.bluewhale.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bluewhale.agent"
        minSdk = 31
        targetSdk = 35
        versionCode = 15
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Personal convenience only. android/local.properties is ignored by Git.
            buildConfigField("String", "DEFAULT_API_KEY", "\"$bundledApiKey\"")
        }
        release {
            // Public artifacts must never inherit a developer's local API key.
            buildConfigField("String", "DEFAULT_API_KEY", "\"\"")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("debug"))
            // Benchmark builds use the API key already stored in app-private preferences.
            buildConfigField("String", "DEFAULT_API_KEY", "\"\"")
            matchingFallbacks += listOf("debug")
            if (releaseSigningConfigured) {
                // Preserve app data when temporarily replacing a locally installed release build.
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets.getByName("benchmark").java.srcDir("src/debug/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Apache-2.0 offline speech recognition. The Chinese model is downloaded
    // from the official Vosk model host on first use to keep the APK compact.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

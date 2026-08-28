import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

/**
 * Secrets resolution order: local.properties (your laptop) -> environment
 * variable (CI) -> empty string. Empty is not a build failure on purpose --
 * the app boots into SetupGate and tells you exactly what is missing, which
 * is far easier to diagnose than a blank grey map.
 */
fun secret(name: String): String {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    return props.getProperty(name) ?: System.getenv(name) ?: ""
}

android {
    namespace = "com.nikhil.ridetogether"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nikhil.ridetogether"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Consumed by <meta-data android:name="com.google.android.geo.API_KEY"> in the manifest.
        manifestPlaceholders["MAPS_API_KEY"] = secret("MAPS_API_KEY")

        buildConfigField("String", "MAPS_API_KEY", "\"${secret("MAPS_API_KEY")}\"")
        buildConfigField("String", "PROXY_BASE_URL", "\"${secret("PROXY_BASE_URL")}\"")
    }

    /*
     * One committed keystore, used by debug AND release, on every machine.
     *
     * This is deliberate. The Google Maps API key is locked to
     * (package name + signing SHA-1); Firebase pins the same SHA-1. A
     * per-machine auto-generated debug keystore would change that fingerprint
     * on every CI runner and silently break the map. Pinning it here means the
     * fingerprint is constant forever:
     *
     *   SHA-1: 41:06:BD:CF:E5:C8:9A:9E:B1:13:6F:3B:DD:C1:CB:89:DF:A7:8A:F4
     *
     * The password is in plain sight because this key protects nothing -- the
     * app is sideloaded, not published to Play. If you ever do publish, make a
     * separate upload key and keep it out of the repo.
     */
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/ridetogether.keystore")
            storePassword = "ridetogether"
            keyAlias = "ridetogether"
            keyPassword = "ridetogether"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Lint runs as its own reported CI step. Failing the APK build on a
        // style warning helps nobody.
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    // FakeRideRepository is shared by the JVM unit tests and the on-device
    // instrumented tests, but must not end up in the shipped APK -- hence its
    // own source set rather than living in src/main.
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // LifecycleService, so the location service gets a scope that dies with it.
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Maps + location
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.maps.android:maps-compose:6.2.1")

    // Places autocomplete. Uses the Android-restricted key, so the key in the
    // APK is useless to anyone who extracts it.
    implementation("com.google.android.libraries.places:places:4.0.0")

    // Firebase: anonymous auth + realtime database for the ride room.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-auth")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

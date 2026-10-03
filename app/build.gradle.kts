import java.util.Properties

plugins {
    // Same plugin set as anon-browser — known to build inside Termux + proot-distro.
    id("com.android.application") version "8.6.1"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

// Release signing lives outside the repo (path set via TORY_ACCESS_KEYSTORE_PROPERTIES,
// defaulting to a sibling directory) so the keystore/passwords never end up in git.
val keystorePropertiesFile = file(
    providers.environmentVariable("TORY_ACCESS_KEYSTORE_PROPERTIES")
        .getOrElse("../../tory-access-keys/keystore.properties")
)
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.joshreimer.toryaccess"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.joshreimer.toryaccess"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Optional: -Ptory.abis=arm64-v8a builds a single-ABI APK (~1/3 the size) — handy for
        // quick device/emulator iterations and for Termux builds on the phone itself.
        providers.gradleProperty("tory.abis").orNull?.let { abis ->
            ndk { abiFilters += abis.split(',').map(String::trim).filter(String::isNotEmpty) }
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
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
            // Separate package so a debug build (with its automation receiver) can sit
            // next to a release install on the same phone.
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Tory Access (debug)")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Tory Access")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/versions/*/OSGI-INF/**",
            )
        }
        jniLibs {
            // tor-android ships libtor.so per-ABI; it must stay uncompressed and be
            // extracted to nativeLibraryDir so we can exec it under W^X (API 29+).
            useLegacyPackaging = true
            // We only use Termux's pure-Java emulator, never its pty JNI, and its
            // libtermux.so is 4KB-aligned (fails the 16KB-page check). Drop it.
            excludes += "**/libtermux.so"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Pinned above what Compose 2024.09.02 pulls in transitively (1.0.1) — 1.1.0 is built
    // 16KB-page-aligned, 1.0.1 isn't (flagged by Android's debug-build compatibility warning).
    implementation("androidx.graphics:graphics-path:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Bundled tor daemon. 0.4.8.22 is the first build with a 16KB-page-aligned libtor.so.
    implementation("info.guardianproject:tor-android:0.4.8.22") {
        // We drive tor's control port ourselves; jtorctl isn't needed.
        exclude(group = "info.guardianproject", module = "jtorctl")
    }
    // tor-android 0.4.8.22 declares kotlin-stdlib:2.3.0, which Kotlin 2.0.21 can't read.
    // It's a thin binary wrapper, so force the stdlib back to our compiler's version.
    implementation("org.jetbrains.kotlin:kotlin-stdlib") {
        version { strictly("2.0.21") }
    }

    // SSH client (maintained JSch fork: rsa-sha2, ed25519, curve25519, SOCKS5 proxy).
    implementation("com.github.mwiede:jsch:2.28.7")
    // Gives JSch Ed25519 / X25519 on Android, whose platform JCA lacks them pre-API 33/34.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    // Termux's terminal emulator (xterm-256color state machine; we render it ourselves).
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

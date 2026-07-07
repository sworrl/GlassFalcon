import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildDate: String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

// Auto-incrementing build number — was a hand-edited literal that went stale across many
// installs during a single session (easy to forget to bump by hand every time). Reads+writes
// version.properties at CONFIGURATION time (evaluated on every Gradle invocation against this
// module, not gated to only `assembleDebug`), so it may tick up slightly more often than exactly
// "once per install" if a compile-only check runs in between — harmless: still monotonically
// increasing, still unique per actual build, and the alternative (a separate task precisely
// scoped to only assemble) hits a configuration-vs-execution timing pitfall in the same Gradle
// invocation. versionCode and the visible "0.0.N-BETA" versionName now share the same counter.
val versionPropsFile = file("version.properties")
val versionProps = Properties().apply { if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) } }
val buildNumber = (versionProps.getProperty("versionCode", "70").toIntOrNull() ?: 70) + 1
versionProps.setProperty("versionCode", buildNumber.toString())
versionPropsFile.outputStream().use { versionProps.store(it, "Auto-incremented by app/build.gradle.kts on every build") }

android {
    namespace = "dev.glassfalcon"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.glassfalcon"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        // Human version rolls the patch field at 100: build 99 -> 0.0.99, build 100 -> 0.1.00,
        // build 122 -> 0.1.22. versionCode stays the raw monotonic counter for the Play/install
        // "is this newer" check; only the visible name is grouped into minor.patch.
        versionName = "0.${buildNumber / 100}.${(buildNumber % 100).toString().padStart(2, '0')}-BETA"
        ndk {
            // arm64 only. Every phone that can host a Mavic RC is arm64-v8a; x86_64 is emulator-only
            // and armeabi-v7a is ancient 32-bit. Shipping one ABI instead of three drops ~37 MB of
            // duplicated native libs (libmaplibre.so + libmlkitcommonpipeline.so ×3). Re-add x86_64
            // here if you want to run the app on an x86 emulator for UI work.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Release signing — keystore.properties + the .jks are gitignored (see .gitignore). Absent on
    // a fresh clone → release falls back to unsigned; the committed source never carries the key.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) } }
    signingConfigs {
        if (keystorePropsFile.exists()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
            // R8/minify disabled: this AGP's R8 can't parse Kotlin 2.2.x metadata (max supported
            // 2.0.0), and obfuscation is pointless for a public GPL source tree anyway. The release
            // APK is still release-signed; it's just not shrunk. Re-enable when AGP/R8 catches up.
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Private/custom plugins developed locally. plugins/local/ is gitignored and never uploaded;
    // if it has a kotlin/ dir, its source compiles into this (local) build. Absent on a clean
    // clone, so this is a no-op for anyone else.
    val localPlugins = rootProject.file("../plugins/local/kotlin")
    if (localPlugins.exists()) sourceSets.getByName("main").kotlin.srcDir(localPlugins)

    packaging {
        resources { excludes += setOf("META-INF/**", "*.txt", "DebugProbesKt.bin") }
        jniLibs { keepDebugSymbols += setOf("**/*.so") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    applicationVariants.configureEach {
        val variant = this
        outputs.configureEach {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "GlassFalcon-$buildDate-${variant.versionName}.apk"
            }
        }
    }
}

dependencies {
    // Glass Falcon SDK — DUML protocol, telemetry, mission logic (AAR module)
    implementation(project(":sdk"))

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // At-rest encryption: EncryptedSharedPreferences for secrets (API keys) + a MasterKey in the
    // Android Keystore (TEE/StrongBox-backed where available). The app's own AES-256-GCM file
    // encryption (SecureStore) uses the platform Keystore directly and needs no extra dep.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Maps (MapLibre — offline; 11.7+ adds PMTiles local-file sources)
    implementation("org.maplibre.gl:android-sdk:11.9.0")

    // Video: ExoPlayer for RTSP relay preview
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // Real backdrop blur for the Liquid Glass panels (RenderEffect-based on API 31+, no-op
    // blur fallback below that — our minSdk 26 still renders fine, just without the blur).
    implementation("dev.chrisbanes.haze:haze:0.7.3")

    // On-device Gemini Nano (AICore) for the "AI Assisted Copilot" mode — no API key, no
    // network. Gated at runtime by GenerativeModel.checkStatus(); devices without AICore
    // report FeatureStatus.UNAVAILABLE rather than crashing. Real published coordinates,
    // verified against dl.google.com/android/maven2 (not guessed) on 2026-07-03.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
    implementation("com.google.mlkit:genai-common:1.0.0-beta3")

    // ActiveTrack subject detection/tracking — on-device, no network. Real published
    // coordinates, verified against dl.google.com/android/maven2 AND decompiled directly
    // (same discipline as the genai-prompt entry above) on 2026-07-04.
    implementation("com.google.mlkit:object-detection:17.0.2")

    // WebSocket client for the encrypted re-streamer plugin (StreamPublisher) — the phone→relay
    // ingest and the token API both ride OkHttp. Android ships no WebSocket client and no
    // java.net.http, so this is the pragmatic dependency for it.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

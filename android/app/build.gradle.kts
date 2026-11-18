import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Clé de publication partagée avec Dicto Raccourcis (permission de signature) :
// android/keystore.properties (ignoré par git) ou variables DICTO_* en CI.
val signing: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        Properties().apply { file.inputStream().use { load(it) } }
            .let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
    } else {
        System.getenv("DICTO_STORE_FILE")?.let {
            mapOf(
                "storeFile" to it,
                "storePassword" to System.getenv("DICTO_STORE_PASSWORD").orEmpty(),
                "keyAlias" to System.getenv("DICTO_KEY_ALIAS").orEmpty(),
                "keyPassword" to System.getenv("DICTO_KEY_PASSWORD").orEmpty(),
            )
        }
    }
}

android {
    namespace = "app.dicto"
    compileSdk = 37
    // Le même NDK que scripts/build-android-libs.sh : AGP s'en sert pour retirer les symboles des .so.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.dicto"
        minSdk = 29
        targetSdk = 37
        versionCode = 8
        versionName = "0.1.7"
    }

    // Les bibliothèques natives (cœur Rust + whisper.cpp) viennent de scripts/build-android-libs.sh.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    base.archivesName.set("Dicto-v${defaultConfig.versionName}")

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getValue("storeFile"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Bibliothèques natives compressées dans l'APK (onnxruntime pèse 21 Mo) : téléchargement
        // deux fois plus léger, au prix d'une extraction à l'installation.
        jniLibs.useLegacyPackaging = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Tests du pont Kotlin ↔ Rust sur la JVM : bibliothèque compilée pour la machine.
            val hostLib = rootProject.file("../core/target/debug/libdicto_ffi.so")
            it.systemProperty("uniffi.component.dicto_ffi.libraryOverride", hostLib.path)
            it.systemProperty("dicto.whisperModel", project.findProperty("dicto.whisperModel")?.toString().orEmpty())
            it.systemProperty("dicto.sampleWav", project.findProperty("dicto.sampleWav")?.toString().orEmpty())
            it.systemProperty("dicto.frWav", project.findProperty("dicto.frWav")?.toString().orEmpty())
            // Captures d'écran : langue (fr, en) et thème (light, dark).
            it.systemProperty("dicto.screenshotLang", project.findProperty("dicto.screenshotLang")?.toString() ?: "fr")
            it.systemProperty("dicto.screenshotTheme", project.findProperty("dicto.screenshotTheme")?.toString() ?: "light")
            // Une autre langue ou un autre thème : les tests doivent tourner de nouveau.
            it.inputs.property("dicto.screenshotLang", project.findProperty("dicto.screenshotLang")?.toString() ?: "fr")
            it.inputs.property("dicto.screenshotTheme", project.findProperty("dicto.screenshotTheme")?.toString() ?: "light")
            // sherpa-onnx et onnxruntime sont à côté de la bibliothèque de test.
            it.environment("LD_LIBRARY_PATH", hostLib.parent)
            // Captures d'écran écrites dans app/build/screenshots/ (ScreenshotTest).
            it.systemProperty("roborazzi.test.record", "true")
            // Robolectric (android.system.SystemCleaner) sur le JDK 21.
            it.jvmArgs("--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED")
        }
    }
}

dependencies {
    // UniFFI s'appuie sur JNA (version AAR, avec ses .so Android).
    implementation("${libs.jna.get()}@aar")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // Sur la JVM, JNA a besoin de sa version .jar (bibliothèque native pour la machine).
    testImplementation(libs.jna)
    // Captures d'écran des vrais écrans sur la JVM (Robolectric + Roborazzi).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

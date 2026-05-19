plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

val versionPropsFile = file("version.properties")
val versionProps = Properties()

if (!versionPropsFile.exists()) {
    versionProps["versionCode"] = "4"
    versionProps.store(FileOutputStream(versionPropsFile), "VerifyBlind Auto-generated Sequence")
}
versionProps.load(FileInputStream(versionPropsFile))

val currentVersionCode = versionProps["versionCode"].toString().toInt()

val currentVersionName = "1.0.15"

android {
    namespace = "com.verifyblind.mobile"
    compileSdk = 35

    val kimlikPropsFile = file("../verifyblind.properties")
    val kimlikProps = Properties()
    if (kimlikPropsFile.exists()) {
        kimlikProps.load(FileInputStream(kimlikPropsFile))
    }

    defaultConfig {
        applicationId = "com.verifyblind.mobile"
        minSdk = 26 // Android 8.0 (NFC support good)
        targetSdk = 35
        // Uygulamanın versiyon kodu (Sadece Google Play için önemlidir, her güncellemede 1 artırılmalıdır. Örn: 10, 11, 12...)
        versionCode = currentVersionCode
        // Uygulamanın kullanıcılara ve bizim API'ye görünen versiyon adı (Örn: "1.0.5", "1.0.6").
        versionName = currentVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Centralized Configurations
        // API_BASE_URL ve USE_LOCAL_API build type'a göre ayrılıyor:
        // debug → verifyblind.properties'e bakır (USE_LOCAL_API=true ise local Docker)
        // release → her zaman production URL, USE_LOCAL_API=false
        buildConfigField("String", "STORE_URL", "\"${kimlikProps.getProperty("STORE_URL") ?: ""}\"")
        buildConfigField("String", "CERT_PIN_1", "\"${kimlikProps.getProperty("CERT_PIN_1") ?: ""}\"")
        buildConfigField("String", "CERT_PIN_2", "\"${kimlikProps.getProperty("CERT_PIN_2") ?: ""}\"")
        buildConfigField("String", "DEVELOPER_PUBLIC_KEY", "\"${kimlikProps.getProperty("DEVELOPER_PUBLIC_KEY") ?: ""}\"")
    }

    buildTypes {
        debug {
            val useLocalApi = kimlikProps.getProperty("USE_LOCAL_API") == "true"
            val apiBaseUrl = if (useLocalApi)
                "http://192.168.1.113:5102/api/Verify/"
            else
                kimlikProps.getProperty("API_BASE_URL") ?: ""
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            buildConfigField("Boolean", "USE_LOCAL_API", "$useLocalApi")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"${kimlikProps.getProperty("API_BASE_URL") ?: ""}\"")
            buildConfigField("Boolean", "USE_LOCAL_API", "false")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0") // Debugging

    // Crypto
// BouncyCastle - version 1.72 is used because it's the last version that maintains
    // compatibility with JMRTD's getObject() while supporting Attestation verification.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.72")
    implementation("org.bouncycastle:bcprov-jdk18on:1.72")

    // CBOR parsing for AWS Nitro Attestation Document (COSE_Sign1 format)
    implementation("com.upokecenter:cbor:4.5.4")

    // NFC / Passport (JMRTD)
    implementation("net.sf.scuba:scuba-sc-android:0.0.23") {
        exclude(group = "org.bouncycastle")
    }
    implementation("org.jmrtd:jmrtd:0.7.35") {
        exclude(group = "org.bouncycastle")
    }

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // ML Kit & CameraX
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0")
    implementation("com.google.mlkit:face-detection:16.1.6") // Face Detection

    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("androidx.camera:camera-video:1.3.1") // Video Recording

    // Play Integrity (Attestation)
    implementation("com.google.android.play:integrity:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // TensorFlow Lite (Local AI)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // ViewModel + LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Room (Local DB)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // ---- Cloud Backup ----
    // Google Drive
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20231128-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }

    // Dropbox
    implementation("com.dropbox.core:dropbox-android-sdk:7.0.0")
}

android {
    androidResources {
        noCompress.add("tflite")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Bu ayar AAPT2'nin PNG dosyalarını sıkıştırmasını engeller,
            // böylece GitHub Actions'taki AAPT2 derleme hatalarını aşarız.
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.register("generateBuildInfo") {
    val assetsDir = file("src/main/assets")
    val outFile = assetsDir.resolve("build_info.json")
    outputs.file(outFile)
    inputs.property("versionCode", currentVersionCode)
    inputs.property("versionName", currentVersionName)
    doLast {
        val vc = inputs.properties["versionCode"] as Int
        val vn = inputs.properties["versionName"] as String
        val gitCommit: String = try {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().readLine()?.trim() ?: "unknown"
        } catch (e: Exception) { "unknown" }
        assetsDir.mkdirs()
        outFile.writeText(
            "{\n" +
            "  \"version_code\": $vc,\n" +
            "  \"version_name\": \"$vn\",\n" +
            "  \"git_commit\": \"$gitCommit\"\n" +
            "}"
        )
        println(">>> VerifyBlind: build_info.json yazıldı (commit=$gitCommit, build=$vc)")
    }
}

afterEvaluate {
    tasks.named("preBuild") { dependsOn("generateBuildInfo") }
}

import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

val versionPropsFile = file("version.properties")
val versionProps = Properties()

if (!versionPropsFile.exists()) {
    versionProps["versionCode"] = "4"
    versionProps.store(FileOutputStream(versionPropsFile), "VerifyBlind Auto-generated Sequence")
}
versionProps.load(FileInputStream(versionPropsFile))

val currentVersionCode = versionProps["versionCode"].toString().toInt()

val currentVersionName = "1.0.186"

android {
    namespace = "com.verifyblind.mobile"
    compileSdk = 36

    val kimlikPropsFile = file("../verifyblind.properties")
    val kimlikProps = Properties()
    if (kimlikPropsFile.exists()) {
        kimlikProps.load(FileInputStream(kimlikPropsFile))
    }

    defaultConfig {
        applicationId = "com.verifyblind.mobile"
        minSdk = 26 // Android 8.0 (NFC support good)
        targetSdk = 36
        // Uygulamanın versiyon kodu (Sadece Google Play için önemlidir, her güncellemede 1 artırılmalıdır. Örn: 10, 11, 12...)
        versionCode = currentVersionCode
        // Uygulamanın kullanıcılara ve bizim API'ye görünen versiyon adı (Örn: "1.0.5", "1.0.6").
        versionName = currentVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "tr")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Centralized Configurations
        // API_BASE_URL ve USE_LOCAL_API build type'a göre ayrılıyor:
        // debug → verifyblind.properties'e bakır (USE_LOCAL_API=true ise local Docker)
        // release → her zaman production URL, USE_LOCAL_API=false
        buildConfigField("String", "STORE_URL", "\"${kimlikProps.getProperty("STORE_URL") ?: ""}\"")
        buildConfigField("String", "DEVELOPER_PUBLIC_KEY", "\"${kimlikProps.getProperty("DEVELOPER_PUBLIC_KEY") ?: ""}\"")
        buildConfigField("String", "SENTRY_DSN", "\"${kimlikProps.getProperty("SENTRY_DSN") ?: ""}\"")
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
            // ⚠️ Bu dosyada İKİ tane release bloğu var (aşağıdaki ikinci `android { }`
            // bloğuna bak) ve sonra gelen kazanıyor. İkisini birden değiştir, yoksa
            // burada yaptığın değişiklik sessizce hiçbir işe yaramaz.
            isMinifyEnabled = true
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // ML Kit & CameraX
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0")
    implementation("com.google.mlkit:face-detection:16.1.6") // Face Detection

    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2") // Video Recording

    // Play Integrity (Attestation)
    implementation("com.google.android.play:integrity:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // TensorFlow Lite (Local AI) — migrated to LiteRT (Google's TF-Lite successor; classic
    // org.tensorflow:tensorflow-lite Maven coordinates are now Maven-relocated to LiteRT and
    // will never ship a 16KB-page-aligned .so). LiteRT 1.4.x keeps the exact same Java package/
    // class names (org.tensorflow.lite.Interpreter, org.tensorflow.lite.support.common.FileUtil)
    // so FaceEmbedder.kt needs NO import changes. tensorflow-lite-gpu removed — unused (no
    // GpuDelegate anywhere; Interpreter always runs CPU-only).
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-support-api:1.4.2")

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

    // Crash reporting (iOS Sentry paritesi)
    implementation("io.sentry:sentry-android:8.50.1")

    // Edge-to-edge: enableEdgeToEdge() (MainActivity/SplashActivity) requires activity 1.8.0+;
    // transitively resolved to 1.8.0 via appcompat before this — pinned explicitly for the fix.
    implementation("androidx.activity:activity-ktx:1.13.0")
}

android {
    androidResources {
        noCompress.add("tflite")
    }

    buildTypes {
        getByName("release") {
            // BELİRLEYİCİ olan bu — yukarıdaki release bloğundan SONRA çalışır ve onu ezer.
            // R8 açık, ancak obfuscation proguard-rules.pro içinde -dontobfuscate ile
            // kapatıldı: kod şeffaflığı (public kaynak + DEX hash doğrulaması) korunuyor,
            // shrink + optimize kazanılıyor. Kaynak küçültme (isShrinkResources) BİLİNÇLİ
            // olarak açılmadı — ayrı bir risk ekseni, ayrı adımda değerlendirilecek.
            isMinifyEnabled = true
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

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keys live outside the repo. Point MKTV_KEYSTORE_PROPS at a properties file
// (storeFile, storePassword, keyAlias, keyPassword); default ~/.mktvhub/keystore.properties.
// Without it, release builds are produced unsigned and debug builds are unaffected.
val keystoreProps = Properties().apply {
    val f = file(System.getenv("MKTV_KEYSTORE_PROPS") ?: "${System.getProperty("user.home")}/.mktvhub/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.mktvhub.mktv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mktvhub.mktv"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.3.2"
        buildConfigField("String", "DEFAULT_HOME_URL", "\"https://www.google.com\"")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("com.google.zxing:core:3.5.3")
    // TV home screen: Watch Next ("Play Next") cards and the mkTV channel (TvHome.kt).
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (the Android jar only has stubs); the app uses the platform copy.
    testImplementation("org.json:json:20240303")
}

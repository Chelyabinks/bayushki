import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Версия берётся из тега vX.Y.Z (в CI: -PappVersion=1.2.3). versionCode = X*10000 + Y*100 + Z.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.1.0"
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// Подпись: из переменных окружения (CI) или из keystore.properties (локально, НЕ в git).
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(key: String): String? = System.getenv(key) ?: signingProps.getProperty(key)

android {
    namespace = "com.sleepclub.app"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ru.bayushki.app"
        minSdk = 28
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "SERVER_URL", "\"${findProperty("serverUrl")}\"")
        buildConfigField("String", "GITHUB_REPO", "\"${findProperty("githubRepo")}\"")
    }

    signingConfigs {
        create("release") {
            val path = signing("KEYSTORE_FILE")
            if (path != null) {
                storeFile = rootProject.file(path)
                storePassword = signing("KEYSTORE_PASSWORD")
                keyAlias = signing("KEY_ALIAS")
                keyPassword = signing("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing("KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

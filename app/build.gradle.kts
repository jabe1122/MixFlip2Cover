import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is read from `keystore.properties` (git-ignored) or from environment variables
// (MF2C_STORE_FILE / MF2C_STORE_PASSWORD / MF2C_KEY_ALIAS / MF2C_KEY_PASSWORD) so CI can sign without
// committing secrets. When neither is present the release build falls back to the debug key.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    signingProps.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.trim()?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.jabe1122.mixflip2cover"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jabe1122.mixflip2cover"
        minSdk = 36
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "MF2C_STORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "MF2C_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MF2C_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "MF2C_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0")
    testImplementation("io.github.libxposed:api:101.0.1")
    testImplementation("junit:junit:4.13.2")
}

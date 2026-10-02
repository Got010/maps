plugins {
    alias(libs.plugins.southbound.android.application)
    alias(libs.plugins.southbound.android.compose)
}

// The release workflow passes -Psouthbound.version=<tag without the v>.
val appVersion = providers.gradleProperty("southbound.version").getOrElse("0.1.0")

// Release signing comes from CI secrets (see README). Without them, a local
// release build is unsigned and can't be installed.
val keystoreFile = providers.environmentVariable("SOUTHBOUND_KEYSTORE_FILE")

android {
    namespace = "app.southbound"

    defaultConfig {
        applicationId = "app.southbound"
        versionName = appVersion
        versionCode = versionCodeOf(appVersion)
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.isPresent) {
                storeFile = file(keystoreFile.get())
                storePassword = providers.environmentVariable("SOUTHBOUND_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SOUTHBOUND_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SOUTHBOUND_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.isPresent) signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":feature:explore"))
    implementation(project(":feature:settings"))
    implementation(libs.maplibre.android)
    implementation(libs.androidx.activity.compose)
}

/** 1.2.3 -> 10203. Android requires every update to have a higher versionCode. */
fun versionCodeOf(version: String): Int {
    val parts = version.split('.')
    require(parts.size == 3 && parts.all { it.toIntOrNull() in 0..99 }) {
        "Version must look like 1.2.3 (each part 0-99), got '$version'"
    }
    val (major, minor, patch) = parts.map(String::toInt)
    return major * 10_000 + minor * 100 + patch
}

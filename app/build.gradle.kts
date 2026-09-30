plugins {
    alias(libs.plugins.nysmaps.android.application)
    alias(libs.plugins.nysmaps.android.compose)
}

android {
    namespace = "org.nysmaps.app"

    defaultConfig {
        applicationId = "org.nysmaps"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":feature:explore"))
    implementation(libs.maplibre.android)
    implementation(libs.androidx.activity.compose)
}

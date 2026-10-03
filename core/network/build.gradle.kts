plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.network"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.userdata"
}

dependencies {
    // MiniJson, for the on-disk format.
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.southbound.android.library)
    alias(libs.plugins.southbound.android.compose)
}

android {
    namespace = "app.southbound.feature.search"
}

dependencies {
    api(project(":core:search"))
    api(project(":core:wifi"))
    implementation(project(":core:map"))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
}

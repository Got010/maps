plugins {
    alias(libs.plugins.southbound.android.library)
    alias(libs.plugins.southbound.android.compose)
}

android {
    namespace = "app.southbound.feature.directions"
}

dependencies {
    api(project(":core:routing"))
    implementation(project(":core:map"))
    implementation(project(":core:search"))
    implementation(project(":core:states"))
    implementation(libs.androidx.compose.material.icons.extended)
}

plugins {
    alias(libs.plugins.southbound.android.library)
    alias(libs.plugins.southbound.android.compose)
}

android {
    namespace = "app.southbound.feature.transit"
}

dependencies {
    api(project(":core:transit"))
    implementation(project(":core:map"))
    implementation(project(":feature:directions"))
    implementation(libs.androidx.compose.material.icons.extended)
}

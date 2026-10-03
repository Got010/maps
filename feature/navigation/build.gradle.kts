plugins {
    alias(libs.plugins.southbound.android.library)
    alias(libs.plugins.southbound.android.compose)
}

android {
    namespace = "app.southbound.feature.navigation"
}

dependencies {
    implementation(project(":core:map"))
    implementation(project(":core:routing"))
    implementation(project(":core:voice"))
    implementation(libs.ferrostar.ui.compose)
    implementation(libs.androidx.compose.material.icons.extended)
}

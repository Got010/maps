plugins {
    alias(libs.plugins.southbound.android.library)
    alias(libs.plugins.southbound.android.compose)
}

android {
    namespace = "app.southbound.feature.explore"
}

dependencies {
    implementation(project(":core:map"))
    implementation(project(":core:packs"))
    implementation(project(":core:routing"))
    implementation(project(":core:search"))
    implementation(project(":core:transit"))
    implementation(project(":core:network"))
    implementation(project(":core:voice"))
    implementation(project(":feature:directions"))
    implementation(project(":feature:navigation"))
    implementation(project(":feature:search"))
    implementation(project(":feature:transit"))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}

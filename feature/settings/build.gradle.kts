plugins {
    alias(libs.plugins.southbound.android.library)
    alias(libs.plugins.southbound.android.compose)
}

android {
    namespace = "app.southbound.feature.settings"
}

dependencies {
    implementation(project(":core:export"))
    implementation(project(":core:routing"))
    implementation(project(":core:voice"))
    implementation(project(":core:network"))
    implementation(project(":core:updates"))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}

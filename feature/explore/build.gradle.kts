plugins {
    alias(libs.plugins.nysmaps.android.library)
    alias(libs.plugins.nysmaps.android.compose)
}

android {
    namespace = "org.nysmaps.feature.explore"
}

dependencies {
    implementation(project(":core:map"))
    implementation(project(":core:packs"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}

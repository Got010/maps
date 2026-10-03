plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.updates"
}

dependencies {
    api(project(":core:packs"))
    api(project(":core:network"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
}

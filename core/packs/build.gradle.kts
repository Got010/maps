plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.packs"
}

dependencies {
    // MiniJson, for the release manifest.
    api(project(":core:network"))
    api(libs.kotlinx.coroutines.android)
}

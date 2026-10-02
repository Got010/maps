plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.routing"
}

dependencies {
    // Ferrostar's navigation engine, and its notification for the foreground service.
    api(libs.ferrostar.core)
    implementation(libs.ferrostar.ui.compose)
    // Valhalla on the device.
    implementation(libs.valhalla.mobile)
    implementation(libs.valhalla.models.config)
    implementation(libs.kotlinx.coroutines.android)
}

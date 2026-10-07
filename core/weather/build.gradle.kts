plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.weather"
}

dependencies {
    // NetworkGate and MiniJson: every request is a whole-state pull through the gate.
    api(project(":core:network"))
    // GeoPoint, Geo and RouteTraffic.remaining, shared with the traffic alerts.
    api(project(":core:traffic"))
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.traffic"
}

dependencies {
    // NetworkGate and MiniJson: every feed is fetched whole through the gate.
    api(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.feature.car"
}

dependencies {
    // The trip, as plain values (TripBroadcast).
    implementation(project(":core:routing"))
    // The Car App Library: an AndroidX library that draws templates on the car screen through the
    // Android Auto host app. It has no Google Play Services dependency (build-logic fails the
    // build if one ever appears), and the phone app works the same without Android Auto.
    implementation(libs.androidx.car.app)
    implementation(libs.kotlinx.coroutines.android)
}

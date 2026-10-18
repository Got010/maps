plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.wifi"
}

dependencies {
    // LatLon, NearbyBox, OpeningHours, Place and the distance maths; the search pack's reader stays in core:search.
    api(project(":core:search"))
    // SQLite for the free Wi-Fi pack, the same bundled build the search and transit packs use.
    implementation(libs.androidx.sqlite.bundled)
}

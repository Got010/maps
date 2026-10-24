plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.road"
}

dependencies {
    // LatLon, NearbyBox, Place and the distance maths; the search pack's reader stays in core:search.
    api(project(":core:search"))
    // MiniJson, to read a spot's small `extra` object.
    implementation(project(":core:network"))
    // SQLite for the Road pack, the same bundled build the search, transit and Wi-Fi packs use.
    implementation(libs.androidx.sqlite.bundled)
}

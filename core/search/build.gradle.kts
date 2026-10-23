plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.search"
}

dependencies {
    // The state lines a route crosses (RouteAlerts.crossings); Crossing and StateOutlines are in its signature.
    api(project(":core:states"))
    // SQLite with FTS5 compiled in, for the search pack's trigram index.
    implementation(libs.androidx.sqlite.bundled)
}

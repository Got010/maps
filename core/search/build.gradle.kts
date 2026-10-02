plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.search"
}

dependencies {
    // SQLite with FTS5 compiled in, for the search pack's trigram index.
    implementation(libs.androidx.sqlite.bundled)
}

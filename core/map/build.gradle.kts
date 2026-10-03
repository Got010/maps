plugins {
    alias(libs.plugins.southbound.android.library)
    alias(libs.plugins.southbound.android.compose)
}

android {
    namespace = "app.southbound.core.map"
    // Bundle styles/ (light.json, dark.json, glyphs/) as assets.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("styles"))
}

dependencies {
    // MiniJson, to rewrite the bundled style for the installed basemaps.
    implementation(project(":core:network"))
    implementation(libs.maplibre.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}

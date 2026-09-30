plugins {
    alias(libs.plugins.nysmaps.android.library)
    alias(libs.plugins.nysmaps.android.compose)
}

android {
    namespace = "org.nysmaps.core.map"
    // Bundle styles/ (light.json, dark.json, glyphs/) as assets.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("styles"))
}

dependencies {
    implementation(libs.maplibre.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}

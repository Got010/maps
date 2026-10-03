plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.voice"
    // The Piper voice, unpacked by scripts/fetch-voice.sh.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("voice-assets"))
}

dependencies {
    api(libs.ferrostar.core)
    implementation(libs.sherpa.onnx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.states"
}

dependencies {
    // MiniJson, to read the two data files in the module's resources.
    implementation(project(":core:network"))
}

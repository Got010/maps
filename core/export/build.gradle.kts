plugins {
    alias(libs.plugins.southbound.android.library)
}

android {
    namespace = "app.southbound.core.export"
}

dependencies {
    // The types the bundle holds appear in this module's own API (BackupData), so callers see them too.
    api(project(":core:userdata"))
    api(project(":core:network"))
    api(project(":core:routing"))
    api(project(":core:voice"))
    implementation(project(":core:packs"))
    implementation(project(":core:updates"))
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.southbound.android.library)
    alias(libs.plugins.southbound.android.compose)
}

android {
    namespace = "app.southbound.feature.explore"
}

dependencies {
    implementation(project(":core:export"))
    implementation(project(":core:map"))
    implementation(project(":core:packs"))
    implementation(project(":core:routing"))
    implementation(project(":core:search"))
    implementation(project(":core:states"))
    implementation(project(":core:transit"))
    implementation(project(":core:traffic"))
    implementation(project(":core:weather"))
    implementation(project(":core:wifi"))
    implementation(project(":core:road"))
    implementation(project(":core:userdata"))
    implementation(project(":core:network"))
    implementation(project(":core:updates"))
    implementation(project(":core:voice"))
    implementation(project(":feature:directions"))
    implementation(project(":feature:navigation"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:transit"))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}

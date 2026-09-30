import com.android.build.api.dsl.CommonExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal const val COMPILE_SDK = 36
internal const val MIN_SDK = 31
internal const val TARGET_SDK = 36

/** CLAUDE.md hard constraint: no Play Services, Firebase, analytics or crash SDKs. */
private val BANNED_GROUPS = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.android.play",
    "com.crashlytics.sdk.android",
    "io.sentry",
    "com.bugsnag",
    "io.fabric.sdk.android",
)

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** SDK levels, Java 17 and unit-test setup shared by every Android module. */
internal fun Project.configureKotlinAndroid(android: CommonExtension<*, *, *, *, *, *>) {
    android.apply {
        compileSdk = COMPILE_SDK
        defaultConfig.minSdk = MIN_SDK
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        testOptions.unitTests.isReturnDefaultValues = true
    }
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    dependencies {
        add("testImplementation", libs.findLibrary("junit").get())
    }
    banTrackingDependencies()
}

/** Fails the build if any configuration, even transitively, resolves a banned group. */
private fun Project.banTrackingDependencies() {
    val projectPath = path
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val group = requested.group
            if (BANNED_GROUPS.any { group == it || group.startsWith("$it.") }) {
                throw GradleException(
                    "$group:${requested.name} is banned by CLAUDE.md (no Google Play Services, " +
                        "Firebase, analytics or crash SDKs). Requested in $projectPath.",
                )
            }
        }
    }
}

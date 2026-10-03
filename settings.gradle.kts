pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (the bundled offline voice) isn't on Maven Central;
        // scripts/fetch-voice.sh downloads its pinned AAR here.
        maven {
            url = uri("third-party/m2")
            content { includeGroup("com.k2fsa") }
        }
    }
}

if (!file("third-party/m2/com/k2fsa").isDirectory) {
    throw GradleException("The offline voice isn't downloaded yet. Run scripts/fetch-voice.sh first.")
}

rootProject.name = "southbound"

include(":app")

include(
    ":core:network",
    ":core:map",
    ":core:routing",
    ":core:voice",
    ":core:search",
    ":core:transit",
    ":core:traffic",
    ":core:packs",
    ":core:updates",
    ":core:userdata",
)

include(
    ":feature:explore",
    ":feature:search",
    ":feature:directions",
    ":feature:navigation",
    ":feature:transit",
    ":feature:settings",
)

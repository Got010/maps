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
    }
}

rootProject.name = "nys-maps"

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

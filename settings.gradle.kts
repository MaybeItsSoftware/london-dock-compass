pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "London Dock Compass"

// :app is the Wear OS app -- the original, and still the point of the project. It keeps the bare
// name because the release pipeline addresses it by path. :mobile is the phone companion, and
// :core is everything the two agree about.
include(":core")
include(":app")
include(":mobile")

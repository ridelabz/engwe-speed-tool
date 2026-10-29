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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "engwe-speed-tool"

// The pure-Kotlin modules need only a JDK. `-PjvmOnly` (or `-PjvmOnly=true`) leaves out the Android
// modules, for machines without the Android build tools.
include(":protocol", ":session", ":reporting")
val jvmOnly = providers.gradleProperty("jvmOnly").map { it != "false" }.getOrElse(false)
if (!jvmOnly) {
    include(":app", ":ble-android")
}

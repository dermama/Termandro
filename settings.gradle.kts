pluginManagement {
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

rootProject.name = "DeckOS"
include(":app")
include(":terminal")

include(":lorie")
project(":lorie").projectDir = file("sources/termux-x11/lorie")

include(":shell-loader:stub")
project(":shell-loader:stub").projectDir = file("sources/termux-x11/shell-loader/stub")

pluginManagement {
    repositories {
        // Google repository (Android plugin)
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        google()
        // Maven Central
        mavenCentral()
        // Gradle plugin portal
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
rootProject.name = "GlassFalcon"
include(":app")
include(":sdk")
include(":lsposed")

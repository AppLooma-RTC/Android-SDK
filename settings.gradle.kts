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
        maven { url = uri("https://jitpack.io") }
        // Last, so a locally built transport core is only a fallback before it reaches Central.
        mavenLocal()
    }
}

rootProject.name = "applooma-rtc-android"
include(":applooma-rtc")
include(":applooma-uikit")

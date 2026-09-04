pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Khai báo version tập trung ở đây - tránh xung đột giữa buildscript + pluginManagement
    plugins {
        id("com.android.application") version "8.3.2" apply false
        id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "DataUsageMonitor"
include(":app")

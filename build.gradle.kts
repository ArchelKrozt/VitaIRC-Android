plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}

// Build output lives outside iCloud Drive (see README: building)
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.vitairc-android-build/${project.name}"))
}

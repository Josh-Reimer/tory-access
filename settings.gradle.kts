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
        // Guardian Project's tor-android binary (libtor.so per ABI), not on Maven Central.
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
        // Termux's terminal-emulator library (pure-Java VT100/xterm state machine).
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.termux.termux-app") }
        }
    }
}

rootProject.name = "tory-access"

include(":app")

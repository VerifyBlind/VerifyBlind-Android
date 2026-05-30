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
        maven { url = uri("https://jitpack.io") } // For JMRTD or other libs
        // JMRTD often requires specific repos or local libs, but let's try Maven Central/JCenter/Scuba first
        // Check JMRTD website: usually requires 'https://www.jmrtd.org/maven'? No, it's often imported via JARs or specific repos.
        // We'll use a common fork or repackage if needed, or assume manual JAR import.
        // For now, let's look for a standard open source alternative or instructions.
    }
}

rootProject.name = "VerifyBlind"
include(":app")

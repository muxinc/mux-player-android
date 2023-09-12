pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    mavenLocal()
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven { url = uri("https://muxinc.jfrog.io/artifactory/default-maven-release-local") }
    google()
    mavenCentral()
    mavenLocal()
  }
}

rootProject.name = "Mux Video Media3"
include(":app")
include(":media3-mux")

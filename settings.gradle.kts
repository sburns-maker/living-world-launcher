pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } } }
rootProject.name = "LivingWorldLauncher"
include(":app")

// Local source checkout is pinned in scripts/fetch-mmslib.sh and CI.
val mmsSource = file("vendor/mmslib/library")
if (mmsSource.isDirectory) {
  include(":mmslib")
  project(":mmslib").projectDir = mmsSource
}

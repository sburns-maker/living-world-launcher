import java.io.File
import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

// GitHub's hosted runners are fresh VMs. A debug keystore generated on each
// runner causes UPDATE_INCOMPATIBLE on the user's Android device.
// This test keystore is injected from a GitHub Actions repository secret.
// Never commit the key or use it to sign a public/production release.
val testSigningDirectory = providers.environmentVariable("LIVING_WORLD_TEST_SIGNING_DIR").orNull
val testSigning = Properties()
if (!testSigningDirectory.isNullOrBlank()) {
  val propertiesFile = File(testSigningDirectory, "signing.properties")
  require(propertiesFile.isFile) { "Missing stable test signing.properties" }
  propertiesFile.inputStream().use { testSigning.load(it) }
}
val ciRun = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()

android {
  namespace = "com.livingworld.launcher"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.livingworld.launcher"
    minSdk = 28
    targetSdk = 35
    versionCode = ciRun ?: 1
    versionName = if (ciRun != null) "0.1.0-test.$ciRun" else "0.1.0-local"
  }

  if (!testSigningDirectory.isNullOrBlank()) {
    signingConfigs {
      create("stableDevelopment") {
        storeFile = File(testSigningDirectory, testSigning.getProperty("storeFile", "test-signing.p12"))
        storeType = "PKCS12"
        storePassword = testSigning.getProperty("storePassword")
        keyAlias = testSigning.getProperty("keyAlias")
        keyPassword = testSigning.getProperty("keyPassword")
        require(storeFile?.isFile == true) { "Stable development signing keystore missing" }
      }
    }
    buildTypes {
      getByName("debug") {
        signingConfig = signingConfigs.getByName("stableDevelopment")
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

if (project.findProject(":mmslib") != null) {
  dependencies { implementation(project(":mmslib")) }
}

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.livingworld.launcher"; compileSdk = 35
 defaultConfig { applicationId = "com.livingworld.launcher"; minSdk = 28; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}


// JitPack builds the upstream Apache-2.0 Fossify MMS source.
dependencies { implementation("com.github.FossifyOrg:mmslib:main-SNAPSHOT") }

plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.mux.player.media3"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.mux.player.media3"
    minSdk = 23
    //noinspection EditedTargetSdkVersion
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
    multiDexEnabled = true
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("boolean", "SHOULD_REPORT_INSTRUMENTATION_TEST_EVENTS_TO_SERVER", "true")
    buildConfigField("String", "INSTRUMENTATION_TEST_ENVIRONMENT_KEY", "\"YOUR_KEY_HERE\"")
  }

  buildFeatures {
    buildConfig = true
  }

  buildTypes {
    debug {
      isMinifyEnabled = false
    }
  }

  testOptions {
    // Changes the directory where Gradle saves test reports. By default, Gradle saves test reports
    // in the path_to_your_project/module_name/build/outputs/reports/ directory.
    // '$rootDir' sets the path relative to the root directory of the current project.
    reportDir = "./automated_test_results/reports"
    // Changes the directory where Gradle saves test results. By default, Gradle saves test results
    // in the path_to_your_project/module_name/build/outputs/test-results/ directory.
    // '$rootDir' sets the path relative to the root directory of the current project.
    resultsDir = "./automated_test_results/results"
  }

  sourceSets {
    getByName("androidTest") {
      // Important, can't get asset file in instrumentation test without this
      assets.directories += "src/main/assets"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(fileTree("libs") { include("*.jar") })
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.navigation.fragment)
  implementation(libs.androidx.navigation.ui)

  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.rules)
  // Optional -- Hamcrest library
  androidTestImplementation(libs.hamcrest.library)
  // Optional -- UI testing with Espresso
  androidTestImplementation(libs.androidx.test.espresso.core)
  // Optional -- UI testing with UI Automator
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.ext.junit)

  api(libs.checker.qual)
  // Automated tests should always test the local module and not the maven dependency.
  implementation(project(":library"))
}

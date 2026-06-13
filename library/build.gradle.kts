import java.time.Year

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.mux.android.distribution)
}

android {
  namespace = "com.mux.player"
  compileSdk = 37

  defaultConfig {
    minSdk = 23

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")

    multiDexEnabled = true
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
}

muxDistribution {
  devVersion(versionFromCommitHash("dev-"))
  releaseVersion(versionFromTag())
  artifactIds(just("android"))
  groupIds(just("com.mux.player"))
  publicReleaseIf(releaseIfCmdFlag("publicRelease"))

  packageDocs(releaseOnTag().call())
  publishIf { it.contains("release", ignoreCase = true) }
  artifactoryConfig {
    contextUrl = "https://muxinc.jfrog.io/artifactory/"
    releaseRepoKey = "default-maven-release-local"
    devRepoKey = "default-maven-local"
  }

  dokkaConfig {
    moduleName = "Mux Player SDK for Android"
    footer = "(c) " + Year.now() + " Mux, Inc. Have questions or need help?" +
            " Contact support@mux.com"
  }

  pom {
    description.set("The Mux Player SDK for Android. It's just media3 with some helpful tools")
    inceptionYear.set("2022")
    url.set("https://github.com/muxinc/mux-stats-sdk-media3")
    organization {
      name.set("Mux, Inc")
      url.set("https://www.mux.com")
    }
    developers {
      developer {
        email.set("support@mux.com")
        name.set("The player and sdks team @mux")
        organization.set("Mux, inc")
      }
    }
  }
}

tasks.register("dokkaHtml") {
  group = "documentation"
  description = "Alias for dokkaGenerateHtml (Dokka v2)."
  dependsOn("dokkaGenerateHtml")
}

dependencies {
  api(libs.media3.common)
  api(libs.media3.exoplayer)
  api(libs.media3.ui)
  api(libs.media3.exoplayer.hls)
  api(libs.media3.cast)

  api(libs.mux.data.media3)

  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.mockk)
  testImplementation(libs.robolectric)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  id("com.android.application") version "8.1.3" apply false
  id("org.jetbrains.kotlin.android") version "1.8.0" apply false
  id("com.android.library") version "8.1.3" apply false
  id("com.mux.gradle.android.mux-android-distribution") version "1.1.2" apply false
}

allprojects {
  ext {
    set("muxDataVersion", "dev-feat-add-device-b5dce4d")
  }
}

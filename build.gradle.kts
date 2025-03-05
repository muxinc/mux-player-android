plugins {
  id("com.android.application") version "8.8.2" apply false
  id("org.jetbrains.kotlin.android") version "2.1.10" apply false
  id("com.android.library") version "8.8.2" apply false
  id("com.mux.gradle.android.mux-android-distribution") version "1.3.0" apply false
}

allprojects {
  ext {
    set("muxDataVersion", "1.6.2")
  }
}
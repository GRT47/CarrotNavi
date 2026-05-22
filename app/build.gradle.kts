plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.tmapbridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.tmapbridge"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // JSON parsing
  implementation("com.google.code.gson:gson:2.10.1")
  
  // Tmap SDK dependencies
  implementation("com.tmapmobility.tmap:tmap-ui-sdk:1.0.0.0146")
  implementation("com.google.flatbuffers:flatbuffers-java:1.11.0")
  implementation("com.squareup.retrofit2:retrofit:2.9.0")
  implementation("com.squareup.retrofit2:converter-gson:2.9.0") {
      exclude(group = "com.google.code.gson", module = "gson")
  }
  implementation("com.squareup.retrofit2:adapter-rxjava2:2.9.0")
  implementation("com.squareup.okhttp3:okhttp:5.3.2")
  implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")
  implementation("com.google.android.exoplayer:exoplayer:2.19.1")
  implementation("com.google.android.exoplayer:exoplayer-core:2.19.1") {
      exclude(group = "com.google.guava", module = "guava")
  }
  implementation("com.google.guava:guava:33.5.0-jre")
  implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
  implementation("com.github.bumptech.glide:glide:4.13.2")
  implementation("com.google.android.gms:play-services-location:21.3.0")
  implementation("com.airbnb.android:lottie:3.0.7")
  implementation("com.squareup.okio:okio:1.17.6")
}

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.example.tv"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.example.tv"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"
  }
  signingConfigs {
    create("release") {
      // long: 正式版始终使用独立签名；密码由环境或本机钥匙串注入，缺失时让签名校验失败，不能回退到调试证书。
      storeFile = file(
        providers.environmentVariable("TVLIVE_STORE_FILE")
          .getOrElse("${System.getProperty("user.home")}/.android/tvlive-release.jks"),
      )
      storeType = "PKCS12"
      storePassword = providers.environmentVariable("TVLIVE_STORE_PASSWORD").orNull
      keyAlias = providers.environmentVariable("TVLIVE_KEY_ALIAS").getOrElse("tvlive")
      keyPassword = providers.environmentVariable("TVLIVE_KEY_PASSWORD").orNull ?: storePassword
    }
  }
  buildTypes {
    getByName("release") {
      // long: 发布和验收使用同一份经过 R8 优化的 APK，确保 JS 桥接和遥控器流程在混淆后仍可正常工作。
      isDebuggable = false
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
  androidResources.ignoreAssetsPattern = ".DS_Store:!CVS:!*.scc:.*:<dir>_*:!*.bak:!*.swp:*~"
}

kotlin { jvmToolchain(17) }

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
  implementation(composeBom)
  implementation("androidx.core:core-ktx:1.18.0")
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation("androidx.compose.ui:ui-tooling")
  testImplementation("junit:junit:4.13.2")
}

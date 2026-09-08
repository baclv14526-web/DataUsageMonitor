plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "com.datamonitor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.datamonitor.app"
        minSdk        = 28
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
                ?: "${projectDir}/keystore/release.keystore"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile     = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "changeit123"
                keyAlias      = System.getenv("KEY_ALIAS")          ?: "datamonitor"
                keyPassword   = System.getenv("KEY_PASSWORD")        ?: "changeit123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile?.exists() == true) rel
                            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    // Loại bỏ resource không dùng để giảm dung lượng APK
    androidResources {
        // Chỉ giữ ngôn ngữ vi và en
        localeFilters += listOf("vi", "en")
    }

    // Tên APK output rõ ràng
    applicationVariants.all {
        val v = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "DataMonitor-v${v.versionName}-${v.buildType.name}.apk"
        }
    }
}

dependencies {
    // Chỉ giữ dependency thực sự dùng
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Coroutines — dùng trong service
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Đã xóa: constraintlayout (không dùng), lifecycle-service (không dùng),
    //         activity-ktx (không dùng feature gì đặc biệt)
}

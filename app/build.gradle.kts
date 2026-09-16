plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "com.datamonitor.app"
    compileSdk = 34

    defaultConfig {
        applicationId          = "com.datamonitor.app"
        minSdk                 = 28
        targetSdk              = 34
        versionCode            = 1
        versionName            = "1.0"
        // Chỉ đóng gói ngôn ngữ vi + en → bỏ 80+ locale thừa từ Material/AppCompat
        // resourceConfigurations tương thích mọi phiên bản AGP (thay cho localeFilters)
        resourceConfigurations += listOf("vi", "en")
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

    // Đặt tên APK output rõ ràng, không có "unsigned" trong tên
    applicationVariants.all {
        val v = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "DataMonitor-v${v.versionName}-${v.buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

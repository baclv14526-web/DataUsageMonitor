plugins {
    // Không cần khai báo version vì đã khai báo trong settings.gradle.kts pluginManagement.plugins
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.datamonitor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.datamonitor.app"
        minSdk = 28          // Android 9 trở lên
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Đọc từ env (CI) hoặc local.properties (build local)
            // Fallback path đúng: app/keystore/ (trước sai là project.rootDir/keystore/)
            val ksPath = System.getenv("KEYSTORE_PATH")
                ?: "${projectDir}/keystore/release.keystore"
            val ksPassword = System.getenv("KEYSTORE_PASSWORD") ?: "changeit123"
            val ksAlias = System.getenv("KEY_ALIAS") ?: "datamonitor"
            val ksKeyPassword = System.getenv("KEY_PASSWORD") ?: "changeit123"

            val ksFile = file(ksPath)
            // Chỉ gán storeFile nếu file thực sự tồn tại để tránh lỗi cấu hình Gradle
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            } else {
                println("⚠️  WARNING: Keystore không tìm thấy tại $ksPath")
                println("   Build release sẽ dùng debug signing (APK vẫn có thể cài nhưng không khuyến nghị cho production)")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Dùng release signingConfig nếu keystore tồn tại, không thì dùng debug
            val releaseConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseConfig.storeFile?.exists() == true) {
                releaseConfig
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // Đặt tên APK output rõ ràng, không có "unsigned" trong tên
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "DataMonitor-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
}

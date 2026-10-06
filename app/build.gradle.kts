plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

@Suppress("UnstableApiUsage") android {
    namespace = "com.jobeen.ime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jobeen.ime"
        minSdk = 24
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 49
        versionName = "1.0.991"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DENABLE_LOGGING=OFF"
                arguments += "-DALSO_LOG_TO_STDERR=OFF"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Jime 专用持久签名：VM 重置会丢 /root/.android 下的 debug key，
    // 为避免签名变化导致无法覆盖安装，所有构建统一用此 key。
    // 密码放在 ~/.gradle/gradle.properties（JIME_*），不进仓库；没有配置时回退默认 debug 签名。
    signingConfigs {
        create("jimeStable") {
            val ksPath = project.findProperty("JIME_STORE_FILE") as String?
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = project.findProperty("JIME_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("JIME_KEY_ALIAS") as String?
                keyPassword = project.findProperty("JIME_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        debug {
            val ksPath = project.findProperty("JIME_STORE_FILE") as String?
            if (ksPath != null && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("jimeStable")
            }
        }
        release {
            // 与已发布版本同一把 key，保证覆盖升级；无配置时回退默认 debug 签名
            val ksPath = project.findProperty("JIME_STORE_FILE") as String?
            if (ksPath != null && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("jimeStable")
            }
            // R8 第二步：2026-10-01 开启 isShrinkResources（资源均为静态引用，无动态 getIdentifier）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/libs")
            assets {
                directories.add("src/main/assets")
            }
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    // implementation(libs.tokenizer)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.zxing.android.embedded)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.systemservices)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

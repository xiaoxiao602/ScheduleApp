plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.gzuschedule.app.flutter"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.gzuschedule.app.flutter"
        // ⚠️ 与原 Android 版一致：minSdk 26（Android 8.0）
        minSdk = 26
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // ⚠️⚠️ ABI 过滤 —— 只保留 arm64-v8a。
        //
        //   `flutter build --target-platform android-arm64` 只管 Flutter 自己的
        //   libflutter.so / libapp.so，**第三方原生库仍会打全三个架构**：
        //     lib/x86_64/libsqlite3.so        1.55 MB  ← 模拟器专用
        //     lib/armeabi-v7a/libsqlite3.so   1.52 MB  ← 2015 年前的老手机
        //     lib/x86_64/libdartjni.so        0.12 MB
        //     lib/armeabi-v7a/libdartjni.so   0.08 MB
        //     lib/*/libdatastore_shared_counter.so 0.02 MB
        //   → 白装 3.29 MB
        //
        //   在这里过滤后，APK 从 24.3 MB → 约 21 MB。
        //
        // ⚠️ 只在真机（小米13，arm64）上安装 → 不会有兼容问题。
        //    若以后要给 32 位老手机用，删掉这段即可。
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // 自用，暂用 debug 签名
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // ⚠️ ADR-086：去掉产物文件名里的 "-debug" 后缀，
    //    统一命名为「广软课程表-VERSION.apk」（与原 Android 版一致）。
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "广软课程表-${versionName}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // flutter_local_notifications 要求 core library desugaring（AAR metadata 强校验）。
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}

flutter {
    source = "../.."
}

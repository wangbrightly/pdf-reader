plugins {
    id("com.android.application")
}

android {
    namespace = "app.pdfreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pdfreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // PDF 文字提取层：见 SELECTION.md 及后续调研记录。
    // 原计划用 MuPDF（mupdf-android-fitz），但调研确认它没有官方发布到
    // Maven Central / JitPack 的预编译 aar——官方仓库要求把源码（含 libmupdf
    // 子模块）递归 clone 下来，用 NDK/CMake 自己编译 native 库；能找到的
    // Maven Central 坐标（net.timelegend.mupdf:fitz）是第三方个人 fork 重新
    // 发布的二进制，非 Artifex 官方，native 代码的供应链信任度不足。
    // 因此改用次选方案 PdfBox-Android：纯 Java/Kotlin 实现，无 native 库，
    // Maven Central 有现成坐标，Apache 2.0 许可证，编译风险低得多。
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
}

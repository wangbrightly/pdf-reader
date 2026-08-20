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

    testOptions {
        unitTests {
            // Robolectric 需要能加载 AndroidManifest.xml / resources，
            // 否则 RuntimeEnvironment 初始化会报找不到清单文件。
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // 文字/图片按需加载（RecyclerView 窗口式重构，见
    // /Users/mac/.claude/plans/fizzy-snuggling-cloud.md）第 3 步用——条目粒度=页，
    // 只创建/保留屏幕附近的 ViewHolder，翻远的自动回收，是根治 4232 页文档
    // OutOfMemoryError（NOTES.md #21）必需的基础设施。1.3.2 是稳定版本。
    implementation("androidx.recyclerview:recyclerview:1.3.2")

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

    // 抽取层需要 Context（PDFBoxResourceLoader.init(context)），纯 JVM 单元测试
    // 拿不到真实 Android Context，用 Robolectric 在 JVM 上模拟一个。
    // 版本：4.16.1，2026-01-21 发布（已核实 GitHub Releases + Maven Central 均有该坐标）。
    // 选它而非更早的 4.15.x，是因为 4.16 这一支才开始支持 compileSdk 36（Android
    // Baklava）——本项目 compileSdk = 36，用旧版本会在初始化时报 SDK 不支持。
    // 4.16 系列要求跑测试的 JDK ≥ 21（本机走 Homebrew openjdk 26，满足要求）。
    testImplementation("org.robolectric:robolectric:4.16.1")
}

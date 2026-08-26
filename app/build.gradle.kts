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
        versionCode = 2
        versionName = "0.2.0"

        // 2026-08-26 新增：[Jpeg2000Decoder] 依赖 native .so，Robolectric（纯桌面
        // JVM，无法加载 Android ABI 的 ELF 二进制）测不了，必须用 instrumentation
        // test（跑在真机/模拟器的真实 Android 运行时），见
        // /Users/mac/.claude/plans/iridescent-foraging-floyd.md 阶段 B 第 4 步。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // 2026-08-26 POC（见 /Users/mac/.claude/plans/iridescent-foraging-floyd.md）：
    // JPX/JPEG2000 图片解码，JP2ForAndroid（OpenJPEG 2.5.4 的 JNI 封装，个人
    // 维护者重新发布到 Maven Central 的坐标，不是 Thales 官方渠道，原坐标
    // com.gemalto.jp2 已随 JCenter 关停失效，见 NOTES.md #43）。先用 1.0.5
    // （minCompileSdk=1，比 1.1.0 的 minCompileSdk=37 风险低）验证能否正常
    // 构建+真机解码，POC 通过再决定是否正式接入、要不要换 1.1.0。
    implementation("io.github.michaldvorak-gemalto:jp2-android:1.0.5")

    testImplementation("junit:junit:4.13.2")

    // 只在测试范围（不打进 APK）——用来交叉验证 [Jbig2GenericRegionDecoder]（自己
    // 手写的 JBIG2 通用区域解码器，见该类 KDoc 完整背景）写得对不对。这个库本身
    // 在 Android 运行时用不了（依赖 javax.imageio.stream，NOTES.md #27 装机验证
    // 过两次），但纯 JVM 单元测试（Robolectric 跑在桌面 JVM 上，不是 Android
    // 运行时）没有这个限制，可以正常加载——用它已经证明正确的解码逻辑当"标准
    // 答案"，喂同一份数据给自己写的解码器，比对结果是否一致，比"我自己觉得抄对了"
    // 更可靠。
    testImplementation("org.apache.pdfbox:jbig2-imageio:3.0.5")

    // 交叉验证新写的 [JpegDecoder]（自己手写、支持 CMYK 的 JPEG 解码器）本来想
    // 用 TwelveMonkeys 的 imageio-jpeg 插件当参考实现（标准 JDK 自带的
    // javax.imageio 不支持 4 分量 CMYK JPEG，这正是这次要解决的问题本身）——
    // 试过之后放弃了：喂一张已知纯黑（CMYK 全部拉满 C/M/Y、K=0）的最小测试图，
    // 这个库解出 (44,48,49)，不是预期的 (0,0,0)，偏差量级不是"合理的有损压缩
    // 误差"，是这个库自己在处理这类极端 CMYK 组合时有问题（没有深挖具体原因，
    // 重要的是不能拿一个自己都有已知偏差的实现当标准答案）。改成用 Python
    // Pillow（底层 libjpeg-turbo）离线预先解码成 PNG 存进测试 fixture，见
    // [app.pdfreader.extract.JpegDecoderCrossValidationTest] KDoc 完整背景，
    // 所以这里不需要额外的测试期第三方 JPEG 解码依赖。

    // 抽取层需要 Context（PDFBoxResourceLoader.init(context)），纯 JVM 单元测试
    // 拿不到真实 Android Context，用 Robolectric 在 JVM 上模拟一个。
    // 版本：4.16.1，2026-01-21 发布（已核实 GitHub Releases + Maven Central 均有该坐标）。
    // 选它而非更早的 4.15.x，是因为 4.16 这一支才开始支持 compileSdk 36（Android
    // Baklava）——本项目 compileSdk = 36，用旧版本会在初始化时报 SDK 不支持。
    // 4.16 系列要求跑测试的 JDK ≥ 21（本机走 Homebrew openjdk 26，满足要求）。
    testImplementation("org.robolectric:robolectric:4.16.1")

    // instrumentation test 基础设施（见 defaultConfig.testInstrumentationRunner
    // 注释）——[Jpeg2000DecoderInstrumentedTest] 需要在真实 Android 运行时里跑
    // 才能真正加载 jp2-android 的 native 库。只拉最小必需的两个坐标（JUnit4
    // 风格的测试类 + 能在真机上跑起来的 runner），不引入 Espresso 之类的 UI
    // 测试框架（这份测试不碰任何 View，用不上）。
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

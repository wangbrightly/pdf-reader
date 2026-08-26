# pdf-reader — 小米手机 PDF 阅读器 App

本地 Android App，核心是"重排阅读模式"：抽取 PDF 正文，按屏宽重新排版（不是简单缩放页面），配合字号连续可调、图片双指缩放。TDD 开发，装了 `tdd-guardian@xiaolai` 插件。

详细技术选型见 `SELECTION.md`，完整踩坑记录见 `NOTES.md`——改动前先读这两份，下面只是浓缩版，供 Claude 快速加载上下文。

## 技术栈

- Kotlin + AGP 9.3.1（**不要加 `org.jetbrains.kotlin.android` 插件**——AGP 9.0+ 已内置 Kotlin 支持，加了会直接构建失败）
- `com.tom-roush:pdfbox-android:2.0.27.0`（纯 Java/Kotlin，无 native 库）—— MuPDF 没有官方预编译 aar，第三方 fork 供应链信任度不足，故弃用
- `compileSdk = 36` / `minSdk = 26` / `targetSdk = 36`
- 测试：JUnit 4 + Robolectric 4.16.1（纯 JVM 单元测试用 Robolectric 模拟 Android Context）

## 构建与测试前必读：JDK 版本是最大的坑

- **跑测试必须用 JDK 21**，不是编译用的 JDK 17。Robolectric 支持 `compileSdk 36` 要求 JVM ≥ 21。
  ```
  export JAVA_HOME=/usr/local/opt/openjdk@21
  ```
- **千万别让 Gradle 用这台机器默认的 `openjdk`（当前指向 JDK 26.0.2）**——JDK 26 会导致依赖下载反复"TLS 握手失败"。换成 17 或 21 后秒级下载。遇到莫名其妙的下载失败，先查 `JAVA_HOME` 有没有指到 26。
- Robolectric 的 `nativeruntime-dist-compat` 依赖约 159MB，`settings.gradle.kts` 已配置阿里云镜像（境外线路约 10KB/s → 镜像约 9MB/s），不用再额外配置代理排除规则（`~/.gradle/gradle.properties` 已处理）。

## PdfBox 中文提取 bug（已修复，别重新踩）

某些常见汉字（十一二人大文工日长门马车页……）会被提取成形状相似但编码不同的"部首"变体字符，是 PDFBox 已知问题（[PDFBOX-55](https://issues.apache.org/jira/browse/PDFBOX-55)，字体 CID→Unicode 映射歧义时选中部首而非本字）。

修法在代码里已经实现：只对康熙部首区间（U+2F00–U+2FDF）**单字符**做 NFKC 规整；CJK 部首补充区（U+2E80–U+2EFF）手写了 19 字对照表。**不能对整句话做 NFKC**——会把中文全角标点降级成英文半角标点。

生成中文测试用 PDF fixture 不要用 macOS 自带的 `textutil`/`cupsfilter`（会给部分汉字逐字单独起文字对象，PdfBox 分段启发式处理不了），改用 Puppeteer 打印 HTML。

## 表格检测

信号是矢量网格线（填充矩形，不是描边直线——Chromium 打印出来的表格边框是这样），不是文字列对齐。策略保守，≥3 横线 + ≥3 竖线 + 横竖跨度比例不超过 2 倍才判定为表格，宁可漏检。命中页整页渲染成 Bitmap，复用图片展示的双指缩放机制。**整页图片优先于表格检测**（`scanHasFullPageImage=true` 时不跑 `TableGridDetector`）——装饰性设计页（色块/线条多）会让矢量线段凑巧组成假网格，见 NOTES #38/#39/#42。这是历史上反复误判的敏感区域（#17/#39/#42 三次不同触发场景），改动前务必读 `TableGridDetector` 类 KDoc 全文。

## CMYK/YCCK JPEG、JBIG2：自己手写的解码器

PdfBox-Android 和安卓原生 `BitmapFactory` 都解不出这台设备上的 CMYK/YCCK JPEG（纯黑或花屏），JBIG2 完全没实现——两个都自己从头写了解码器（`JpegDecoder.kt`/`Jbig2GenericRegionDecoder.kt`/`Jbig2SymbolTextDecoder.kt`），正确性靠 Pillow（libjpeg-turbo）/`jbig2-imageio` 逐像素交叉验证，范围严格限定在真机确认过的数据形状，范围外一律返回 `null` 退回占位图（不猜、不冒险给出可能错误的内容）。**"跟 Pillow 逐像素比对通过"不等于"结果本身是对的"**——Pillow 自己对 YCCK 反色的默认处理也会错，两份早期 fixture 的参考答案因此错了很久没被测出来，靠独立于 Pillow 的 poppler 整页渲染交叉验证才发现，见 NOTES #40。CMYK→RGB 用的是乘法公式 `(255-C)×(255-K)/255`，不是教科书常见的加法公式，见 NOTES #35。CMYK（transform=0）反色约定逐图对采样值投票判断（Adobe 标准是"存的值反色"，但真实文档存在例外），见 NOTES #29/#32；YCCK（transform=2）不投票，固定整体反色（C/M/Y/K 四个分量一起翻转），见 NOTES #40。已知局限：JBIG2 Huffman 编码符号词典未实现；CMYK/YCCK JPEG 不支持渐进式、超过 600 万像素（`JpegDecoder.MAX_CMYK_JPEG_PIXELS`，见 NOTES #38）；JPX/JPEG2000 完全不支持（PDFBox-Android 需要额外的可选组件 `com.gemalto.jp2:jp2-android`，2026-08-26 核实过原发布仓库 JCenter 已关停、Maven Central 没有这个坐标、JitPack 全部版本构建失败，添加支持这件事本身找不到可靠引入渠道，见 NOTES #43），遇到时展示占位图，不再静默消失。

## `Session` 并发安全

`PdfTextExtractor.Session` 内部有个 `documentLock`（`ReentrantLock(true)`，公平模式），`loadPage`/后台页脚学习线程/后台目录抽取线程全部互斥访问同一个 `PDDocument`——**改这块代码前一定要读 NOTES #33/#34/#36/#43**：`PDDocument` 不是"多读者安全"的资源，`loadPage` 之间并发访问会导致真实的数据损坏（不是理论风险，受控实验实锤过），读写锁的"多读者"模型在这里从设计上就是错的；公平性同样重要，非公平锁在后台线程高频重新加锁时会把 `loadPage` 饿死很久（真机复现过 18 秒卡顿）。**例外**：CMYK/YCCK 图片的真正解码（`JpegDecoder.decode`）从 NOTES #43 起被拆出锁外——它只读一份已经从 `PDImage` 复制出来的 `ByteArray`，不碰 `PDDocument`，多个 `loadPage` 调用可以真正并发解码；`PageContentStreamEngine` 的 `deferCmykDecode=true` 模式负责这个拆分（锁内只读字节，不调 `JpegDecoder.decode`），`PdfPageAdapter.LOAD_POOL_SIZE=3` 就是靠这个例外才有真实并发收益，不是单纯"抢锁"。

## 已知局限（如实告知过用户）

- 表格和正文混排同一页时，正文也会跟着变图片，丢失重排/调字号能力
- 只有外框无内部分隔线的表格会漏检，继续按文字重排（行列打散）
- 完全无边框线的表格（纯空白对齐）检测不到
- `ReadingProgressStore` 无清理机制，条目随打开过的文件数线性增长（当前量级不算问题）
- 扫描版 PDF（没有文字层）无法重排/调字号，需要 OCR，用户 2026-08-18 决定暂缓（见 NOTES #10）
- 大文件（126MB+）`PDDocument.load` 本身耗时几秒到十几秒，试过换成临时文件缓冲但反而更慢，已回退，没找到有效优化手段（见 NOTES #23）

真机型号是小米 mondrian，装机验证是这个项目的日常工作方式（几乎每次改动都真机复测），不是没做过。

## 工作流约定

- 每完成一个 TDD 增量后，**验证 `git status` 是否干净**——曾经发生过测试全过但忘了 `git commit` 的情况，报告里不会主动提这茬。
- 全自动模式下遇到"任务描述和更优方案冲突"，允许主动改并写清楚理由（例如把"字号变化后重新抽取文字"改成"只重排"，因为段落内容不随字号变化）。

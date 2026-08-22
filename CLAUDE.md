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

信号是矢量网格线（填充矩形，不是描边直线——Chromium 打印出来的表格边框是这样），不是文字列对齐。策略保守，≥3 横线 + ≥3 竖线才判定为表格，宁可漏检。命中页整页渲染成 Bitmap，复用图片展示的双指缩放机制。

## 已知局限（如实告知过用户）

- 表格和正文混排同一页时，正文也会跟着变图片，丢失重排/调字号能力
- 只有外框无内部分隔线的表格会漏检，继续按文字重排（行列打散）
- 完全无边框线的表格（纯空白对齐）检测不到
- `ReadingProgressStore` 无清理机制，条目随打开过的文件数线性增长（当前量级不算问题）
- 全程没有 adb 装机验证，真机型号未知，minSdk 26 起不影响开发但没有真机实测

## 工作流约定

- 每完成一个 TDD 增量后，**验证 `git status` 是否干净**——曾经发生过测试全过但忘了 `git commit` 的情况，报告里不会主动提这茬。
- 全自动模式下遇到"任务描述和更优方案冲突"，允许主动改并写清楚理由（例如把"字号变化后重新抽取文字"改成"只重排"，因为段落内容不随字号变化）。

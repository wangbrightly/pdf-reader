# 踩坑记录（供以后回来读这个项目时先看）

## 1. Gradle 测试要用 JDK 21，不是文档里"验证过"的 JDK 17

`~/Desktop/核实与改写档案/小米PDF阅读器App-提示词-v1.md` 里写的"已验证工具链"是 JDK 17.0.20（复用自 weekly-surprise 项目），但那只覆盖了**空壳 App 编译**。这个项目接了 Robolectric 4.16.1（跑 `PdfTextExtractorTest` 需要，见下），Robolectric 要支持 compileSdk 36 就要求跑测试的 JVM ≥ 21，JDK 17 会直接报错：

```
Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java 17)
```

**用法**：跑 `gradle test` 一律用 JDK 21：

```bash
export JAVA_HOME=/usr/local/opt/openjdk@21
gradle test
```

（本机 `openjdk@21` 已经装好，keg-only，不在 PATH 里，必须显式 `JAVA_HOME`。）

`compileSdk`/`minSdk` 等 Android 侧配置维持 36/26 不变，这条只影响"用哪个 JVM 跑 Gradle"。

## 2. 千万别用机器默认的 `openjdk`（当前是 JDK 26）跑这个项目

机器上 `/usr/local/opt/openjdk` 这个符号链接指向的是 JDK 26.0.2（很新的版本），不是文档里验证过的 17。**JDK 26 在这台机器上会导致 Gradle 下载依赖时反复"TLS 握手失败"**（`Remote host terminated the handshake`），无论走代理还是走阿里云镜像都一样失败——换成 JDK 17 或 21 后同样的下载秒级完成。没深挖 JDK 26 具体是哪个 TLS 参数不兼容，只确认了"换 JDK 就好"，如果以后其他项目也遇到类似的下载失败，先检查 `JAVA_HOME` 有没有不小心指到 26。

## 3. `settings.gradle.kts` 里加了阿里云镜像

Robolectric 的 `nativeruntime-dist-compat` 依赖包约 159MB，走代理连 Maven Central 只有约 10KB/s（要 4 小时+），改成不走代理直连阿里云镜像后约 9MB/s（17 秒）。`~/.gradle/gradle.properties` 里已经把 `*.aliyun.com` 排除在代理之外，所以只要仓库列表里有阿里云镜像，Gradle 会自动挑更快的那个，不需要额外配置。

## 4. PdfBox-Android 有个已知的中文提取 bug，已经修了

**现象**：某些常见汉字（十、一、二、人、大、文、工、日……还有"长门马车页"这几个）会被提取成形状几乎一样但编码不同的"部首"字符（比如"十"被提出来变成"⼗"），肉眼一晃很难发现，但逐字比对/搜索/复制粘贴会出问题。

**用真实中文 PDF 实测确认过**（不是猜的）：在 `~/Desktop/核实与改写档案/中县干部-十全十美清单.pdf` 上复现，query 过 PDFBox 社区的已知 issue（[PDFBOX-55](https://issues.apache.org/jira/browse/PDFBOX-55)、[sourceforge #72](https://sourceforge.net/p/pdfbox/bugs/72/)）——是"字体的 CID→Unicode 映射有歧义时，PDFBox 会挑中部首而不是汉字本字"这个已知问题类别，poppler/pdftotext 在同一份文件上没这个问题。

**修法**：`PdfTextExtractor.fixRadicalVariants()` 逐字符检查——落在康熙部首区间（U+2F00–U+2FDF）的用 `Normalizer.normalize(_, NFKC)` 修（214 个康熙部首里 Unicode 官方就有兼容分解映射，直接能用）；另外 19 个"CJK 部首补充"区（U+2E80–U+2EFF）里本身是常用独立汉字的（车/长/门/见/贝/韦/页/风/飞/马/鱼/鸟/卤/麦/黄/齐/齿/龙/龟），Unicode 没给兼容映射，手写了一张表。纯部首用字（钅饣纟讠辶等，从不单独成字）没收——这类字形不会在正常文本里单独出现，收了也用不上。

**踩过的坑**：一开始图省事对整句话做 NFKC，顺手把中文全角标点（，？！（）：）降级成了英文半角标点——NFKC 的兼容折叠范围比想象中大，Fullwidth Forms 区块也在里面。改成只对部首区间的单字符做 NFKC，标点原样保留。

## 5. 测试用的中文 PDF fixture 别用 macOS 自带工具生成

最早用 `textutil`/`cupsfilter` 生成小测试 PDF，两者背后都是同一套 macOS 系统打印管线，会给某些汉字（恰好是上面第 4 条里出问题的那批字）逐字单独起一个文字对象，PdfBox 的分段启发式应付不了，断行断得很怪，且用 `pdftotext -raw` 交叉核对也是乱的——是 fixture 生成方式本身的问题，不是抽取层的锅。

改用本机已装的 Puppeteer（`~/.claude-tools/webshot`）把一段 HTML 打印成 PDF，文字对象按正常语句连续排布，`pdftotext -raw` 核对正常。现在 `app/src/test/resources/sample-chinese.pdf` 就是这样生成的。这也顺带更贴近真实场景——"网页另存为 PDF"是用户真会遇到的常见 PDF 来源。

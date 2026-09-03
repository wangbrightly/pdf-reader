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

信号是矢量网格线（填充矩形，不是描边直线——Chromium 打印出来的表格边框是这样），不是文字列对齐。策略保守，≥3 横线 + ≥3 竖线 + 横竖跨度比例不超过 2 倍 + 同方向线段坐标区间合并起来不能有缺口（`hasCoverageGap`，容差 3pt——独立方框排版的方框之间有真实设计间距，会露出缺口；真表格哪怕逐格画边框，相邻单元格共享分界线，合并起来零缺口，见 NOTES #52 踩过的坑：不能用"单条线自身长度占整体跨度的比例"，Chromium 逐格画表格边框会让这个信号反过来）才判定为表格，宁可漏检。命中页整页渲染成 Bitmap，复用图片展示的双指缩放机制。**整页图片优先于表格检测**（`scanHasFullPageImage=true` 时不跑 `TableGridDetector`）——装饰性设计页（色块/线条多）会让矢量线段凑巧组成假网格，见 NOTES #38/#39/#42。判定出的 `TableRegion` 精确覆盖整个页面 MediaBox（四条边零边距贴死页面边界，`TABLE_REGION_FULL_PAGE_REJECT_RATIO=0.95`）时同样判定为误判——真实数据表格不会做到这样，这种情况改用 `renderPageWithAndroidPdfRenderer`（pdfium）整页栅格化，**不用**下面这条 `PDFRenderer` 裁剪路径（PdfBox 自己的渲染器不认识 DeviceCMYK，会把 CMYK 缩略图的颜色全部读错），也不落回逐图 reflow（会丢光背景色块/装饰线条这些矢量图形，观感更差），见 NOTES #50。这是历史上反复误判的敏感区域（#17/#39/#42/#50/#52 五次不同触发场景），改动前务必读 `TableGridDetector` 类 KDoc 全文。

## 图片在页面内的插入位置

一页里的图片按各自的纵坐标（`PageImageResult.topY`，从图片的 CTM 换算出"距页顶多少 pt"）插回紧跟它自己那段文字后面（`ImagePlacement.afterParagraphIndexByTopY` + `PdfTextExtractor.interleaveTextAndImages`），不是简单地把一页所有图片统一堆在这一页最后一段文字之后——一页只有一张主图的文档看不出区别，一页塞了多张独立配图（杂志式排版，每个小节自己的文字+图片）就会把图文关系整个打散，见 NOTES #51。`extractContent`/`ImagePlacement.afterParagraphIndex`（按页归类，不看页内纵坐标）是更早期、目前只有测试在用的旧路径，不是 `Session.loadPage` 这条实际生效的阅读路径，两者不共享这条按纵坐标插入的逻辑。

## 文字抽取的两个边界修复 + 两栏排版整页栅格化

`isLineOnPage`：跟 `TableGridDetector` 那边的 `isSegmentOnPage` 同一个根因（InDesign 跨页拼版，`MediaBox` 只是显示窗口不是内容边界）——文字行也要过滤掉 X 坐标越界的（邻页内容混进来），不是只有矢量线段需要这层过滤，见 NOTES #53。`mergeSameLineRuns` 判断"是否同一行"除了看 Y 坐标相同，还要看 X 坐标离得够近（`LINE_MERGE_MAX_X_GAP_PT=20`）——两栏排版里左右栏标题经常同一 Y 高度，只看 Y 会把两栏内容错误粘连成一行，同样见 NOTES #53。

两栏排版页**不追求重排出正确的阅读顺序**（用户明确拍板放弃这个目标，按高度分段检测的工作量/验证难度远超收益）——`hasColumnGap` 识别到就直接整页栅格化（复用下面 pdfium 那条路径），不逐段重排。已知局限：同一页"两栏/通栏混合"（比如页面顶部有一句跨两栏的介绍段落）会漏检，接受这个风险——漏检的代价只是维持当前已经修好的样子，不会更差，见 NOTES #54。

`hasScatteredLayout`（NOTES #58）覆盖比 `hasColumnGap` 更宽的场景——不认"两个 X 区间"，认"同一 Y 高度出现 2 条及以上文字"（先调 `mergeSameLineRuns` 排除同一行的正常碎片再统计，阈值 `MIN_OVERLAPPING_Y_GROUPS=4`），命中同一条整页栅格化路径。**只查排除表格区域之后的 `nonTableLines`，检查点在表格检测之后**（`hasColumnGap` 还在表格检测之前，两者不对称，别改错位置）——普通表格每一行的几个单元格天然就是"同一 Y 多条文字"，会跟这个信号撞在一起，装机前的单元测试曾经因此炸出真回归（一个 3 列 4 行的小表格就命中阈值，抢在表格精确裁剪之前把整页正文吞掉栅格化）。改判断条件前**必须**在真实调用路径上跑一遍 `mergeSameLineRuns` 拿真实合并后的数据，不能只看原始坐标数字推——第一版按未合并数据数错过一次阈值，见 NOTES #58 完整教训。

`linesToParagraphs` 里"紧凑列表识别"用的 `isShortLine`（NOTES #59）量的是"这一行自己有多宽"（`(endX-startX)/pageWidth`），**不是**"右边界离页面右侧多远"（`endX/pageWidth`，第一版这么写过，多列网格右侧的短标签哪怕文字本身很短也测不出来，会跟旁边的短标签粘连成一大段乱码，见 NOTES #59）。改这个函数或者它的阈值 `LIST_ITEM_MAX_WIDTH_RATIO` 之前，先想清楚是要测"内容本身多宽"还是"位置在页面哪"——这两个概念在单栏排版里恰好等价，在多列排版里完全不是一回事。

同一条"紧凑列表识别"还有个边界情况（`isBulletMarker`，NOTES #65）：项目符号本身（`o`/`•` 这类单字符标记）物理宽度极窄，会被 `isShortLine` 也判成"短行"，跟它自己配对的正文一起满足"连续两行都短"，被切在符号和内容之间——`compactListBoundary` 判断加了"`lines[i-1]` 不是符号"这个条件（`isBulletMarker`，绝对宽度 `BULLET_MARKER_MAX_WIDTH_PT=15pt`，不用比例——符号物理尺寸不随页宽缩放）。符号只该跟"上一个列表项"之间产生边界，不该跟"自己的内容"之间产生边界。

`PageContentStreamEngine.hasReusedImage`（NOTES #60）覆盖的是前面三条规则都测不出来的另一类场景——**同一张图片对象在页内被复用**（设计稿式拼贴：一张装饰色块摆在好几个不同位置/角度当"连接箭头"用），不是文字堆叠也不是矢量网格线。用 `pdImage as? PDImageXObject` 取 `cosObject`（`COSStream`）存进 `IdentityHashMap` backed 的 `Set`，第二次见到**同一个实例**（不是内容/尺寸相同的两个不同对象——同一个资源名被 `/Do` 调用多次时 PdfBox-Android 确实返回同一个实例，`decodeSoftMaskCompositeOrNull` 那段注释验证过）就命中，同样整页栅格化。图片数量本身**不是**可靠信号——本文档另几个多图页（含 #51/#52 修过的"RF 电路"6 图小节页）都是每张图各不相同，只统计"数量多"会跟 #51 已经修好的"一页多个独立图文小节"场景冲突。

`hasLabelColumnWithSideContent`（NOTES #61）覆盖的是另一类三栏版式——分类标签+产品名徽章+短语列表，`hasColumnGap`/`hasScatteredLayout` 都测不出来（缝隙被跨栏说明文字焊死；短语各自落在不同 Y，测不出"并排"）。信号是"一整列短语精确对齐到同一个 `startX`"，但**必须加一道校验**：这一列的垂直范围内，页面上要确实存在至少 3 条别的文字、且 `startX` 比这一列小至少 40pt（`LEFT_CONTENT_MIN_GAP_PT`）——单独"同一 X 对齐"会把普通缩进列表也误判（左边是页面留白，几何上长得一样），这道校验是用户明确要求加的，权衡过"先上线看真机效果"更快但更冒险的路线。

`PageContentStreamEngine.hasOverlappingImages`（NOTES #62）是 `hasReusedImage` 的姊妹信号——同一类"设计稿式拼贴"，手法从"复用同一个对象"换成"摆放多个不同对象让它们物理重叠"。用 CTM 算每张图片的包围盒，两两重叠面积占较小那张自身面积的比例达到 `MIN_IMAGE_OVERLAP_RATIO=0.15` 就命中（真机三组数据横跨 21%~66%，阈值取在最低值之下留安全边际；正常图片"边缘贴合不重叠"的重叠比趋近于 0）。**排查这类反馈时先用 CTM 精确核对重叠关系，不要凭页面图片数量或肉眼截图猜**——同一次调查曾经因为用户口头描述的页码/现象跟自己的诊断结论对不上而查错方向，见 NOTES #62 完整过程。

## CMYK/YCCK JPEG、JBIG2：自己手写的解码器

PdfBox-Android 和安卓原生 `BitmapFactory` 都解不出这台设备上的 CMYK/YCCK JPEG（纯黑或花屏），JBIG2 完全没实现——两个都自己从头写了解码器（`JpegDecoder.kt`/`Jbig2GenericRegionDecoder.kt`/`Jbig2SymbolTextDecoder.kt`），正确性靠 Pillow（libjpeg-turbo）/`jbig2-imageio` 逐像素交叉验证，范围严格限定在真机确认过的数据形状，范围外一律返回 `null` 退回占位图（不猜、不冒险给出可能错误的内容）。**"跟 Pillow 逐像素比对通过"不等于"结果本身是对的"**——Pillow 自己对 YCCK 反色的默认处理也会错，两份早期 fixture 的参考答案因此错了很久没被测出来，靠独立于 Pillow 的 poppler 整页渲染交叉验证才发现，见 NOTES #40。CMYK→RGB 用的是乘法公式 `(255-C)×(255-K)/255`，不是教科书常见的加法公式，见 NOTES #35。CMYK（transform=0）反色约定逐图对采样值投票判断（Adobe 标准是"存的值反色"，但真实文档存在例外），见 NOTES #29/#32；YCCK（transform=2）不投票，固定整体反色（C/M/Y/K 四个分量一起翻转），见 NOTES #40。已知局限：JBIG2 Huffman 编码符号词典未实现；CMYK/YCCK JPEG 不支持渐进式、超过 600 万像素（`JpegDecoder.MAX_CMYK_JPEG_PIXELS`，见 NOTES #38）。

## JPEG/JPX 编码的软蒙版（/SMask）：绕开 PdfBox 自己的合成，不是绕开解码

蒙版格式是 JPEG 或 JPX（`softMask.suffix=="jpg"`/`"jpx"`，不管底图本身是什么格式）时，`pdImage.image` 内部的蒙版合成有 bug，产出纯黑图片——蒙版单独解码是正常的，只有这两种蒙版格式会坏，见 NOTES #57/#63。修法是调用 `imageXObject.image` 前临时用 `COSDictionary.removeItem(COSName.SMASK)` 摘掉蒙版（解码完 `setItem` 放回去），让 PdfBox 自己一直可靠的底图解码逻辑在"没有蒙版参与合成"的情况下跑，蒙版单独解码（JPEG 用 `BitmapFactory`，JPX 用 `Jpeg2000Decoder`）灰度值当 alpha 通道手动合成——**不是**判断"底图和蒙版是不是都是 JPEG"（第一版这么判断过，装机复测完全没效果，根因是这份文档的底图 `suffix` 其实是 `"png"` 不是 `"jpg"`，判断条件从一开始就没生效，靠在真实调用路径上加**无条件**日志才纠正过来）。这是本类第二次"先分析第三方工具输出（这次是 `pdfimages -list`）反推判断条件，装机才发现推错了"的教训，改判断条件前先用真机日志把 `PDImage` 实际字段值实测一遍，不要靠外部工具的分组结果反推。**#57 那次"蒙版是 PNG/JPX 时合成都正常"这条结论后来被 #63 推翻**——样本量不够就断言"其它组合没事"是会过期的，遇到同一类 bug 的新反馈别直接采信旧结论说"这个组合之前验证过没问题"。

蒙版解码失败时函数返回**已经解码成功的不带蒙版底图**（完全不透明），不是 `null`——第一版失败时返回 `null`（回退到 `pdImage.image` 那条已知有 bug 的通用路径），Robolectric 诊断时意外发现"即使确认蒙版解码失败，最终展示的图片依然正常"，追下去是 `imageXObject.image` 内部有解码缓存，函数自己已经成功解码过一次底图，调用方兜底那行代码在**同一个 `PDImageXObject` 实例**上第二次调用命中了缓存——这份行为没有任何文档保证，不能依赖，见 NOTES #63"实测意外发现"一节。**JPX 蒙版真正解码成功、透明区域是否正确抠图没有真机专门验证过**，只确认过"不再是纯黑"——`Jpeg2000Decoder` 依赖真实 Android native 库，Robolectric 测不到这一层。

## JPX/JPEG2000：接的是第三方 native 库，不是手写解码器

`Jpeg2000Decoder.kt` 薄封装 `io.github.michaldvorak-gemalto:jp2-android:1.0.5`（`JP2ForAndroid` 项目，OpenJPEG 2.5.4 的 JNI 封装，**个人维护者重新发布到 Maven Central 的坐标，不是 Thales 官方渠道**，不要在对外文档里写"官方库"）。**NOTES #43 曾经记录"原坐标 `com.gemalto.jp2` 不可引入"，这条结论已过时**——上游换坐标解决了发布问题，见 NOTES #48 完整核实过程（这条结论是被用户连续三轮独立核实纠正的，不是自己主动发现）。POC 装机验证过：正常数据解码正确（跟 macOS `sips` 独立实现逐像素比对一致）、截断流/伪造头部数据稳定返回 `null` 不崩溃、NOTES #43 那份真实样本能正确解码。**遇到"某开源组件不可用"这类结论时，如果决策时间点和调研时间点隔了几天以上，先重新一手核实，不能直接复述旧结论**——这类结论有时效性，尤其是个人维护的小众库。

依赖 native `.so`，Robolectric（纯桌面 JVM）加载不了，测试分两层：`PdfTextExtractorImageTest` 里的 wiring 测试（Robolectric，验证 suffix 判断/占位图降级，不测真正解码）+ `app/src/androidTest/Jpeg2000DecoderInstrumentedTest`（真机 instrumentation test，测真正的 native 解码）。`gradle connectedDebugAndroidTest` 在这个项目的 USB 环境下经常因为短暂断线整体失败（UTP 测试编排对连接中断容忍度低，不会自动重试）——更抗断线的路径是 `adb install` 手动装主 APK+androidTest APK，再 `adb shell am instrument -w app.pdfreader.test/androidx.test.runner.AndroidJUnitRunner` 直接触发。

**图片"占满全页且解码成功"≠"值得为它隐藏文字/占屏幕空间"**：`PageContentStreamEngine.hasVisibleContent`（亮度标准差采样，阈值 12）判断这张图是不是接近纯色的背景层——不是的话正常展示；是的话不仅不隐藏文字，图片本身也整个跳过不展示（`addRealImage`），避免"图解码成功了但内容毫无价值"占用比真实文字大得多的屏幕空间，见 NOTES #49 完整的三轮真机回归过程。

**PDF 蒙版（`/Mask` stencil masking）目前不合成，遇到就跳过**：`PageContentStreamEngine.drawImage` 检测到 JPX 图片带 `getMask()`/`getSoftMask()` 就直接跳过不展示——真正合成需要知道蒙版抠掉的区域下方画了什么，等同于整页栅格化，不是两张位图简单叠加。`hasSkippedFullPageImage` 记录这次跳过；当页面**同时**满足"真实文字字符数低于阈值"（`MIN_REAL_TEXT_CHARS_FOR_FALLBACK_SKIP = 10`，不能用 `filtered.isEmpty()`——真机数据证实封面页会有孤立噪音字符骗过"是否为空"判断）时，改用 `Session.renderPageWithAndroidPdfRenderer` 整页栅格化兜底（**不是** PdfBox 自己的 `PDFRenderer`——真机验证过后者对同一份数据一样渲染不全，根因是 PdfBox-Android 从未实现 JBIG2 解码，`PDFRenderer` 内部渲染管线一样受限；`android.graphics.pdf.PdfRenderer` 是系统自带的 pdfium 引擎，对 JBIG2+蒙版支持成熟，真机验证过完整正确）。

## Wingdings/Webdings 图标字体：字母键位提取出来的"文字"没有语义，整段过滤

`LineCollectingStripper.writeString`（`isDecorativeSymbolFont`，NOTES #64）：这两个字体是纯图标字体（键盘字母键位对应箭头/项目符号这类图标，不是字母本身，PDF 的 `ToUnicode` 映射表如实记录"这是键盘 l 键"但这串数据从设计上不是给人读的语义文字），textPositions 全部来自这两个字体名就整段跳过，不进 `lines`。**判断条件精确匹配"Wingdings"/"Webdings"这两个字体家族名字符串，不能扩大化**——同一份文档大量用 `Symbol` 字体排版真实希腊字母/数学符号（公式变量），按"字体名不常见就过滤"这种更简单的思路会把公式内容删光，是比"多几个孤立字符"严重得多的破坏。构造测试 fixture 时注意：`page.resources.getFont()` 拿标准 14 字体（比如 `PDType1Font.HELVETICA`）返回的是跨 JVM 进程共享的静态单例，直接在它的 `cosObject` 上 `setItem` 改名会让副作用泄漏到同一进程内其它测试，要先用 `COSDictionary(COSDictionary)` 拷贝一份独立字典再改名。

## `Session` 并发安全

`PdfTextExtractor.Session` 内部有个 `documentLock`（`ReentrantLock(true)`，公平模式），`loadPage`/后台页脚学习线程/后台目录抽取线程全部互斥访问同一个 `PDDocument`——**改这块代码前一定要读 NOTES #33/#34/#36/#43**：`PDDocument` 不是"多读者安全"的资源，`loadPage` 之间并发访问会导致真实的数据损坏（不是理论风险，受控实验实锤过），读写锁的"多读者"模型在这里从设计上就是错的；公平性同样重要，非公平锁在后台线程高频重新加锁时会把 `loadPage` 饿死很久（真机复现过 18 秒卡顿）。**例外**：CMYK/YCCK 图片的真正解码（`JpegDecoder.decode`）从 NOTES #43 起被拆出锁外——它只读一份已经从 `PDImage` 复制出来的 `ByteArray`，不碰 `PDDocument`，多个 `loadPage` 调用可以真正并发解码；`PageContentStreamEngine` 的 `deferCmykDecode=true` 模式负责这个拆分（锁内只读字节，不调 `JpegDecoder.decode`），`PdfPageAdapter.LOAD_POOL_SIZE=3` 就是靠这个例外才有真实并发收益，不是单纯"抢锁"。`loadPage` 另外多了个 `onTextReady` 回调（文字抽完立刻回调展示，不用等同页图片解码完），`PdfPageAdapter` 的加载队列从普通 FIFO 换成 `PriorityBlockingQueue`（当前可见页优先于预加载页），见 NOTES #44——这两处装机上验证过能正常工作，但"确实更快"这个体感结论没有拿真机 logcat 时间戳逐条量化过。

## UI 视觉规范

配色统一在 `colors.xml`（藏青蓝 `button_primary_bg` 是"字号"滑杆/主操作按钮的强调色；"行距"/"边距"/"段距"三个滑杆另有独立强调色 `accent_line_spacing`/`accent_padding`/`accent_block_spacing`，参照游戏音量滑杆截图"每条滑杆自己一个颜色"的设计，见 NOTES #46）。滑杆/翻页手柄的"旋钮"造型（实心圆+白色描边+抓握纹理）用 `VectorDrawable` 写死 `pathData`，不要用 `layer-list` 叠 `<shape>`——后者的 `<item>` 定位是"到边界的内边距"语义，摆不出"几条等间距线居中排列"这种效果；`VectorDrawable` 也不支持运行时传参染色（`tint` 会把多色 vector 里的白色描边一起染掉），4 种强调色对应 4 份独立的 thumb/track drawable 文件（`_purple`/`_coral`/`_teal` 后缀），见 NOTES #45/#46。

**沉浸模式**（`MainActivity.updateChromeVisibility`）：点击屏幕中央区域（`CENTER_TAP_ZONE_START`~`END`）统一控制 `topButtonRow`（目录/打开 PDF/设置）、`fileNameLabel`、`settingsPanel`（4 个滑杆）、`pageScrubberThumb` 四样东西的显隐，`chromeRevealedByTap` + `settingsPanelExpanded` 两个独立状态的交集决定 `settingsPanel` 最终是否可见——**改这块前先读该函数 KDoc**，`topButtonRow` 在没有文档时（`currentSession == null`）有强制常驻显示的例外，不然用户点不到"打开 PDF"入口。

**深色模式**：只在 `values-night/colors.xml` 覆盖 `label_text_emphasis`（文件名/滑杆标签的深色文字，唯一一处"写死颜色+可能坐在会变黑的背景上"的地方）——正文文字不设颜色、继承主题属性自动跟随；按钮/滑杆强调色画在自己的纯色块上、半透明灰背景本来就会随底色自动深浅，都不需要额外的 night 变体。遇到"深色模式下看不清"，不要先反应"关掉 forceDark"或"锁死 Light 主题"绕过去——先盘点 `colors.xml` 到底哪个颜色是真正需要变的，通常比想象中少，见 NOTES #47 完整教训（这条踩了两次弯路才找到正解）。

## 已知局限（如实告知过用户）

- 表格和正文混排同一页时，正文也会跟着变图片，丢失重排/调字号能力
- 只有外框无内部分隔线的表格会漏检，继续按文字重排（行列打散）
- 完全无边框线的表格（纯空白对齐）检测不到
- `ReadingProgressStore` 无清理机制，条目随打开过的文件数线性增长（当前量级不算问题）
- **PdfBox-Android 对某些 `/Indexed` 调色板 + FlateDecode 栅格数据的图片解码高度算错**（2026-09-03 排查 NOTES #62 时发现，未修复：`.colorSpace.numberOfComponents` 疑似把 Indexed 空间误报成 3 分量，导致按 RGB 而不是按索引单分量读取字节，行数因此被压缩到约 1/3、颜色也错）——真机这次撞到的具体页面被 `hasOverlappingImages` 整页栅格化兜底掉了，问题本身**没有修**，如果以后遇到"图片显示高度不对/颜色错乱但不是纯黑"且没有触发任何整页栅格化规则的页面，先查是不是这个坑，不要当成新 bug 从头排查
- JPX 蒙版合成"不再是纯黑"已验证，但"透明区域真的正确抠图了"没有真机专门验证过，见"JPEG/JPX 编码的软蒙版"一节
- 同一段说明文字引用的插图如果原书排版把插图印在下一页（图文本来就跨页），逐页独立处理的架构接不上，会出现"读到一堆提到图的文字、翻页后突然冒出一整块图片"——用户 2026-09-03 已知情况后拍板不投入开发，接受为局限
- 扫描版 PDF（没有文字层）无法重排/调字号，需要 OCR，用户 2026-08-18 决定暂缓（见 NOTES #10）
- 大文件（126MB+）`PDDocument.load` 本身耗时几秒到十几秒，试过换成临时文件缓冲但反而更慢，已回退，没找到有效优化手段（见 NOTES #23）

真机型号是小米 mondrian，装机验证是这个项目的日常工作方式（几乎每次改动都真机复测），不是没做过。

## 工作流约定

- 每完成一个 TDD 增量后，**验证 `git status` 是否干净**——曾经发生过测试全过但忘了 `git commit` 的情况，报告里不会主动提这茬。
- 全自动模式下遇到"任务描述和更优方案冲突"，允许主动改并写清楚理由（例如把"字号变化后重新抽取文字"改成"只重排"，因为段落内容不随字号变化）。

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

## 6. Chromium 打印表格边框，画的是"细长填充矩形"，不是描边直线

做表格检测（2026-08-18 增量）时反编译了 `sample-with-table.pdf` 的 content stream 才发现：Chromium 打印 `<table border>` 的单元格边框，用的是 `re`（画矩形）+ `f`（填充）——每条边框是一个宽或高只有 1pt 的细长矩形，不是常见印象里"用 `m l S` 描一条线"。如果表格检测只处理 `strokePath`（对应 `S`），会完全漏检 Chromium 打印出来的表格。`TableGridDetector`/`PdfTextExtractor.TableGridStreamEngine` 因此同时处理 `appendRectangle`（矩形四条边）和 `fillPath`（不只是 `strokePath`），并且用"线段长度"过滤掉矩形的"厚度"那条短边，只留长边参与网格判断。以后如果还要基于矢量图形做检测，先反编译几个真实来源（浏览器打印/Office 导出/LaTeX）的 content stream 看它们实际画法，不要凭 PDF 规范的"教科书画法"猜。

## 7. PdfBox-Android 的 `PDFGraphicsStreamEngine` 和 `PDFRenderer` 在 Robolectric 下能正常跑，不需要额外配置

`com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine`（自定义图形流引擎，用来拿矢量线段坐标）和 `com.tom_roush.pdfbox.rendering.PDFRenderer`（整页渲染成 `Bitmap`）在现有 Robolectric 4.16.1 环境下直接能跑，用真实含表格的 fixture 验证过（A4 页面 @150 DPI 渲染出 1241×1753 px 的 `Bitmap`，和"210×297mm×150dpi"的理论值吻合），没有触发"Robolectric 默认不支持真实图形栅格化"这类担心——不需要额外开启 `NativeGraphicsMode`/`LegacyGraphicsMode` 之类的配置，跟已有 `PDImageXObject.getImage()`（[4] 节图片抽取用到的 API）同样开箱即用。以后如果要在这个项目里做更多"依赖 PDFBox 图形渲染"的功能，可以直接假设 Robolectric 环境支持，不用先怀疑环境限制。

## 8. `gradle assembleDebug` 成功不等于 App 能跑起来——真机装上直接闪退过一次

2026-08-18 装真机（小米，代号 mondrian）第一次打开就闪退。原因：`MainActivity` 继承
`AppCompatActivity`，AppCompat 的 `setContentView` 硬性要求应用主题是
`Theme.AppCompat`（或子类），但项目从建骨架起就没声明过任何主题，`AndroidManifest.xml`
里也没写 `android:theme`，用的是系统默认主题——`AppCompatDelegateImpl.createSubDecor`
直接抛 `IllegalStateException`。

**为什么一路 TDD 都没抓到**：写过的测试全是纯逻辑（抽取/重排/设置/进度这些），没有一个
测试真正跑过 `MainActivity.onCreate()` 里 `setContentView` 这条路径——`assembleDebug`
只验证代码能编译打包，不验证运行时不崩。UI 增量的边界写的是"UI 代码不强求自动化测试
覆盖，跑一次真实编译验证即可"，这个尺度对纯逻辑够用，但对"Activity 起不起得来"这类
运行时才暴露的问题不够，**光编译成功不能当作"App 能用"的证据，得真机/模拟器实际跑一次
才算数**。

修法：加 `res/values/themes.xml` 定义 `Theme.PdfReader`（继承
`Theme.AppCompat.DayNight.NoActionBar`），`AndroidManifest.xml` 的 `<application>`
标签加 `android:theme="@style/Theme.PdfReader"`。

## 9. 用 adb 装包到小米手机，"USB 安装"这个开发者选项开关容易漏开

第一次 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`——
不是安装包或代码问题，是 MIUI/HyperOS 在"开发者选项"里单独放了一个"USB 安装"开关
（跟"USB 调试"是两个不同开关），没开会拦下所有非应用商店来源的 adb 安装。开发者选项里
手动打开后重新 `adb install -r` 就通过了。

## 10. 待办（暂缓）：扫描版 PDF 的文字重排——需要 OCR，用户 2026-08-18 决定先不做

扫描 PDF 本质是"页面照片"，没有真正的文字层，`PdfTextExtractor` 抽不出任何文字，
所以现在这类页面没法重排/调字号——这是预期行为，不是 bug。要支持的话需要接入 OCR
（光学字符识别），技术路线已经调研过：

- **可行方案**：Google ML Kit 文字识别（`com.google.mlkit:text-recognition` +
  `text-recognition-chinese`），离线运行、免费、不用联网，CJK 识别效果不错。
- **前提已确认**：这台测试机上其实**有** Google Play 服务（`adb shell pm list
  packages | grep google` 能看到 `com.google.android.gms`/`com.google.android.gsf`，
  包名里还有个"谷歌安装器"，像是手动装上的）——ML Kit 依赖 GMS，这台机器上能跑。
  但不能想当然认为"小米手机都有 GMS"，换一台机器要重新核实这个前提。
- **代价**：APK 体积会明显变大（ML Kit 识别包十几 MB 级别）；扫描页要先转图片再逐页
  识别，比现在的纯文字抽取慢不少；识别质量有上限，不保证 100% 准确，需要保留"认不出
  就回退成看图片"的降级路径，不能因为识别失败就崩溃或整份文档失败。

用户 2026-08-18 决定"留着以后做"，不是否决，是排期问题——以后要捡起来做的话，先重新
确认目标机型有没有 GMS，再决定还是走 ML Kit 还是换成不依赖 GMS 的 Tesseract 方案
（体积更小依赖更少，但识别准确率通常更弱，尤其 CJK）。

## 11. 内嵌图片朝向修正：纸面推导的符号推错了，"独立验证"的测试其实也不独立

**现象**：真机反馈一本扫描书全书图片上下+左右都反了。第一次修复（018b673）时用纸面
推导 CTM 数学，假定"不翻转"的基准 CTM 是 `a>0、d<0`；装机验证后依然是反的。

**关键教训：测试里的"独立参照模型"和被测代码用了同一个错误假设，所以互相"验证通过"，
却验证的是同一个错误**。`PdfTextExtractorImageOrientationTest.referenceLayout()` 当
时直接把 Bitmap 左上角映射成图片空间坐标 `(0,0)`——这其实是把"Bitmap 顶部=图片空间
原点"当成了默认前提，跟生产代码里那次推错的"基准"假设是同一个坑，不是两套独立逻辑。
两边"数学推导一致"这种验证方式，遇到"两边共享同一个错误假设"时是失效的，不会自己
报错，只会在真机上暴露。

**这次怎么验证的**：不再靠推导，改用真正独立于 PDFBox/本项目代码的第三方渲染器
（poppler `pdftoppm`）：Python 手写最小 PDF（4×4 像素红/绿/蓝/黄四象限测试图 + 明确
指定的 CTM 矩阵），poppler 渲染出 PNG，PIL 逐像素采样具体颜色，跟代码算出来的结果
直接比对坐标和颜色，不比对"两套数学推导是否一致"：

- CTM `a>0,d>0` → ground truth 证明**不需要修正**（旧代码判断成"需要垂直翻转"，错）。
- CTM `a>0,d<0` → ground truth 证明**需要垂直翻转**（跟旧假设的方向正好相反）。
- CTM `a=0,d=0`（90°旋转分支）→ ground truth 证明 `m01` 应为 `-sign(c)`，旧代码写
  成 `sign(c)`，符号反了；`m10=-sign(b)` 是对的，没改。

**修法**：`orientationMatrixOrNull()` 改了两处符号（`m11`、`m01`），测试文件的
`referenceLayout` 图片空间坐标映射也一并改成跟 PDF 规范一致（Bitmap 顶部对应图片
空间 `y=1` 而不是 `y=0`），`ctmCases` 里被搞反的标签也改了。装机验证：用户确认改完
后全书方向问题基本解决，库里另有一本书仍有方向问题，但用户判断是那本书本身排版有
问题，不是这次要覆盖的场景，先不追。

**往后再改这类朝向/坐标问题，别再纯靠纸面推导，也别造一个"独立模型"就假设它真的独立
——用第三方成熟工具（不同代码库、不同作者）产出的结果当地面真相，才是真的独立验证。**

## 12. 已修：配置变化（转屏等）会导致 Activity 重建，当前打开的文档整个丢失

**现象**（code-review 2026-08-19 发现，不是真机反馈）：`MainActivity` 没有
`android:configChanges`，也没有 `onSaveInstanceState`/`ViewModel` 之类的状态保存
机制——只要发生配置变化（最常见是转屏，也可能是系统字体大小变化等），Activity 会
被完整销毁重建，`currentContent`（已经抽取好的文字/图片/大纲）、当前滚动位置、
当前打开的文件全部丢失，回到"还没打开任何文档"的初始画面。

**修法**（commit `6026899`）：`onSaveInstanceState` 里只存一个 `currentUri`
（`Uri` 是 `Parcelable`，体积小，不受 `Bundle` 大小限制），`onCreate` 检测到
`savedInstanceState` 非空时用这个 `Uri` 重新走一遍 `loadPdf()`——相当于自动帮
用户重新打开刚才那份文件，会比真正的状态保留慢（要重新抽取），但实现简单；滚动
位置不用额外处理，`onPause` 在销毁前已经存过一次阅读进度，重新 `loadPdf` 走的是
和"手动重新打开"完全相同的路径，自动读回那次存的位置。

**真机验证**：adb 关闭自动旋转、强制转横屏，截图确认文档没有消失、还停留在转屏前
的大致位置；logcat 确认 `extractContent` 确实被重新调用了一次（不是系统侥幸保留
了视图状态），转回竖屏正常。这处改动涉及 Activity 生命周期，Robolectric 测不了，
用这种方式验证，没有另外写自动化测试。

## 13. 已修（部分）：大图加载慢——反编译 PdfBox-Android 找到根因，JPEG 走安卓原生解码

**现象**：一份 258 页、图片较多的文档打开要 25 秒，真机日志确认几乎全部耗时在图片
解码——`PDImage.getImage()` 默认按源图片原始分辨率整张解码，源图片是几千像素见方
的扫描页/照片时，解码成本跟屏幕实际显示尺寸完全不成比例。

**前两次尝试都失败了**（教训见下）：
1. 用 `PDImage.getImage(Rect, Int)` 降采样重载：真机实测 `subsampling>1` 时稳定
   抛 `y + height must be <= bitmap.height()`，全部图片解码失败、静默丢图，日志
   显示的"快了 16 倍"其实是图片全没了。
2. 解码完整图后 `Bitmap.createScaledBitmap` 事后缩小：图片都在了，但真机实测总
   耗时反而变成 36.3 秒，比原来还慢——真正的开销大头（全分辨率解码本身）没有省
   掉，事后缩小是纯粹叠加的额外成本。

**根因**（反编译 PdfBox-Android 2.0.27.0 字节码找到的，不是猜的）：
`getImage()`/`getImage(Rect,Int)` 最终都调用同一个内部方法
`SampledImageReader.getRGBImage(...)`，只是传的 `subsampling` 参数不同——尝试 1
踩到的异常是这个内部方法本身处理 `subsampling>1` 的 bug，不管怎么调用外层 API 都
绕不开。

**第三次尝试成功**：完全绕开 PdfBox-Android 的图片解码，只对 JPEG（`getSuffix()
=="jpg"`，反编译确认内容流最后一层过滤器是 `DCTDecode` 时才返回这个值）生效——用
`PDImage.createInputStream(listOf("DCTDecode","DCT"))` 拿到停在 DCT 解码之前的
原始 JPEG 字节（反编译确认这个重载的语义就是"遇到列表里的过滤器名字就停手"），交给
安卓自带的 `BitmapFactory.decodeByteArray` + `Options.inSampleSize` 解码——平台
自带、久经考验，跟 `SampledImageReader` 不共享任何代码，不会被同一个 bug 影响。
任何一步失败都回退到 `pdImage.image`，不会丢图。

**真机验证**（同一份 258 页文档）：25.4s → 16.2s（提速约 36%），0 张图片解码失败，
截图确认多张扫描地图/人物照片正常清晰显示。

**"部分"修好的意思**：只覆盖 JPEG 编码的图片，PNG/TIFF/JBIG2 等其它格式仍然走原来
较慢但可靠的 `pdImage.image` 路径——JPEG 是真实场景里最常见的大图来源（扫描页/
照片），但不是唯一来源，遇到非 JPEG 大图较多的文档，这次的提速覆盖不到。

**这次投入的调查方法值得记录**：前两次都是"看起来解决了"就装机验证，第三次先反编译
库的字节码找到真正的根因和正确的 API 用法，再动手实现——同一个问题反复失败之后，
停下来往底层查一层，往往比继续在上层试各种参数组合更快找到真正能用的方案。

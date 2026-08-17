# PDF 阅读器技术选型调研

调研日期：2026-08-17
目标场景：小米手机（MIUI/HyperOS，minSdk 26 起）本地自用 PDF 阅读 App，核心诉求是"重排阅读"（抽取正文文字，按屏宽重新排版，图片尽量随文字流保留位置），中文显示要正确，字号/行距/边距连续可调。不追求应用商店合规。

> 标注说明：每条关键论断后括号标注来源 URL 及核实日期；无法联网核实、依赖模型训练记忆的论断，一律标注"**训练记忆，未核实**"，不当作确定结论使用。

---

## 1. 对比表格

| 维度 | 路线1：android.graphics.pdf.PdfRenderer / androidx.pdf | 路线2：PdfBox-Android (tom-roush) | 路线3a：Pdfium 系绑定 | 路线3b：MuPDF | 路线4：PDF→EPUB 本机转换 |
|---|---|---|---|---|---|
| 带位置信息的文本层 | 仅 **Android 15 (API 35) 及以上**的系统 `PdfRenderer.Page.getTextContents()` 才有，按 API 30–34 有 `PdfRendererPreV` 补丁包；**API 26–29 完全没有**，只能渲染位图 | 有，`PDFTextStripper` 重写 `writeString(String, List<TextPosition>)`，逐字符坐标 | 现成 Android 封装库**均不提供**逐字符坐标（见下方细节）；底层 pdfium C 库本身有 `FPDFText_GetCharBox`，但需自己写 JNI | 有，结构化文本 (STEXT) API，`fz_stext_char` 精确到字符级 bbox | 间接：底层多为 iText，能拿到文本流但不是给你查询坐标的 API，是"直接吐 EPUB" |
| 中文提取/字体渲染 | 未找到专门踩坑记录（未核实），走系统渲染引擎 | 上游 Apache PDFBox 历史上多次报告 CJK 提取问题（ToUnicode CMap 缺失导致乱码/空白），移植版继承同一代码 | 视具体绑定而定，底层 pdfium 对 CJK 支持成熟（训练记忆） | 通常被认为对 CJK 支持较好（训练记忆，未找到反例，也未找到专门验证） | ePUBator 明确写"未针对亚洲文字测试过"(untested) |
| License | Apache 2.0 | Apache 2.0 | 各绑定库多为 Apache 2.0；底层 pdfium 为 BSD 风格 | **AGPLv3 或 Artifex 商业授权**（双授权） | ePUBator 本体 GPL-3.0，依赖的 iText 为 AGPLv3 |
| 项目维护状态（已核实日期） | androidx.pdf 仍在活跃迭代，但**长期处于 alpha**（当前 1.0.0-alpha19，2026-07-01） | 代码最后一次实质提交 **2023-12-29**，之后只有依赖升级 PR；125 个未关闭 issue | barteksc 主流版本最后 push **2024-03-19**（停滞）；社区维护叉 rhariskumar3/AndroidPDFPreview 最后 push 2026-01-29 但 README 自称"不再积极维护" | mupdf 主库最后 push **2026-08-14**；mupdf-android-fitz 最后 push **2026-06-26**，非常活跃 | abravodev/epubator 具体最近提交日期未核实 |
| APK 体积增量 | 官方称"轻量"，因为壳层不带 native 库，本质是系统渲染服务的沙箱封装（有来源，见下） | 纯 Java/Kotlin，无 native .so，增量最小（几 MB 级，未逐字节测） | 多架构 pdfium native 库合计**约 16MB**（一篇技术文章引用的数字，未逐字节复核） | 旧数据一篇 2016 年博客提到从 10MB 涨到 59.9MB（**过时，不采信具体数字，仅供量级参考**）；MuPDF mini 官方 F-Droid 通用包当前实测 **16 MiB**（已核实，2026-06） | 依赖 iText，体积中等，未逐字节测 |
| 有无验证可行的开源重排示例 | 未找到成熟示例（API 太新） | 未找到成熟示例 | kdroidFilter/ComposePdf 用 `FPDFText_GetCharBox` 做字符级选中，证明底层能力可行，但**尚未提交 LICENSE 文件**、且自称实验性 | KOReader 基于 MuPDF，但其"真·文字重排"（非扫描位图的 k2pdfopt 重排）长期是社区里的未解难题（GitHub issue 标题即为 "PDF text reflowing"） | ePUBator 证明"纯离线设备端转换"确实存在且可行，但输出质量弱（见下） |

---

## 2. 逐路线详细说明

### 路线 1：android.graphics.pdf.PdfRenderer + androidx.pdf（Jetpack）

**两层要分清**：
- `android.graphics.pdf.PdfRenderer`（API 21 起随系统自带）——基础版本**只能把页面渲成 Bitmap**，没有任何文字提取能力。
- Android 15（API 35）给 `PdfRenderer.Page` 新增了 `getTextContents()`、`getImageContents()`、`selectContent()` 等框架级 API，返回的 `PdfPageTextContent` 带有按行的 `RectF` 坐标（[developer.android.com/develop/ui/views/layout/pdf/pdf-viewer](https://developer.android.com/develop/ui/views/layout/pdf/pdf-viewer)，已核实）。API 30–34 靠 `PdfRendererPreV` 补丁包往回兼容。**API 26–29（项目声明的 minSdk 起点）完全没有这套能力**——这是本项目声明的 minSdk 与该路线能力之间的真实缺口，需要你确认小米手机实际运行的系统版本（HyperOS 通常基于较新 Android，大概率没问题，但没有实测确认）。
- `androidx.pdf`（Jetpack 独立库，`PdfViewerFragment`/Compose `PdfViewer`）目前最新版 **1.0.0-alpha19（2026-07-01发布）**，minSdk 已从早期的 31 下调到 **28**（[developer.android.com/jetpack/androidx/releases/pdf](https://developer.android.com/jetpack/androidx/releases/pdf)，已核实）。`getSelectionBounds()` API 是 2026-04 的 alpha18 才加入的新功能。**仍是 alpha，API 随时可能破坏性变更**，一篇实测文章直接写"customizations are very limited"（[proandroiddev.com](https://proandroiddev.com/say-goodbye-to-third-party-pdf-libraries-androidx-pdf-is-here-c49872728784)，已核实）。
- APK 体积：`pdf-document-service` 模块的定位是"sandboxing the underlying android PdfRenderer class"，即封装系统自带渲染器，本身不打包 native 库，因此体积增量很小（有引用来源，未逐字节测）。

**结论**：这是"官方原生"路线，长期看最省心，但当前（2026-08）仍不成熟、minSdk 门槛也比项目声明的 26 更高。要用它做重排，本质上还是拿到"按行"的坐标（不是逐字符），且必须依赖较新系统版本。

---

### 路线 2：PdfBox-Android（tom-roush 移植版）

- 许可证 Apache 2.0，个人自用无任何限制。
- 文字位置：继承自 Apache PDFBox 的经典做法——继承 `PDFTextStripper`，重写 `writeString(String, List<TextPosition>)`，`TextPosition` 携带逐字符的 x/y/宽高信息。一篇讲"Custom Android PDF Text Search"的实测文章确认了移植版里 `com.tom_roush.pdfbox.text.PDFTextStripper` / `TextPosition` 这两个类名可用（[medium.com/@williammmm.kim](https://medium.com/@williammmm.kim/custom-android-pdf-text-search-a04d2960cde3)，已核实存在这套 API；具体行为细节基于对上游 PDFBox 源码结构的训练记忆，未逐行读移植版源码确认，因 GitHub 接口在调研过程中被限流）。
- 维护状态：GitHub 仓库最后一次**实质代码提交是 2023-12-29**（之后只有依赖升级机器人 PR），`updated_at` 字段虽然显示到 2026-08（[api.github.com/repos/TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)，已核实），但那只是 issue/star 等非代码活动刷新的时间戳，不代表代码在维护。当前 125 个未关闭 issue。**判定为低活跃度/事实上停滞**，超过两年半没有功能性提交。
- 中文提取：上游 Apache PDFBox 历史上有多个 JIRA 记录涉及 CJK 提取乱码（`Invalid ToUnicode CMap in font`、缺失 CMap 导致乱码或空白），这是**提取阶段**的已知坑，会随移植版一并继承（[issues.apache.org/jira/browse/PDFBOX-2721](https://issues.apache.org/jira/browse/PDFBOX-2721) 等，已核实存在此类 issue，但未针对移植版本单独复现测试）。移植版仓库自身的 issue（#437、#66、#58、#5）多数是**写入/生成 PDF 时的中文字体嵌入问题**，与"读取提取"场景相关性较弱，但 #5（`PDFTextStripperByArea failed extracting text (font problems?)`）显示提取侧也确有字体相关故障案例。
- APK 体积：纯 Java 移植，无 native .so，是四条路线中体积增量最小的。
- 无现成的开源"重排阅读器"示例佐证。

**结论**：技术上具备重排前提（逐字符坐标），无体积代价，许可证友好，但项目实质性停滞两年半以上，中文提取存在已知风险类别（非必现，取决于具体 PDF 生产工具）。

---

### 路线 3a：Pdfium 系 Android 绑定

调研中直接看了三个主要绑定库的源码/元数据：

1. **barteksc/PdfiumAndroid**（554 星，最后 push **2024-03-19**，非 archived，Apache 2.0）——直接读取了其 `PdfiumCore.java` 源码，方法列表里**没有任何文字提取相关方法**，只有渲染、页面尺寸、链接、目录、元数据文本。也就是说这个最主流的绑定**完全不能拿文字**，更不用说坐标（已核实，直接读源码确认）。
2. **rhariskumar3/AndroidPDFPreview**（7 星，最后 push **2026-01-29**，但 README 明确写"This library is no longer actively maintained"、"Pull requests will not be accepted for new features"，Apache 2.0）——有 `extractText(pageIndex: Int, rect: RectF): String?`，但方向是"给定矩形→拿文字"，**不返回逐字符坐标**，跟重排需要的"给定文字→拿坐标"正好相反（已核实，读了其 Dokka API 文档）。
3. **kdroidFilter/ComposePdf**（67 星，Kotlin Multiplatform，含 Android target）——文档显示确实用了 `FPDFText_GetCharBox` 做字符级点击命中测试，说明**底层能力技术上可行**，但仓库**目前没有提交 LICENSE 文件**（"treat the wrapper code as unlicensed pending a decision"），且项目自我定位偏实验性（已核实，WebFetch 读取仓库页面）。

底层 pdfium C/C++ 库本身：BSD 风格许可证，通过 [bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) 持续每周构建分发（已核实其"自 2017 年起每周自动构建"的说法，来自 WebSearch 摘要，未逐条翻发布记录复核到最新一次构建日期）。pdfium 原生 API `FPDFText_GetCharBox` 确实存在且就是做这件事的正确原语。

**结论**：这是一个"底层能力够、上层封装不够"的路线——真正想要逐字符坐标，现成、维护良好、许可证清晰的 Android 封装**目前不存在**，要么用不成熟/无许可证的 ComposePdf，要么自己在 pdfium-binaries 之上写一层 JNI（工程量不小，非"拿来就用"）。

---

### 路线 3b：MuPDF 系

- License：**AGPLv3 与 Artifex 商业授权双授权**，当前文档版本 1.27.2（2026-02-18）（[mupdf.readthedocs.io/en/latest/guide/using-with-android.html](https://mupdf.readthedocs.io/en/latest/guide/using-with-android.html)、[artifex.com/licensing](https://artifex.com/licensing)，已核实）。
  - **关于个人自用是否受 AGPL 限制**：AGPL 相比普通 GPL 多出的"网络条款"（第13条），触发条件是"通过网络让远程用户与你修改后的程序交互"。对于一个**只装在自己手机上、不发布给任何第三方、也没有服务端网络交互**的 App，这个条款不会被触发；GPL/AGPL 的公开源码义务本质上是"conveying"（对外传播/分发）触发的，自己给自己用不构成传播。这个理解与业内合规厂商的公开说明一致（[fossa.com/blog/open-source-software-licenses-101-agpl-license](https://fossa.com/blog/open-source-software-licenses-101-agpl-license/)、[revenera.com](https://www.revenera.com/software-composition-analysis/glossary/what-is-the-agpl-license) 摘要，已核实存在此说法）。**但这不是正式法律意见**，如果你以后有把 APK 分享给别人、发到网上、或者做成任何形式的"发布"，AGPL 的开源义务就会被触发，需要重新评估。
- 文字位置：结构化文本 STEXT API，`fz_stext_char` 精确到字符级 bbox，这是四条路线里**颗粒度最细、最直接可用**的文本位置 API（[github.com/ArtifexSoftware/mupdf/.../structured-text.h](https://github.com/ArtifexSoftware/mupdf/blob/master/include/mupdf/fitz/structured-text.h)，已核实存在该结构定义）。
- 维护状态：主仓库最后 push **2026-08-14**（3天前），2920 星；Android 专用绑定 `mupdf-android-fitz` 最后 push **2026-06-26**，两者都已核实，是四条路线里**唯一处于高活跃维护状态**的项目。
- 中文：未找到专门的中文提取踩坑记录（也没找到反例），MuPDF 长期被广泛用于各类阅读器 App，一般印象是 CJK 支持较好，**但这一条本身是训练记忆+间接推断，未找到直接验证来源，不算确证**。
- APK 体积：F-Droid 上 "MuPDF mini" 通用包（多架构合一）当前实测下载体积 **16 MiB**（[f-droid.org/en/packages/com.artifex.mupdf.mini.app](https://f-droid.org/en/packages/com.artifex.mupdf.mini.app/)，已核实，2026-06 构建）；一篇 2016 年老博客提到的"10MB 涨到 59.9MB"数字明显过时，**不采信具体数字**，只作为"native 库会显著增大体积"这一量级判断的旧证据。若只打 arm64-v8a 单架构（小米手机现代机型基本都是纯 arm64），实际增量会明显小于 16MB 这个多架构合并数字，但没有找到单架构的具体数字（未核实）。
- 重排示例：知名的 KOReader 底层用 MuPDF，但**"真正针对可选文字 PDF 的文字级重排"**（区别于面向扫描件的 k2pdfopt 位图重排）在其社区里长期是被请求但未完全解决的难题（issue 标题即为 "PDF text reflowing"，[github.com/koreader/koreader/issues/1655](https://github.com/koreader/koreader/issues/1655)，标题及存在性已核实，但因 GitHub API 限流未能核实该 issue 当前是 open 还是 closed、最新讨论结论，此点需要你自己点开确认）。这从侧面印证：**"拿到带坐标的文字"这一步是成熟技术，"把它排版得好看"这一步在业界都还没有普遍意义上的现成解法**，需要自己写排版逻辑。

**结论**：技术能力最强、维护最活跃，但要接受 AGPL（对纯自用场景影响很小，但要认知清楚边界），且没有现成的重排引擎可以直接套用。

---

### 路线 4：PDF → EPUB 本机转换

**先回答可行性问题**：这条路线**不是伪命题**，纯离线、设备端、不依赖 Calibre/PC 的转换确实存在且能跑——`ePUBator`（也叫 abravodev/epubator / naofum/epubconverter，GPL-3.0，底层用 iText，iText 本身走 AGPLv3）就是一个真实存在、可在 Android 设备上独立运行、不需要联网、不需要桌面软件的 PDF→EPUB 转换 App（[github.com/abravodev/epubator](https://github.com/abravodev/epubator)，已核实其自述）。

但看具体能力，**它够不上项目的需求门槛**：
- 只提取"矢量文字"（PDF 里本来就是文字对象的内容），扫描件/图片里的文字提取不到。
- **图片不随文字流保留位置**：README 原话是"tries to extract images... but puts them at the page's end"——图片会被整体挪到章节/页面末尾，这正是题目里"降级方案"要求的效果，而不是"做到"的效果。
- 明确写"只适合单栏 PDF，多栏或表格效果更差"。
- 明确写"未针对亚洲文字测试过"。
- 许可证是 GPL-3.0（应用本体）+ AGPLv3（iText 依赖），个人自用同样不受影响（理由同 MuPDF 一节），但如果想要修改/二次开发它，需要遵守 GPL 系条款。

也没有找到任何设备端方案能做到"保留阅读顺序、图片真正内嵌在文字流里、支持表格"这种高质量转换——那个层级的转换（Calibre 的 `ebook-convert`、pdf2htmlEX 系流水线）全部跑在桌面/服务端，没有找到对应的纯 Android 设备端实现（未找到反例，但也不能证明完全不存在，标记为"未找到"而非"不存在"）。

**结论**：路线本身可行（推翻了"PDF→EPUB 离线转换在手机上做不到"的预设），但**现有实现（ePUBator）在图片位置保留和中文验证上明确达不到题目要求**，本质上只是"抽文字塞进 EPUB 容器"，并不比自己用路线2/3做文字提取、自己写排版逻辑更省事——反而多绕一层格式转换、多引入一层许可证和质量不可控的第三方 App。**不推荐**把这条路线作为主线，仅作为"证明可行性存在"的参考。

---

## 3. 推荐方案

**推荐：MuPDF（路线 3b）作为文字提取+坐标获取引擎，自己写重排排版逻辑，图片降级为独立浮动展示。**

理由：
1. 四条路线里，MuPDF 是**唯一同时满足"逐字符坐标可用"+"当前仍在高频活跃维护"**两个条件的（主库3天前才有提交）。PdfBox-Android 停滞两年半，pdfium 系现成绑定没一个同时具备"文字坐标"和"维护良好"，androidx.pdf 太新太不稳定还有 minSdk 缺口。
2. AGPL 对"只装在自己手机上不对外分发"的场景，按主流开源合规解读不构成实质限制（见路线3b小节的说明和来源），符合你"仅本地自用"的边界条件。
3. MuPDF 的结构化文本 API 精细到字符级 bbox，是四条路线里颗粒度最高、最直接能拿来做重排的原始数据。

需要你知道的代价：
- 没有现成的"重排引擎"可以直接拿来用——文字坐标拿到手之后，"按屏宽重新分行、分段、决定图片摆哪"这一层排版逻辑，业界（包括 KOReader 这种成熟项目）都没有普遍意义上的现成解法，需要自己写。
- Android 端需要接入 `mupdf-android-fitz`（JNI 绑定），比纯 Java 的 PdfBox-Android 多一层 native 编译/体积成本（体积具体数字见上，量级上会比纯 Java 方案增大，比 pdfium 方案相当或略大）。

**次选**：如果不想碰 AGPL 心理负担，或者更看重"纯 Java、零 native、编译最简单"，退而求其次选 **PdfBox-Android**（路线2）——文字坐标 API 是现成的，Apache 2.0 全无限制，缺点是项目停滞、中文提取有已知风险类别（未必命中你的具体 PDF），需要自己多测几份真实中文 PDF 验证提取效果再决定要不要压这条路线。

---

## 4. 兜底方案（重排+图片降级的具体做法）

不管选 MuPDF 还是 PdfBox-Android，最终落地都建议按这个降级路径走，这是本调研得出的现实结论，不是"理想情况下的方案"：

1. **文字抽取层**：用 MuPDF 的 STEXT（或 PdfBox-Android 的 `TextPosition`）拿到每一页里每个文字块/字符的坐标和阅读顺序。
2. **自己写重排逻辑**：按抽取出来的文字顺序，丢弃原 PDF 的绝对坐标，只保留"这是第几段第几行"的相对顺序关系，按当前屏幕宽度、字号、行距重新计算换行——这一步不借助任何"重排引擎"库，因为没有找到成熟可靠的现成实现，得自己写一个基础的贪心换行/分段算法（工作量不算大，是标准的文本流排版问题，不是重排版"抽取识别"那种难题）。
3. **图片处理直接走降级路径**：不去追求"图片精确嵌入某个字符位置"（这一步在所有路线里都没有现成、可靠、维护良好的实现能做到），而是提取图片对象本身（MuPDF/pdfium 都能拿到页面里的图片资源），按它们在原页面中大致所处的段落位置，插入到对应段落之间，以"独立浮动的图片块"形式展示——这正是题目里预设的降级方案，也是调研后确认唯一现实可行的路径。
4. 中文渲染：交给系统自带中文字体（思源黑体系或 MIUI/HyperOS 自带的中文字体），不需要额外嵌入字体文件，只要保证提取出来的是正确的 Unicode 字符串即可。

---

## 5. 未能验证、需要你自己确认的点

1. **小米手机实际运行的 Android/HyperOS 版本**——如果走 androidx.pdf/系统 `PdfRenderer` 这条路线（本次未推荐为主线），文字+坐标 API 只在 API 30 以上才有，API 35 以上才是原生支持，需要你自己在手机"关于本机"里确认。
2. **MuPDF/PdfBox-Android 对你实际会用到的具体中文 PDF 文件的提取效果**——本次调研找到的都是"已知问题类别"（issue 记录），不是"你的文件一定会出问题"，建议拿几份真实要读的 PDF（尤其是国内学术论文排版工具、WPS 导出、扫描件 OCR 后的 PDF 等来源）实测一遍文字提取是否完整、乱码。
3. **koreader/koreader#1655 issue 的当前状态**（open/closed）及最新讨论结论——因 GitHub API 限流未能读取，建议你自己打开这个链接看一眼，判断"文字级重排"在成熟项目里到底卡在哪。
4. **MuPDF 单一 arm64-v8a 架构下的实际 APK 体积增量**——本次只核实到多架构合并包 16MiB 这个数字，没找到单架构拆分后的具体数字。
5. **androidx.pdf 的 `getSelectionBounds()` 等新 API 在国产 ROM（尤其是 HyperOS 对系统 PDF 渲染服务的定制/裁剪）上是否可用**——AndroidX 官方文档针对的是原生 AOSP 行为，小米对系统组件有无魔改未核实，如果后续真考虑走这条路线，建议先在目标机型上写个最小 Demo 验证。
6. **ComposePdf（kdroidFilter）当前是否已经补上 LICENSE 文件**——调研时读到的是"尚未提交"，这类项目状态变化快，如果后续想参考它的 JNI 封装思路，建议使用前重新确认一次许可证状态。

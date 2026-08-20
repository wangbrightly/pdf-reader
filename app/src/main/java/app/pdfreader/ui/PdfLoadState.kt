package app.pdfreader.ui

import android.graphics.Bitmap

/**
 * "选文件→抽取→重排→显示"这条流程的加载状态：Idle / Loading / Success / Error。
 *
 * 不依赖 Activity 生命周期或任何 Android View API，纯数据类，可以在 JVM 单元测试里
 * 直接验证状态流转。MainActivity 只负责根据这四种状态更新 ProgressBar/内容容器/Toast
 * 这层纯粹的展示胶水代码，不做判断逻辑。
 *
 * ## 2026-08-20：Success 从"携带全文档 blocks"改成"只携带 pageCount"
 *
 * 文字/图片真正按需加载（RecyclerView 窗口式重构，见
 * `/Users/mac/.claude/plans/fizzy-snuggling-cloud.md`）上线后，"打开成功"这件事不
 * 再需要预先算出任何展示块——`RecyclerView.Adapter.getItemCount()` 只需要
 * [Success.pageCount]，具体每一页的内容是 [app.pdfreader.extract.PdfTextExtractor
 * .Session.loadPage] 按需算的，见 [app.pdfreader.ui.PdfPageAdapter]。
 *
 * **已知的行为变化（如实记录）**：旧版判断"这份 PDF 是不是抽不出任何可显示内容"
 * 靠"全文档抽完之后 blocks 是不是空的"（比如纯扫描图片、图片也抽取失败的文档）；
 * 新版只知道 `pageCount`，没法在打开阶段就确认"每一页是不是真的都没内容"——
 * `pageCount > 0` 就判定为成功，一份"有页数、但每页都抽不出任何文字/图片"的文档
 * 现在会打开成一个空白的可滚动页面，不再弹"未能提取到内容"的错误提示。这是按需
 * 加载这个架构方向下的必然取舍（要在打开阶段就知道"是不是整份都是空的"，就得先
 * 把所有页都看一遍，跟"按需"矛盾），比"完全不管这种边界情况"更实际的方案（比如
 * 只探测第一页）留给以后有需要再做。
 */
sealed interface PdfLoadState {
    data object Idle : PdfLoadState
    data object Loading : PdfLoadState
    data class Success(val pageCount: Int) : PdfLoadState
    data class Error(val message: String) : PdfLoadState
}

/**
 * 一屏内容里排在一起展示的最小单位：一段已经按当前行宽重排好、可以直接塞进一个
 * TextView 的文字（[Text]），或者一张已经解码好、可以直接塞进一个 ImageView 的图片
 * （[Image]）。谁在前谁在后由 [app.pdfreader.extract.PdfTextExtractor.Session
 * .loadPage] 内部按页内顺序决定（复用 [app.pdfreader.extract.ImagePlacement]
 * 的定位逻辑）。
 *
 * 2026-08-20：去掉了 `Placeholder` 变体——"这一页还没加载出来"这件事现在由
 * [app.pdfreader.ui.PdfPageAdapter] 在 `ViewHolder` 层级直接摆一个占位 View 处理，
 * 不再是这里要表达的一种展示块类型（`loadPage` 只会产出已经加载好的内容，见该方法
 * KDoc）。
 *
 * 2026-08-20 同日再加：[Text.isHeading]——用户要求"标题要加粗"，判断依据是这段文字
 * 在原文档里的字号是否明显大于本页正文字号、或者字体本身标了加粗，两个信号满足
 * 其中一个就算标题（用户明确选择），具体计算见
 * [app.pdfreader.extract.PdfTextExtractor.classifyHeadings]。
 */
sealed interface DisplayBlock {
    data class Text(val text: String, val isHeading: Boolean = false) : DisplayBlock
    data class Image(val bitmap: Bitmap) : DisplayBlock
}

/**
 * 把"抽取 + 重排这一步跑完之后拿到的结果/异常"转换成 [PdfLoadState]。
 *
 * 对应需求 5"抽取失败（文件无效/加密打不开等）要弹提示，不能崩溃闪退"——这里保证的是
 * "任何异常都会被 [runCatching] 兜住、转换成 Error 状态，绝不会让异常继续往外传播
 * 到主线程炸掉 App"这一层逻辑；具体"怎么弹提示"（Toast/对话框）是 UI 胶水代码的事，
 * 不在这个纯函数的职责范围内。这条保证同样覆盖图片抽取失败的情况——单张图片抽取
 * 失败在 [app.pdfreader.extract.PdfTextExtractor] 内部就已经被跳过了（不会抛到这里），
 * 这里的 [runCatching] 兜的是更严重的、导致整份文档都读不出来的异常。
 *
 * 页数为 0（理论上不该出现，`PDDocument` 至少有 1 页才算合法 PDF）也当作 Error 处理，
 * 见 [PdfLoadState.Success] KDoc"已知的行为变化"一节——"整份文档一个字都抽不出来"
 * 这种更细粒度的空结果判断，按需加载架构下不再能在打开阶段就确认。
 */
object PdfLoadReducer {
    private const val EMPTY_RESULT_MESSAGE = "未能从这份 PDF 中提取到可显示的文字或图片内容（可能是扫描图片、无文字层的 PDF）"
    private const val DEFAULT_ERROR_MESSAGE = "打开 PDF 失败，请确认文件是有效的 PDF 且未加密"

    fun fromResult(result: Result<Int>): PdfLoadState =
        result.fold(
            onSuccess = { pageCount ->
                if (pageCount <= 0) {
                    PdfLoadState.Error(EMPTY_RESULT_MESSAGE)
                } else {
                    PdfLoadState.Success(pageCount)
                }
            },
            onFailure = { throwable ->
                PdfLoadState.Error(throwable.message?.takeIf { it.isNotBlank() } ?: DEFAULT_ERROR_MESSAGE)
            },
        )
}

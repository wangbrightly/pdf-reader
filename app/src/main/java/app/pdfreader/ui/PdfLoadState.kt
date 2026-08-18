package app.pdfreader.ui

import android.graphics.Bitmap

/**
 * "选文件→抽取→重排→显示"这条流程的加载状态：Idle / Loading / Success / Error。
 *
 * 不依赖 Activity 生命周期或任何 Android View API，纯数据类，可以在 JVM 单元测试里
 * 直接验证状态流转。MainActivity 只负责根据这四种状态更新 ProgressBar/内容容器/Toast
 * 这层纯粹的展示胶水代码，不做判断逻辑。
 *
 * ## 2026-08-18 增量：Success 携带的是 [DisplayBlock] 列表，不再是纯文字行
 *
 * 图片浮动展示这个增量要求"图片穿插在大致对应的文字段落之间"，单个 TextView 塞
 * 一整段纯文字做不到这件事——展示层需要知道"这一块是文字还是图片、按什么顺序摆"，
 * 所以 [Success.blocks] 从 `List<String>`（重排后的文字行）换成了 `List<DisplayBlock>`
 * （文字段落块 + 图片块的有序混合）。这是本次改动里"必要"的公开签名变更：
 * [app.pdfreader.MainActivity] 是唯一调用方，已同步更新为按 [DisplayBlock] 逐个渲染成
 * 一个纵向 LinearLayout 里的子 View（文字段落→TextView，图片→支持双指缩放的
 * ImageView），不再是单个 TextView。
 */
sealed interface PdfLoadState {
    data object Idle : PdfLoadState
    data object Loading : PdfLoadState
    data class Success(val blocks: List<DisplayBlock>) : PdfLoadState
    data class Error(val message: String) : PdfLoadState
}

/**
 * 一屏内容里排在一起展示的最小单位：要么是一段已经按当前行宽重排好、可以直接塞进一个
 * TextView 的文字（[Text]），要么是一张已经解码好、可以直接塞进一个 ImageView 的图片
 * （[Image]）。谁在前谁在后由 [app.pdfreader.MainActivity] 里的组装逻辑决定，依据是
 * [app.pdfreader.extract.ExtractedImage.afterParagraphIndex]（图片该插在哪个段落之后，
 * 见 [app.pdfreader.extract.ImagePlacement]）。
 */
sealed interface DisplayBlock {
    data class Text(val text: String) : DisplayBlock
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
 * 空列表（比如整份 PDF 一个字都没抽出来、也没有任何图片，例如纯扫描图片没有文字层
 * 且图片本身也抽取失败）也当作 Error 处理，而不是显示一个空白页——那对用户来说是
 * "看起来卡住了"而不是"正常打开了空文档"。
 */
object PdfLoadReducer {
    private const val EMPTY_RESULT_MESSAGE = "未能从这份 PDF 中提取到可显示的文字或图片内容（可能是扫描图片、无文字层的 PDF）"
    private const val DEFAULT_ERROR_MESSAGE = "打开 PDF 失败，请确认文件是有效的 PDF 且未加密"

    fun fromResult(result: Result<List<DisplayBlock>>): PdfLoadState =
        result.fold(
            onSuccess = { blocks ->
                if (blocks.isEmpty()) {
                    PdfLoadState.Error(EMPTY_RESULT_MESSAGE)
                } else {
                    PdfLoadState.Success(blocks)
                }
            },
            onFailure = { throwable ->
                PdfLoadState.Error(throwable.message?.takeIf { it.isNotBlank() } ?: DEFAULT_ERROR_MESSAGE)
            },
        )
}

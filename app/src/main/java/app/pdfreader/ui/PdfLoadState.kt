package app.pdfreader.ui

/**
 * "选文件→抽取→重排→显示"这条流程的加载状态：Idle / Loading / Success / Error。
 *
 * 不依赖 Activity 生命周期或任何 Android View API，纯数据类，可以在 JVM 单元测试里
 * 直接验证状态流转。MainActivity 只负责根据这四种状态更新 ProgressBar/TextView/Toast
 * 这层纯粹的展示胶水代码，不做判断逻辑。
 */
sealed interface PdfLoadState {
    data object Idle : PdfLoadState
    data object Loading : PdfLoadState
    data class Success(val lines: List<String>) : PdfLoadState
    data class Error(val message: String) : PdfLoadState
}

/**
 * 把"抽取 + 重排这一步跑完之后拿到的结果/异常"转换成 [PdfLoadState]。
 *
 * 对应需求 5"抽取失败（文件无效/加密打不开等）要弹提示，不能崩溃闪退"——这里保证的是
 * "任何异常都会被 [runCatching] 兜住、转换成 Error 状态，绝不会让异常继续往外传播
 * 到主线程炸掉 App"这一层逻辑；具体"怎么弹提示"（Toast/对话框）是 UI 胶水代码的事，
 * 不在这个纯函数的职责范围内。
 *
 * 空列表（比如整份 PDF 一个字都没抽出来，例如纯扫描图片没有文字层）也当作 Error 处理，
 * 而不是显示一个空白页——那对用户来说是"看起来卡住了"而不是"正常打开了空文档"。
 */
object PdfLoadReducer {
    private const val EMPTY_RESULT_MESSAGE = "未能从这份 PDF 中提取到可显示的文字内容（可能是扫描图片、无文字层的 PDF）"
    private const val DEFAULT_ERROR_MESSAGE = "打开 PDF 失败，请确认文件是有效的 PDF 且未加密"

    fun fromResult(result: Result<List<String>>): PdfLoadState =
        result.fold(
            onSuccess = { lines ->
                if (lines.isEmpty()) {
                    PdfLoadState.Error(EMPTY_RESULT_MESSAGE)
                } else {
                    PdfLoadState.Success(lines)
                }
            },
            onFailure = { throwable ->
                PdfLoadState.Error(throwable.message?.takeIf { it.isNotBlank() } ?: DEFAULT_ERROR_MESSAGE)
            },
        )
}

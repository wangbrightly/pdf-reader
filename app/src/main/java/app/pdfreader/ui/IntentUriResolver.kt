package app.pdfreader.ui

import android.content.Intent
import android.net.Uri

/**
 * 从外部传入的 [Intent] 里解析出要打开的 PDF 文件 Uri。
 *
 * 只覆盖"用户从别的 App（文件管理器/浏览器/IM）用……打开或分享一个 PDF 过来"这条
 * 路径——系统会用 ACTION_VIEW 隐式 Intent 启动 MainActivity，Uri 放在
 * [Intent.getData] 里，这是本函数存在的意义。
 *
 * App 内点击"打开 PDF"按钮走的是 `ActivityResultContracts.OpenDocument()`，那条路径
 * 由 Activity Result API 直接把 Uri 交回回调，不经过这个函数，也不需要为它写解析逻辑。
 *
 * 只认 [Intent.ACTION_VIEW]；其余 action（比如 App 图标正常启动带的 ACTION_MAIN）
 * 一律返回 null，调用方据此判断"这次启动是不是带着一个要打开的文件"。
 */
object IntentUriResolver {
    fun resolvePdfUri(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_VIEW) return null
        return intent.data
    }
}

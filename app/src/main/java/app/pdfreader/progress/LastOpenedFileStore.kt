package app.pdfreader.progress

import android.content.Context
import android.net.Uri

/**
 * 记住"最近一次成功打开的文件"，用来在冷启动时自动恢复正在读的文档。
 *
 * ## 背景（2026-08-21 真机诊断）
 *
 * 真机反馈"这本书还有问题"，排查发现根本不是某一页渲染错——是文档整个消失了，
 * App 回到了最初的空白首页。查 `logcat -b events` 找到确凿证据：
 * ```
 * am_kill: [0,pid,app.pdfreader,905,stop app.pdfreader due to LockScreenClean,...]
 * ```
 * MIUI 的"锁屏清理"会在锁屏后主动杀掉后台 App 进程，且这个清理**连带把 Recents
 * 任务卡片一起清掉**——`MainActivity` 已有的"配置变化重建后恢复文档"机制
 * （[MainActivity] 类 KDoc 那一节，靠 `onSaveInstanceState` 的 `Bundle`）只在"同一个
 * 任务被重新调度到前台"时才生效，Recents 卡片被清掉之后再打开 App 图标是彻底的冷
 * 启动，`savedInstanceState` 必为 null，那条恢复路径根本不会触发。安卓自己的
 * OOM/低内存回收也是同样后果——这不是 MIUI 独有的边界情况，是"进程可能在任何时候
 * 被系统杀掉、且不保证任务卡片还在"这个安卓平台级别的常态，只是 MIUI 更激进、更
 * 容易撞见。
 *
 * 修法不是去对抗系统杀进程（前台 Service 之类的手段在 MIUI 上也不保证有效，是一场
 * 打不赢的仗）——而是让"重新打开"这件事本身几乎免费：只要还记得"上次打开的是哪个
 * 文件"，[app.pdfreader.extract.PdfTextExtractor.Session.open] 本来就已经优化到
 * 大多数文档 0.1–1.5 秒能打开（见 [MainActivity] 类 KDoc"文字/图片真正按需加载"一
 * 节），冷启动时自动重新打开、`ReadingProgressStore` 再把页码带回来，效果上跟用户
 * 从没被打断过一样。
 *
 * ## 只记"通过系统文件选择器主动挑的文件"，不记"别的 App 分享过来的文件"
 *
 * `content://` Uri 的读权限默认是一次性的，`ACTION_OPEN_DOCUMENT`（`MainActivity`
 * 里的 `openDocumentLauncher`）允许调用
 * `ContentResolver.takePersistableUriPermission` 换成跨进程重启依然有效的持久授权；
 * 别的 App 用 `ACTION_VIEW` 分享过来的 Uri 走的是临时授权，对它调用
 * `takePersistableUriPermission` 本身就会直接抛 `SecurityException`——这类"临时分享
 * 过来看一眼"的文件语义上也不适合冷启动时不问自取地重新弹出来，`MainActivity` 只在
 * 系统选择器这条路径上才会保存/尝试恢复，见该类 `loadPdf` 的 `rememberAsLastOpened`
 * 参数。
 *
 * ## 存储结构
 * 一个 SharedPreferences 文件，只有一条记录（不像 [ReadingProgressStore] 要按文件
 * 区分——这里只关心"最近一次"，新值直接覆盖旧值）。
 */
object LastOpenedFileStore {
    private const val PREFS_NAME = "last_opened_file"
    private const val KEY_URI = "uri"

    fun save(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri.toString())
            .apply()
    }

    /** 没保存过、或者保存过但已经被 [clear] 时都返回 null。 */
    fun load(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_URI, null) ?: return null
        return Uri.parse(stored)
    }

    /**
     * 冷启动自动恢复失败（文件被删/移动、权限被收回……）时用来清掉记录，避免下次冷
     * 启动继续对着一个打不开的文件重试、每次都弹一次没人问的错误提示。
     */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_URI)
            .apply()
    }
}

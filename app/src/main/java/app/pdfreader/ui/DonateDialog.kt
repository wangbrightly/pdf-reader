package app.pdfreader.ui

import android.app.Activity
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.pdfreader.R

/**
 * 「赞赏作者」弹窗：显示微信赞赏码，并提供"保存到相册"。
 *
 * 为什么要保存按钮：赞赏码显示在本机屏幕上时，本机微信扫不到自己的屏幕，
 * 只能先存进相册，再用微信「扫一扫 → 相册」识别。
 * Android 10+ 用 MediaStore 写公共相册不需要存储权限；更老的系统提示截图。
 */
object DonateDialog {

    fun show(activity: Activity) {
        val image = ImageView(activity).apply {
            setImageResource(R.drawable.donate_qr)
            adjustViewBounds = true
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            contentDescription = activity.getString(R.string.donate_title)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.donate_title)
            .setView(image)
            .setPositiveButton(R.string.donate_save) { _, _ -> saveToGallery(activity) }
            .setNegativeButton(R.string.donate_close, null)
            .show()
    }

    private fun saveToGallery(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(activity, R.string.donate_save_old_android, Toast.LENGTH_LONG).show()
            return
        }
        val ok = runCatching {
            val bitmap = BitmapFactory.decodeResource(activity.resources, R.drawable.donate_qr)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "赞赏码_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("insert failed")
            resolver.openOutputStream(uri).use { out ->
                checkNotNull(out)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        }.isSuccess
        Toast.makeText(
            activity,
            if (ok) R.string.donate_saved else R.string.donate_save_failed,
            Toast.LENGTH_LONG,
        ).show()
    }
}

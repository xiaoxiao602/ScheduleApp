package com.gzuschedule.app.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import java.io.File

/**
 * 用户自定义资料（ADR-012）。
 *
 * 存两类东西：
 *  1. 自定义显示名 —— 走 MetaDao（键值表），与其它元数据同一处
 *  2. 自定义头像   —— **文件**，复制到 App 私有目录，只存文件名到 meta
 *
 * ⚠️ 为什么头像存私有目录而不是数据库：
 *  - 图片是二进制大对象，塞进 Room 会让行变胖、查询变慢
 *  - 私有目录(`filesDir`)对其它 App 不可见，**无需任何存储权限**
 *  - 只需在 meta 里记一个文件名，读取时拼路径即可
 *
 * ⚠️ 权限说明：
 * 选图用系统的 PickVisualMedia（照片选择器），由系统进程读取用户选中的
 * 那一张并授予临时访问权，**App 无需 READ_MEDIA_IMAGES 等权限**。
 */
object UserProfileStore {

    private const val AVATAR_DIR = "avatar"
    private const val AVATAR_FILE = "custom_avatar"

    /** 头像存放目录（App 私有） */
    private fun dir(ctx: Context): File =
        File(ctx.filesDir, AVATAR_DIR).apply { if (!exists()) mkdirs() }

    /**
     * 把用户选中的图片**裁成正方形**后复制进私有目录（ADR-052）。
     *
     * ⚠️ 用户反馈：「头像选择后没有自定义裁切功能」。
     *    旧实现直接 `copyTo` 原图 —— 无论原图是 16:9 还是长竖图，
     *    渲染成圆形头像时都会被**拉伸变形**。
     *
     * 做法：取原图**中心的方形区域**（center-crop），再缩放到 [OUT_SIZE]²
     * 保存。这样：
     *   * 任何比例的图都不会变形
     *   * 头像文件固定 256×256，省空间（原来可能存几 MB 的原图）
     *
     * ⚠️ 为什么不做"可拖动的手动裁切框"：
     *    那需要自绘 View + 手势 + 变换矩阵，代码量与风险都高；
     *    而头像显示尺寸只有 36~72dp，中心裁切在绝大多数情况下观感一致。
     *    若后续需要，可在本函数基础上加一个偏移参数。
     *
     * @return 成功返回 true
     */
    fun saveAvatar(ctx: Context, sourceUri: String): Boolean = runCatching {
        val input = ctx.contentResolver.openInputStream(sourceUri.toUri())
            ?: return false

        // 1) 先只读边界（inJustDecodeBounds），避免把整张大图解到内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        // 2) 计算采样率，让解码后的短边不小于 OUT_SIZE（保证放大后不糊）
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, OUT_SIZE)
        }
        val decoded = ctx.contentResolver.openInputStream(sourceUri.toUri())?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return false

        // 3) 中心方形裁切 → 缩放 → 存 PNG
        val side = minOf(decoded.width, decoded.height)
        val x = (decoded.width - side) / 2
        val y = (decoded.height - side) / 2
        val cropped = Bitmap.createBitmap(decoded, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, OUT_SIZE, OUT_SIZE, true)

        File(dir(ctx), AVATAR_FILE).outputStream().use { outs ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, outs)
        }

        // 及时释放，避免大图占内存
        if (scaled !== cropped) cropped.recycle()
        if (cropped !== decoded) decoded.recycle()
        scaled.recycle()
        true
    }.getOrDefault(false)

    /** 头像输出边长（px）。够 72dp @3x 显示，且文件很小。 */
    private const val OUT_SIZE = 256

    /** 取 2 的幂采样率，使解码结果不小于 [target]（避免过度降采样后放大发虚）。 */
    private fun calcInSampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var shortest = minOf(w, h)
        while (shortest / 2 >= target) {
            shortest /= 2
            sample *= 2
        }
        return sample
    }

    /** 自定义头像文件；不存在返回 null。 */
    fun avatarFile(ctx: Context): File? =
        File(dir(ctx), AVATAR_FILE).takeIf { it.isFile }

    fun hasCustomAvatar(ctx: Context): Boolean = avatarFile(ctx) != null

    /** 删除自定义头像，回到「姓氏头像」默认样式。 */
    fun clearAvatar(ctx: Context) {
        runCatching { File(dir(ctx), AVATAR_FILE).delete() }
    }

    /**
     * 取「姓氏字」用于默认头像。
     *
     * 中文取首字（张同学 -> 李）；英文取首字母大写（xiaoxiao -> X）。
     * 空名回退为「?」。
     */
    fun initial(fullName: String?): String {
        val n = fullName?.trim().orEmpty()
        if (n.isEmpty()) return "?"
        val c = n.first()
        return if (c.isLetter()) c.uppercaseChar().toString() else "?"
    }
}

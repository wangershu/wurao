package com.adhammer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用图标。
 *
 * 参考项目 HyperIsland 的列表行左侧是真实 App 图标（40dp 圆角方），
 * 这里同样用 PackageManager 取图标；取不到就退回一个同尺寸的浅色占位块，避免行高抖动。
 *
 * 图标按包名缓存在进程内 —— 列表滚动时重复调用不用反复 IPC 到 PackageManager。
 */
private val iconCache = HashMap<String, ImageBitmap?>()

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) { loadIcon(ctx, pkg) }
    val shape = RoundedCornerShape(size * 0.28f)
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size).clip(shape)
        )
    } else {
        // 占位：同尺寸的浅灰块，保证行高一致
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        )
    }
}

private fun loadIcon(ctx: Context, pkg: String): ImageBitmap? {
    if (iconCache.containsKey(pkg)) return iconCache[pkg]
    val bmp = runCatching {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bitmap = if (d is BitmapDrawable && d.bitmap != null) {
            d.bitmap
        } else {
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: 144
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: 144
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out ->
                val canvas = Canvas(out)
                d.setBounds(0, 0, w, h)
                d.draw(canvas)
            }
        }
        bitmap.asImageBitmap()
    }.getOrNull()
    iconCache[pkg] = bmp
    return bmp
}

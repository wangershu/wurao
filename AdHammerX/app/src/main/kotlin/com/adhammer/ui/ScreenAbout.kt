package com.adhammer.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.BuildConfig
import com.adhammer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.TimeUnit

/**
 * 关于页。
 *
 * 结构照参考项目 HyperIsland 的 AboutPage：
 * 顶部一块 Hero（渐变背景 + 大图标 + 应用名 + 版本，滚动时淡出），
 * 下面是「开发者 / 交流 / 模块 / 项目 / 第三方库 / 声明」几个分组。
 * 参考项目的 Hero 用了 miuix-blur 做纹理折射，我们没引那个库，
 * 走的是它的 fallback 分支：直接用渐变给图标和文字上色（[Modifier.gradientMask]）。
 *
 * 链接信息集中在文件末尾的几个常量里，换仓库只改那几行。
 */
@Composable
fun AboutScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val heroHeight = LocalConfiguration.current.screenHeightDp.dp * HERO_HEIGHT_FRACTION
    val heroHeightPx = with(density) { heroHeight.toPx() }

    val scrollOffset by remember {
        derivedStateOf { listState.firstVisibleItemScrollOffset.toFloat() }
    }
    val fade = (1f - scrollOffset / heroHeightPx).coerceIn(0f, 1f)

    var checking by remember { mutableStateOf(false) }
    var updateHint by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // 进入关于页就静默查一次，结果只更新行摘要，不弹窗打扰
        checking = true
        updateHint = withContext(Dispatchers.IO) { probeLatest() }
        checking = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 顶部渐变背景：跟着 Hero 一起淡出，底部再做一层渐隐，
        // 不然渐变块和下面的白底页面之间会有一条硬边。
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight + 120.dp)
                .alpha(fade)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            drawAboutGradientField(size)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                    startY = size.height * 0.55f,
                    endY = size.height
                ),
                blendMode = BlendMode.DstIn
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = Kit.pad()
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heroHeight)
                        .alpha(fade),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_hammer),
                        contentDescription = null,
                        modifier = Modifier
                            .size(88.dp)
                            .gradientMask(heroBrush())
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = APP_NAME,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.gradientMask(heroBrush())
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            item { Kit.Section("开发者") }
            item { DeveloperCard(ctx) }

            item { Kit.Section("交流") }
            item {
                Kit.RowGroup {
                    Kit.PlainRow(
                        title = "问题反馈",
                        summary = "规则失效、闪退，去 issues 提",
                        icon = MiuixIcons.Messages,
                        onClick = { openUrl(ctx, ISSUES) },
                        end = { ExternalIcon() }
                    )
                }
            }

            item { Kit.Section("模块") }
            item {
                Kit.RowGroup {
                    Kit.ArrowRow(
                        title = "备份与恢复",
                        summary = "导出 / 导入全部规则与配置",
                        icon = MiuixIcons.Backup,
                        onClick = {
                            Navigator.switch(Navigator.Tab.SETTINGS)
                            Navigator.go(Dest.SettingsBackup)
                        }
                    )
                    Kit.PlainRow(
                        title = "检查更新",
                        summary = updateHint.ifBlank { "正在检查…" },
                        icon = MiuixIcons.Update,
                        enabled = !checking,
                        onClick = {
                            if (checking) return@PlainRow
                            scope.launch {
                                checking = true
                                val msg = withContext(Dispatchers.IO) { probeLatest() }
                                updateHint = msg
                                checking = false
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        end = {
                            if (checking) {
                                Box(
                                    modifier = Modifier.padding(end = 8.dp).size(26.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(size = 20.dp)
                                }
                            }
                        }
                    )
                    Kit.PlainRow(
                        title = "发布页",
                        summary = "下载最新 APK",
                        icon = MiuixIcons.Info,
                        onClick = { openUrl(ctx, RELEASES) },
                        end = { ExternalIcon() }
                    )
                }
            }

            item { Kit.Section("项目") }
            item {
                Kit.RowGroup {
                    Kit.PlainRow(
                        title = "仓库",
                        summary = REPO,
                        icon = MiuixIcons.Link,
                        onClick = { openUrl(ctx, REPO) },
                        end = { ExternalIcon() }
                    )
                    Kit.PlainRow(
                        title = "更新日志",
                        summary = CHANGES,
                        icon = MiuixIcons.Info,
                        onClick = { openUrl(ctx, CHANGES) },
                        end = { ExternalIcon() }
                    )
                }
            }

            item { Kit.Section("第三方库") }
            item {
                Kit.Group {
                    LibRow("miuix", "MIUI 风格 Compose 组件库")
                    LibRow("libxposed", "LSPosed API 102")
                    LibRow("Gson", "规则 JSON 解析")
                    LibRow("OkHttp", "订阅与特征库下载")
                }
            }

            item { Kit.Section("版本信息") }
            item {
                Kit.Group {
                    Kit.KeyValue("版本", BuildConfig.VERSION_NAME)
                    Kit.KeyValue("构建类型", BuildConfig.BUILD_TYPE)
                    Kit.KeyValue("包名", BuildConfig.APPLICATION_ID)
                    Kit.KeyValue("框架", UiState.framework)
                    Kit.KeyValue("Hook API", "LSPosed 102（libxposed）")
                }
            }

            item { Kit.Section("声明") }
            item {
                Kit.Group {
                    Text(
                        text = "本模块仅供学习交流与技术研究使用，请勿用于商业或违法用途。" +
                            "因使用本模块产生的任何后果由使用者自行承担。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        // 悬浮返回键：Hero 上不便再压一条实体工具栏
        IconButton(
            onClick = { Navigator.back() },
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        ) {
            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
        }
    }
}

@Composable
private fun ExternalIcon() {
    Icon(
        imageVector = MiuixIcons.Link,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = MiuixTheme.colorScheme.onSurfaceVariantActions
    )
}

/** 开发者卡片：头像位用图标占位，点进 GitHub 主页 */
@Composable
private fun DeveloperCard(ctx: Context) {
    Kit.ArrowRow(
        title = DEVELOPER_NAME,
        summary = DEVELOPER_HANDLE,
        icon = MiuixIcons.Community,
        onClick = { openUrl(ctx, DEVELOPER_URL) }
    )
}

@Composable
private fun LibRow(name: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(
                text = desc,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

// ==================== 渐变 Hero 的绘制（参考项目 AboutPage 的无 blur 分支） ====================

/** Hero 用的斜向渐变（比背景那层更深，压在同色系浅底上才看得清） */
@Composable
private fun heroBrush(): Brush = Brush.linearGradient(
    colors = listOf(Color(0xFF2F5BD8), Color(0xFF6535C8), Color(0xFFD0367A)),
    start = Offset(0f, 0f),
    end = Offset(620f, 180f)
)

/**
 * 用画笔给内容上色：先画内容，再用 SrcIn 把渐变压上去。
 * 参考项目在支持 shader 时走纹理折射，这里是它的降级路径（[CompositingStrategy.Offscreen] 必备）。
 */
private fun Modifier.gradientMask(brush: Brush): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    onDrawWithContent {
        drawContent()
        drawRect(brush = brush, blendMode = BlendMode.SrcIn)
    }
}

/** 参考项目的 drawAboutGradientField 简化版：一层线性渐变 + 三个径向光斑 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAboutGradientField(fieldSize: androidx.compose.ui.geometry.Size) {
    val palette = listOf(
        Color(0xFF6C8CFF), Color(0xFF9B6CFF), Color(0xFFFF7BAE), Color(0xFF7AD9FF)
    )
    drawRect(
        brush = Brush.linearGradient(
            colors = palette.map { it.copy(alpha = 0.46f) },
            start = Offset.Zero,
            end = Offset(fieldSize.width, fieldSize.height)
        )
    )
    val radius = fieldSize.maxDimension * 0.62f
    listOf(
        Offset(fieldSize.width * 0.18f, fieldSize.height * 0.20f),
        Offset(fieldSize.width * 0.82f, fieldSize.height * 0.55f),
        Offset(fieldSize.width * 0.22f, fieldSize.height * 0.72f)
    ).forEachIndexed { i, center ->
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette[i].copy(alpha = 0.72f), palette[i].copy(alpha = 0f)),
                center = center,
                radius = radius
            ),
            center = center,
            radius = radius
        )
    }
}

// ==================== 检查更新 ====================

/**
 * 查 GitHub 上的最新 release。
 *
 * 私有仓库会返回 404，这时如实告诉用户「查不到」并让他去发布页，不假装没更新。
 */
private fun probeLatest(): String {
    val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()
    return runCatching {
        client.newCall(Request.Builder().url(API_LATEST).build()).execute().use { resp ->
            if (!resp.isSuccessful) return "暂时查不到更新信息（仓库可能是私有的）"
            val body = resp.body?.string().orEmpty()
            val tag = JSONObject(body).optString("tag_name", "").trim().removePrefix("v")
            if (tag.isBlank()) return "暂时查不到更新信息"
            if (tag == BuildConfig.VERSION_NAME) "已是最新版 ${BuildConfig.VERSION_NAME}"
            else "有新版本 $tag，点发布页下载"
        }
    }.getOrElse { "检查更新失败：${it.message ?: "网络不可达"}" }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private const val APP_NAME = "大圣净化"
private const val REPO = "https://github.com/wangershu/adhammer"
private const val RELEASES = "https://github.com/wangershu/adhammer/releases"
private const val ISSUES = "https://github.com/wangershu/adhammer/issues"
private const val CHANGES = "https://github.com/wangershu/adhammer/commits/main"
private const val API_LATEST = "https://api.github.com/repos/wangershu/adhammer/releases/latest"
private const val DEVELOPER_NAME = "wangershu"
private const val DEVELOPER_HANDLE = "@wangershu"
private const val DEVELOPER_URL = "https://github.com/wangershu"
private const val HERO_HEIGHT_FRACTION = 0.45f

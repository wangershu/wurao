package com.adhammer.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 关于页，结构完全对齐参考项目 HyperIsland 的 AboutPage：
 *
 * - 顶层是一块会流动的渐变背景（4 团光斑随时间漂移，每 12s 换一组配色），
 *   高度 = Hero + 180dp，底部用 DstIn 竖向渐隐，不带硬边；
 * - 上滑时背景按 0.12 的视差往上游走并整体淡出（389dp 内淡完），
 *   列表内容滑到它上面，把背景「盖住」；滑到底时直接归零；
 * - Hero（图标 + 应用名 + 版本）叠在背景最上层，从 hero 高度的 25% 处开始淡出、缩到 0.9；
 * - 正文首项先空出 hero 的高度，再进「开发者 / 交流 / 模块 / 项目 / 版本 / 声明」。
 */
@Composable
fun AboutScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    val heroHeight = LocalConfiguration.current.screenHeightDp.dp * HERO_HEIGHT_FRACTION
    val heroHeightPx = with(density) { heroHeight.toPx() }
    val backgroundFadeDistance = with(density) { 389.dp.toPx() }
    val logoFadeStart = heroHeightPx * 0.25f
    val logoFadeDistance = heroHeightPx * 0.35f

    val scrollOffset by remember(listState, heroHeightPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                heroHeightPx
            } else {
                listState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }
    val reachedListEnd by remember(listState) {
        derivedStateOf {
            !listState.canScrollForward &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        }
    }
    val backgroundAlpha = if (reachedListEnd) {
        0f
    } else {
        1f - (scrollOffset / backgroundFadeDistance).coerceIn(0f, 1f)
    }
    val logoProgress = if (reachedListEnd) {
        1f
    } else {
        ((scrollOffset - logoFadeStart) / logoFadeDistance).coerceIn(0f, 1f)
    }
    val logoAlpha = 1f - logoProgress
    val logoScale = 1f - logoProgress * 0.1f

    val animationTime = rememberAboutAnimationTime()
    val gradientColors = animatedGradientColors(animationTime, isSystemInDarkTheme())

    var checking by remember { mutableStateOf(false) }
    var updateHint by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // 进入关于页静默查一次，结果只更新行摘要，不弹窗打扰
        checking = true
        updateHint = withContext(Dispatchers.IO) { probeLatest() }
        checking = false
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // 渐变背景：随滚动视差上移 + 淡出
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight + 180.dp)
                .alpha(backgroundAlpha)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationY = -listState.firstVisibleItemScrollOffset * 0.12f
                }
        ) {
            drawAboutGradientField(animationTime, gradientColors, size)
            // 底部渐隐：渐变块和下面的页面底色之间不留硬边
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.White,
                        0.68f to Color.White,
                        1f to Color.Transparent
                    )
                ),
                blendMode = BlendMode.DstIn
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 28.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(heroHeight + DEVELOPER_TOP_GAP))
                    Kit.Section("开发者")
                    DeveloperCard(ctx)
                }
            }

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

        // Hero 叠在背景之上，不进列表，这样它能独立淡出/缩放
        AboutHero(
            logoAlpha = logoAlpha,
            logoScale = logoScale,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .height(heroHeight)
                .padding(horizontal = 16.dp)
        )

        // 悬浮返回键：给个浅底，压在渐变上也看得清
        IconButton(
            onClick = { Navigator.back() },
            modifier = Modifier
                .padding(start = 8.dp, top = 8.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.55f))
        ) {
            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
        }
    }
}

/** Hero：图标 + 应用名 + 版本，整体居中偏下一点（和参考项目的 30dp 偏移一致） */
@Composable
private fun AboutHero(logoAlpha: Float, logoScale: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .offset(y = HERO_CONTENT_OFFSET)
                .graphicsLayer {
                    alpha = logoAlpha
                    scaleX = logoScale
                    scaleY = logoScale
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_hammer),
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .gradientMask(heroBrush())
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = APP_NAME,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Color.White,
                modifier = Modifier.gradientMask(heroBrush())
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
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

/** Hero 上色用的斜向渐变（比背景那层更深，压在同色系浅底上才看得清） */
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

/**
 * 渐变场：一层线性渐变打底 + 四团随时间漂移的径向光斑。
 * 参数与参考项目一致（半径 0.62 倍最长边、透明度 0.58 / 0.88）。
 */
private fun DrawScope.drawAboutGradientField(
    animationTime: Float,
    colors: List<Color>,
    fieldSize: Size
) {
    val strengthened = colors.map(::strengthenGradientColor)
    val translucentPalette = strengthened.any { it.alpha < 0.8f }
    val radius = fieldSize.maxDimension * 0.62f
    val motionTime = animationTime * BACKGROUND_SPEED

    drawRect(
        brush = Brush.linearGradient(
            colors = strengthened.map { color ->
                color.copy(alpha = if (translucentPalette) color.alpha * 0.72f else 0.58f)
            },
            start = Offset.Zero,
            end = Offset(fieldSize.width, fieldSize.height)
        )
    )

    val centers = listOf(
        Offset(
            x = fieldSize.width * (0.18f + 0.10f * sin(motionTime)),
            y = fieldSize.height * (0.20f + 0.08f * cos(motionTime * 0.8f))
        ),
        Offset(
            x = fieldSize.width * (0.82f + 0.10f * cos(motionTime * 0.9f)),
            y = fieldSize.height * (0.78f + 0.10f * sin(motionTime * 0.7f))
        ),
        Offset(
            x = fieldSize.width * (0.22f + 0.12f * cos(motionTime * 0.65f)),
            y = fieldSize.height * (0.80f + 0.08f * sin(motionTime * 0.85f))
        ),
        Offset(
            x = fieldSize.width * (0.80f + 0.12f * sin(motionTime * 0.72f)),
            y = fieldSize.height * (0.20f + 0.08f * cos(motionTime * 0.62f))
        )
    )
    centers.forEachIndexed { index, center ->
        val color = strengthened[index]
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = if (translucentPalette) color.alpha * 0.96f else 0.88f),
                    color.copy(alpha = 0f)
                ),
                center = center,
                radius = radius
            ),
            center = center,
            radius = radius
        )
    }
}

/** 逐帧累加的时间轴，驱动光斑漂移与配色切换 */
@Composable
private fun rememberAboutAnimationTime(): Float {
    var animationTime by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previousFrame = 0L
        while (true) {
            withFrameNanos { frameTime ->
                if (previousFrame != 0L) {
                    animationTime += (frameTime - previousFrame) / 1_000_000_000f
                }
                previousFrame = frameTime
            }
        }
    }
    return animationTime
}

/** 每 12s 在 3 组配色之间做一次平滑过渡（smoothstep） */
private fun animatedGradientColors(animationTime: Float, dark: Boolean): List<Color> {
    val palettes = if (dark) DarkGradientPalettes else LightGradientPalettes
    val segmentValue = animationTime / COLOR_INTERPOLATION_SECONDS
    val segment = floor(segmentValue).toInt() % 4
    val rawProgress = segmentValue - floor(segmentValue)
    val progress = rawProgress * rawProgress * (3f - 2f * rawProgress)
    val start = when (segment) {
        0 -> palettes[1]
        1 -> palettes[0]
        2 -> palettes[1]
        else -> palettes[2]
    }
    val end = when (segment) {
        0 -> palettes[0]
        1 -> palettes[1]
        2 -> palettes[2]
        else -> palettes[1]
    }
    return start.indices.map { index -> lerp(start[index], end[index], progress) }
}

/** 轻微提饱和 + 压亮，让渐变在浅底上不至于糊成一片 */
private fun strengthenGradientColor(color: Color): Color {
    val average = (color.red + color.green + color.blue) / 3f
    val saturation = 1.18f
    val brightnessOffset = 0.015f
    return Color(
        red = (average + (color.red - average) * saturation - brightnessOffset).coerceIn(0f, 1f),
        green = (average + (color.green - average) * saturation - brightnessOffset).coerceIn(0f, 1f),
        blue = (average + (color.blue - average) * saturation - brightnessOffset).coerceIn(0f, 1f),
        alpha = color.alpha
    )
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

private const val HERO_HEIGHT_FRACTION = 0.60f
private const val BACKGROUND_SPEED = 0.12f
private const val COLOR_INTERPOLATION_SECONDS = 12f
private val DEVELOPER_TOP_GAP = 16.dp
private val HERO_CONTENT_OFFSET = 30.dp

/** 与参考项目同一套配色：浅色是低饱和粉/蓝，深色是半透明深蓝紫 */
private val LightGradientPalettes = listOf(
    listOf(Color(1f, 0.90f, 0.94f), Color(1f, 0.84f, 0.89f), Color(0.97f, 0.73f, 0.82f), Color(0.64f, 0.65f, 0.98f)),
    listOf(Color(0.58f, 0.74f, 1f), Color(1f, 0.90f, 0.93f), Color(0.74f, 0.76f, 1f), Color(0.97f, 0.77f, 0.84f)),
    listOf(Color(0.98f, 0.86f, 0.90f), Color(0.60f, 0.73f, 0.98f), Color(0.92f, 0.93f, 1f), Color(0.56f, 0.69f, 1f))
)

private val DarkGradientPalettes = listOf(
    listOf(Color(0.20f, 0.06f, 0.88f, 0.40f), Color(0.30f, 0.14f, 0.55f, 0.50f), Color(0f, 0.64f, 0.96f, 0.50f), Color(0.11f, 0.16f, 0.83f, 0.40f)),
    listOf(Color(0.07f, 0.15f, 0.79f, 0.50f), Color(0.62f, 0.21f, 0.67f, 0.50f), Color(0.06f, 0.25f, 0.84f, 0.50f), Color(0f, 0.20f, 0.78f, 0.50f)),
    listOf(Color(0.58f, 0.30f, 0.74f, 0.40f), Color(0.27f, 0.18f, 0.60f, 0.50f), Color(0.66f, 0.26f, 0.62f, 0.50f), Color(0.12f, 0.16f, 0.70f, 0.60f))
)

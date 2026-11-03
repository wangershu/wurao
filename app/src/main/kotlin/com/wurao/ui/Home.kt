package com.wurao.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wurao.assist.FloatService
import com.wurao.assist.SnapshotService
import com.wurao.core.Cfg
import com.wurao.core.model.Origin
import com.wurao.core.store.RuleStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ScreenCapture
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 状态卡配色，取自 HyperIsland 的 OverviewPage（与旧版一致）
private val ACTIVE_COLOR = Color(0xFF36D167)
private val ACTIVE_BG = Color(0xFFDFFAE4)
private val INACTIVE_COLOR = Color(0xFFFF5A52)
private val INACTIVE_BG = Color(0xFFFFE5E3)
private val WARN_BG = Color(0xFFFFF3D6)
private val WARN_FG = Color(0xFF704D00)

/**
 * 主页：一眼看出「模块现在有没有在工作」。
 *
 * 版式对齐大圣净化（HyperIsland 的 Overview）：
 * 顶部 1 大 + 2 小的状态网格（左大卡带对勾水印），下面接告警卡、信息卡、常用。
 * 命中记录挪到了「今日拦截」的二级页（[HitsScreen]）。
 */
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    // 订阅规则版本：导入/加规则后卡片数字会跟着变
    val rev = Ui.ruleRev
    val allRules = remember(rev) { RuleStore.all() }
    val subs = remember(allRules) { allRules.count { it.origin !is Origin.User } }
    val enabled = allRules.count { it.enabled }
    val todayCount = HitStore.all().size

    val frameOk = CfgPush.bound
    val services = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: ""
    val a11yOn = services.contains(ctx.packageName)
    val ver = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrDefault("?") ?: "?"

    Kit.Page(title = "无扰", bottomExtra = 16.dp) {
        // ---------- 状态网格 ----------
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusCard(
                    active = frameOk,
                    version = ver,
                    modifier = Modifier.weight(1f).aspectRatio(1f)
                )
                Column(
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        title = "已启用规则",
                        value = "$enabled",
                        modifier = Modifier.weight(1f),
                    ) { Navigator.switch(Navigator.Tab.APPS) }
                    StatCard(
                        title = "今日拦截",
                        value = "$todayCount",
                        modifier = Modifier.weight(1f),
                    ) { Navigator.go(Dest.Hits) }
                }
            }
        }

        // ---------- 告警横幅 ----------
        if (!frameOk || !a11yOn) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = WARN_BG, contentColor = WARN_FG),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            if (!frameOk) "LSPosed 未接入" else "无障碍没开",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = WARN_FG,
                        )
                        Text(
                            if (!frameOk) "请确认模块已在 LSPosed 中启用并勾选作用域，然后重新打开应用"
                            else "抓屏和自动化用不了。去开：设置 › 无障碍 › 已下载的服务 › 无扰",
                            fontSize = 14.sp, color = WARN_FG,
                        )
                    }
                }
            }
        }

        // ---------- 信息卡 ----------
        item {
            Kit.Group {
                Kit.KeyValue("系统版本", "Android ${android.os.Build.VERSION.RELEASE}")
                Kit.KeyValue("应用版本", ver)
                Kit.KeyValue("Hook 框架", if (frameOk) "已连接" else "未连接")
                Kit.KeyValue("规则库", "${allRules.size} 条 · 订阅导入 $subs 条")
                Kit.KeyValue("设备型号", android.os.Build.MODEL)
            }
        }

        // ---------- 常用（只留两项，评审拍板） ----------
        item { Kit.Section("常用") }
        item {
            Kit.RowGroup {
                // 悬浮球：一个小胶囊贴在屏幕边缘，随时点一下就开启抓屏
                var floatOn by remember {
                    mutableStateOf(Ui.prefs(ctx).getBoolean("float_ball", false))
                }
                fun setFloat(on: Boolean) {
                    floatOn = on
                    Ui.prefs(ctx).edit().putBoolean("float_ball", on).apply()
                    if (on) {
                        // 悬浮窗权限：没有就去要
                        if (!Settings.canDrawOverlays(ctx)) {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}"),
                                )
                            )
                        } else {
                            ctx.startService(Intent(ctx, FloatService::class.java))
                        }
                    } else {
                        ctx.stopService(Intent(ctx, FloatService::class.java))
                    }
                }
                Kit.SwitchRow(
                    title = "悬浮球",
                    summary = "一个小胶囊贴在屏幕边缘，随时点一下就开启抓屏（不用回应用里）",
                    checked = floatOn,
                    onChecked = { setFloat(it) },
                )
                Kit.ArrowRow(
                    title = "抓规则",
                    summary = if (SnapshotService.active) {
                        "到应用页挑一个 App，抓屏画框点选"
                    } else {
                        "抓屏服务没连上 —— 先去开无障碍（设置 › 无障碍 › 已下载的服务 › 无扰）"
                    },
                    icon = MiuixIcons.ScreenCapture,
                    onClick = {
                        if (SnapshotService.active) {
                            Navigator.switch(Navigator.Tab.APPS)
                        } else {
                            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                )
            }
        }
    }
}

/** 状态大卡：方形，右下角一个对勾/感叹号水印（照抄旧版）。 */
@Composable
private fun StatusCard(active: Boolean, version: String, modifier: Modifier = Modifier) {
    val color = if (active) ACTIVE_COLOR else INACTIVE_COLOR
    val background = if (active) ACTIVE_BG else INACTIVE_BG
    Card(modifier = modifier, colors = CardDefaults.defaultColors(color = background)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
            Box(
                modifier = Modifier.fillMaxSize().offset(12.dp, 16.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                OutlineMark(ok = active, tint = color.copy(alpha = 0.78f), size = 110.dp)
            }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = if (active) "已激活" else "未激活",
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF101010),
                )
                Text(
                    text = "软件版本：$version",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = if (active) Color(0xFF101010) else color,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 小统计卡：标题小字 + 数字大字（照抄旧版）。 */
@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                value,
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 描边对勾/感叹号水印（激活画对勾，未激活画感叹号 —— 未激活不是错误，是「差一步」）。 */
@Composable
private fun OutlineMark(ok: Boolean, tint: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = this.size.minDimension * 0.062f
        val r = this.size.minDimension / 2 - stroke / 2
        drawCircle(color = tint, radius = r, style = Stroke(width = stroke))
        val path = Path().apply {
            if (ok) {
                moveTo(w * 0.295f, h * 0.515f); lineTo(w * 0.445f, h * 0.665f); lineTo(w * 0.715f, h * 0.355f)
            } else {
                moveTo(w * 0.5f, h * 0.30f); lineTo(w * 0.5f, h * 0.56f)
                moveTo(w * 0.5f, h * 0.68f); lineTo(w * 0.5f, h * 0.70f)
            }
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

/** 供二级页复用：检查无障碍是否开着。 */
fun a11yEnabled(ctx: Context): Boolean {
    val services = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: ""
    return services.contains(ctx.packageName)
}

package com.adhammer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhammer.BuildConfig
import com.adhammer.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页。
 *
 * 链接信息集中在 [REPO] / [RELEASES] / [ISSUES] 三个常量里，换仓库只改这三行。
 */
@Composable
fun AboutScreen(ctx: android.content.Context) {
    Kit.DetailPage(title = "关于", subtitle = "v${BuildConfig.VERSION_NAME}", onBack = { Navigator.back() }) {
        // 头部：图标 + 名字 + 版本
        item {
            Kit.Group {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_hammer),
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        Text(
                            text = "大圣净化",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "AdHammer · v${BuildConfig.VERSION_NAME}",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Text(
                    text = "原版「大圣净化」的 Kotlin 复刻版：三大子系统保留，规则与代码解耦 —— 订阅换规则、特征库换识别能力。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item { Kit.Section("开发者") }
        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "GitHub",
                    summary = "wangershu",
                    icon = MiuixIcons.Community,
                    onClick = { openUrl(ctx, "https://github.com/wangershu") }
                )
                Kit.ArrowRow(
                    title = "问题反馈",
                    summary = "遇到规则失效、闪退，去 issues 提",
                    icon = MiuixIcons.Messages,
                    onClick = { openUrl(ctx, ISSUES) }
                )
            }
        }

        item { Kit.Section("项目链接") }
        item {
            Kit.RowGroup {
                Kit.ArrowRow(
                    title = "仓库",
                    summary = REPO,
                    icon = MiuixIcons.Link,
                    onClick = { openUrl(ctx, REPO) }
                )
                Kit.ArrowRow(
                    title = "发布页",
                    summary = "下载最新 APK",
                    icon = MiuixIcons.Download,
                    onClick = { openUrl(ctx, RELEASES) }
                )
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

        item { Kit.Section("第三方库") }
        item {
            Kit.Group {
                LibRow("miuix", "MIUI 风格 Compose 组件库")
                LibRow("libxposed", "LSPosed API 102")
                LibRow("Gson", "规则 JSON 解析")
                LibRow("OkHttp", "订阅与特征库下载")
            }
        }

        item { Kit.Section("说明") }
        item {
            Kit.Group {
                Text(
                    text = "本模块仅供学习交流与技术研究使用，请勿用于商业或违法用途。因使用本模块产生的任何后果由使用者自行承担。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

private const val REPO = "https://github.com/wangershu/adhammer"
private const val RELEASES = "https://github.com/wangershu/adhammer/releases"
private const val ISSUES = "https://github.com/wangershu/adhammer/issues"

/** 用系统浏览器打开链接；没有浏览器就静默失败，不弹崩溃 */
private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun LibRow(name: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(
                text = desc,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp)
        )
    }
}

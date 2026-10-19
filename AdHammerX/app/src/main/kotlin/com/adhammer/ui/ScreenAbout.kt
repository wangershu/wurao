package com.adhammer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页。
 *
 * 开发者与项目链接等信息**先占位**：现在还没定，等定下来只改 [PLACEHOLDER] 一处即可。
 */
@Composable
fun AboutScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        SmallTitle(
            text = "关于",
            modifier = Modifier.padding(horizontal = Kit.H_PAD, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = Kit.pad(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                Kit.ArrowItem(
                    title = PLACEHOLDER,
                    summary = "待补充",
                    icon = MiuixIcons.Community,
                    enabled = false,
                    onClick = {}
                )
            }

            item { Kit.Section("项目链接") }
            item {
                Kit.ArrowItem(
                    title = "仓库",
                    summary = PLACEHOLDER,
                    icon = MiuixIcons.Link,
                    enabled = false,
                    onClick = {}
                )
            }
            item {
                Kit.ArrowItem(
                    title = "发布页",
                    summary = PLACEHOLDER,
                    icon = MiuixIcons.Link,
                    enabled = false,
                    onClick = {}
                )
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
}

private const val PLACEHOLDER = "待补充"

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

package com.adhammer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * MIUI 风格通用组件。
 *
 * 统一规范（照 MIUI 设置页的写法）：
 * - 分组：SmallTitle 作为分组标题，组内用 Card 或 BasicComponent 列表
 * - 列表项：主标题 17sp 常规、副标题 13sp 次要色、右侧放开关或箭头
 * - 触控区：最小 48dp 高
 * - 圆角：统一用 miuix 默认的 CardDefaults.CornerRadius
 */
object Kit {

    val H_PAD = 16.dp
    val LIST_PAD = PaddingValues(horizontal = 0.dp, vertical = 12.dp)

    /**
     * 紧凑标题栏。
     *
     * 用 SmallTopAppBar 而不是 TopAppBar：后者默认带大标题（largeTitle），
     * 在列表型页面里会白占一大块高度。
     */
    @Composable
    fun Bar(
        title: String,
        subtitle: String = "",
        canBack: Boolean = false,
        onBack: (() -> Unit)? = null,
        actions: @Composable RowScope.() -> Unit = {}
    ) {
        SmallTopAppBar(
            title = title,
            subtitle = subtitle,
            navigationIcon = {
                if (canBack) {
                    IconButton(onClick = { onBack?.invoke() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            },
            actions = actions
        )
    }

    /** 分组标题 */
    @Composable
    fun Section(text: String, extra: String = "") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = H_PAD, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallTitle(text = text, modifier = Modifier.weight(1f))
            if (extra.isNotBlank()) {
                Text(text = extra, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }

    /** 标准列表项（点击型） */
    @Composable
    fun Item(
        title: String,
        summary: String = "",
        icon: ImageVector? = null,
        iconTint: Color = MiuixTheme.colorScheme.primary,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        end: @Composable (RowScope.() -> Unit)? = null
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp),
            // 点击交给 BasicComponent（自带 MIUI 的按压反馈），
            // Card 只负责长按，否则两个 clickable 叠在一起会触发两次
            insideMargin = PaddingValues(0.dp),
            onLongPress = onLongClick,
            pressFeedbackType = PressFeedbackType.None
        ) {
            BasicComponent(
                title = title,
                summary = summary.ifBlank { null },
                enabled = enabled,
                startAction = if (icon != null) {
                    {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else null,
                endActions = end,
                onClick = onClick
            )
        }
    }

    /**
     * 带箭头的列表项（点进下一级）。
     *
     * 用 miuix-preference 的 `ArrowPreference` —— 右边那个箭头是 MIUI 设置里的同款，
     * 自己画容易画歪。`endActions` 里还可以塞开关/徽章，会排在箭头前面。
     */
    @Composable
    fun ArrowItem(
        title: String,
        summary: String = "",
        icon: ImageVector? = null,
        iconTint: Color = MiuixTheme.colorScheme.primary,
        enabled: Boolean = true,
        onClick: () -> Unit,
        end: @Composable (RowScope.() -> Unit)? = null
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
            pressFeedbackType = PressFeedbackType.None
        ) {
            ArrowPreference(
                title = title,
                summary = summary.ifBlank { null },
                enabled = enabled,
                onClick = onClick,
                startAction = if (icon != null) {
                    {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else null,
                endActions = { if (end != null) end() }
            )
        }
    }

    /** 开关型列表项 */
    @Composable
    fun SwitchItem(
        title: String,
        summary: String = "",
        icon: ImageVector? = null,
        checked: Boolean,
        enabled: Boolean = true,
        onChecked: (Boolean) -> Unit
    ) {
        Item(
            title = title,
            summary = summary,
            icon = icon,
            enabled = enabled,
            onClick = { if (enabled) onChecked(!checked) },
            end = { Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled) }
        )
    }

    /**
     * 单选列表（MIUI 设置里的「单选项」样式：整行可点，右侧圆点）。
     * 放在同一张 Card 里，视觉上是一组而不是三张散卡。
     */
    @Composable
    fun RadioGroup(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = H_PAD),
            insideMargin = PaddingValues(0.dp),
            pressFeedbackType = PressFeedbackType.None
        ) {
            items.forEachIndexed { i, text ->
                BasicComponent(
                    title = text,
                    onClick = { onSelect(i) },
                    endActions = {
                        RadioButton(selected = i == selectedIndex, onClick = { onSelect(i) })
                    }
                )
            }
        }
    }

    /** 统计格 */
    @Composable
    fun Stats(items: List<Pair<String, String>>) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = H_PAD),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.forEach { (value, label) ->
                Card(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = value,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }

    /** 空状态 */
    @Composable
    fun Empty(text: String, actionText: String? = null, onAction: (() -> Unit)? = null) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = H_PAD)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                if (actionText != null && onAction != null) {
                    TextButton(
                        text = actionText,
                        onClick = onAction,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    /** 说明文字块 */
    @Composable
    fun Note(text: String) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = H_PAD + 4.dp, vertical = 4.dp)
        )
    }

    /** 卡片容器（带内边距的分组内容） */
    @Composable
    fun Group(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(modifier = modifier.fillMaxWidth().padding(horizontal = H_PAD)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                content = content
            )
        }
    }

    /** 键值行 */
    @Composable
    fun KeyValue(key: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = key, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(
                text = value,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }

    /** 小徽章（状态/分类） */
    @Composable
    fun Tag(text: String, color: Color = MiuixTheme.colorScheme.primary) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }

    /** 顶部大状态卡 */
    @Composable
    fun HeroCard(
        ok: Boolean,
        title: String,
        subtitle: String,
        extra: String = ""
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = H_PAD)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态点：绿=已激活，红=未激活
                androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
                    drawCircle(color = if (ok) Color(0xFF34C759) else Color(0xFFFF3B30))
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (extra.isNotBlank()) {
                    Tag(text = extra)
                }
            }
        }
    }

    fun pad(horizontal: Dp = H_PAD) = PaddingValues(horizontal = horizontal, vertical = 12.dp)
}

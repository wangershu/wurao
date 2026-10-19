package com.adhammer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * MIUI 风格通用组件。
 *
 * 结构与命名对齐参考项目 HyperIsland（同为 miuix 0.9.4）：
 * - 根页骨架 [Page]：可折叠大标题 TopAppBar + LazyColumn
 * - 二级页骨架 [DetailPage]：同上，左侧多一个返回键
 * - 列表：[Section] 分组标题 + [RowGroup]（一整张 Card）+ [ArrowRow] / [SwitchRow] / [PlainRow]
 * - 行内边距 18x14、左侧图标 tint = onBackground、右侧箭头 10x16 的 ArrowRight
 */
object Kit {

    val H_PAD = 12.dp

    /**
     * 根页底栏占用的高度。
     *
     * 根 Scaffold 不把 padding 套到 pager 上（否则状态栏内边距会被内层 Scaffold 再算一遍，
     * 整个顶栏下移一个状态栏高度），底栏高度改由这里下发给列表，只在内容末尾留白。
     */
    val LocalBottomBarInset = compositionLocalOf { 0.dp }

    /** 行内边距（与 HyperIsland 的 SettingsItemMargin 一致） */
    private val ITEM_MARGIN = PaddingValues(horizontal = 18.dp, vertical = 14.dp)

    // ---------------------------------------------------------------- 页面骨架

    /**
     * 根页骨架：大标题（滚动时折叠成小标题）+ 列表。
     *
     * 对标 HyperIsland 的 CollapsingPage：TopAppBar 同时给 title 和 largeTitle，
     * 配合 MiuixScrollBehavior 做折叠；列表统一 12dp 间距、底部留白由 [bottomExtra] 控制。
     */
    @Composable
    fun Page(
        title: String,
        subtitle: String = "",
        actionIcon: ImageVector? = null,
        actionDesc: String = "",
        onAction: (() -> Unit)? = null,
        bottomExtra: Dp = 16.dp,
        content: LazyListScope.() -> Unit
    ) {
        val scrollBehavior = MiuixScrollBehavior()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    subtitle = subtitle,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (actionIcon != null && onAction != null) {
                            IconButton(onClick = onAction) {
                                Icon(imageVector = actionIcon, contentDescription = actionDesc)
                            }
                        }
                    }
                )
            }
        ) { padding ->
            PageList(padding, scrollBehavior, bottomExtra, content)
        }
    }

    /**
     * 二级页骨架：与 [Page] 同构，多一个返回键（用 miuix 官方的 Back 图标）。
     */
    @Composable
    fun DetailPage(
        title: String,
        onBack: () -> Unit,
        subtitle: String = "",
        actionIcon: ImageVector? = null,
        actionDesc: String = "",
        onAction: (() -> Unit)? = null,
        actions: @Composable (RowScope.() -> Unit) = {},
        bottomExtra: Dp = 28.dp,
        content: LazyListScope.() -> Unit
    ) {
        val scrollBehavior = MiuixScrollBehavior()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    subtitle = subtitle,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                    actions = {
                        actions()
                        if (actionIcon != null && onAction != null) {
                            IconButton(onClick = onAction) {
                                Icon(imageVector = actionIcon, contentDescription = actionDesc)
                            }
                        }
                    }
                )
            }
        ) { padding ->
            PageList(padding, scrollBehavior, bottomExtra, content)
        }
    }

    /**
     * 独立标题栏：给不适合改成 [DetailPage] 的页面用（正文里有嵌套列表、Tab 之类）。
     * 与 [DetailPage] 用的是同一个 TopAppBar，只是不接滚动折叠。
     */
    @Composable
    fun Bar(
        title: String,
        subtitle: String = "",
        canBack: Boolean = false,
        onBack: (() -> Unit)? = null,
        actions: @Composable (RowScope.() -> Unit) = {}
    ) {
        TopAppBar(
            title = title,
            largeTitle = title,
            subtitle = subtitle,
            navigationIcon = {
                if (canBack) {
                    IconButton(onClick = { onBack?.invoke() }) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                }
            },
            actions = actions
        )
    }

    /**
     * 紧凑标题栏：只有返回键和操作，**不写标题**。
     *
     * 参考项目的详情页（如通知渠道页）就是这种：标题让位给正文顶部的 Header 块。
     */
    @Composable
    fun CompactBar(
        onBack: () -> Unit,
        actions: @Composable (RowScope.() -> Unit) = {}
    ) {
        SmallTopAppBar(
            title = "",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                }
            },
            actions = actions
        )
    }

    /**
     * 详情页顶部的实体头：图标 + 名字 + 副标题 + 右侧开关/操作。
     * 对应参考项目里「QQ / com.tencent.mobileqq [开关]」那一块。
     */
    @Composable
    fun Header(
        title: String,
        subtitle: String = "",
        start: @Composable (() -> Unit)? = null,
        end: @Composable (RowScope.() -> Unit)? = null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (start != null) {
                Box(modifier = Modifier.padding(end = 14.dp)) { start() }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onBackground
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (end != null) Row(verticalAlignment = Alignment.CenterVertically) { end() }
        }
    }

    @Composable
    private fun PageList(
        padding: PaddingValues,
        scrollBehavior: ScrollBehavior,
        bottomExtra: Dp,
        content: LazyListScope.() -> Unit
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = H_PAD,
                top = padding.calculateTopPadding() + 8.dp,
                end = H_PAD,
                bottom = padding.calculateBottomPadding() + LocalBottomBarInset.current + bottomExtra
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }

    // ---------------------------------------------------------------- 分组与行

    /** 分组标题（SmallTitle，与 HyperIsland 的 SectionTitle 同款内边距） */
    @Composable
    fun Section(text: String, extra: String = "") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallTitle(
                text = text,
                modifier = Modifier.weight(1f),
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            )
            if (extra.isNotBlank()) {
                Text(
                    text = extra,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(end = 18.dp)
                )
            }
        }
    }

    /** 一整张卡片里放若干行（HyperIsland 的设置页就是这么分组的） */
    @Composable
    fun RowGroup(content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
            pressFeedbackType = PressFeedbackType.None
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }

    /** 带内边距的卡片（放图表、键值表、说明文字这类自定义内容） */
    @Composable
    fun Group(
        modifier: Modifier = Modifier,
        innerPadding: Dp = 16.dp,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(modifier = modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(innerPadding),
                content = content
            )
        }
    }

    /** 行左侧图标：右边留 16dp，颜色跟正文走（HyperIsland 的 SettingsIcon） */
    @Composable
    private fun LeadingIcon(imageVector: ImageVector) {
        Icon(
            modifier = Modifier.padding(end = 16.dp),
            imageVector = imageVector,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackground
        )
    }

    /** 行右侧箭头：miuix 官方 ArrowRight，10x16 */
    @Composable
    private fun TrailingArrow(enabled: Boolean) {
        Icon(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = null,
            modifier = Modifier.size(width = 10.dp, height = 16.dp),
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurfaceVariantActions
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            }
        )
    }

    /** 行首的任意内容（App 图标等），右边留 16dp，与 [LeadingIcon] 对齐 */
    @Composable
    fun RowStart(content: @Composable () -> Unit) {
        Row(modifier = Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }

    /** 普通行（可点，右侧可挂自定义内容） */
    @Composable
    fun PlainRow(
        title: String,
        summary: String = "",
        icon: ImageVector? = null,
        start: @Composable (() -> Unit)? = null,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
        end: @Composable (RowScope.() -> Unit)? = null
    ) {
        val leading: (@Composable () -> Unit)? = when {
            start != null -> start
            icon != null -> { { LeadingIcon(icon) } }
            else -> null
        }
        BasicComponent(
            title = title,
            summary = summary.ifBlank { null },
            enabled = enabled,
            startAction = leading,
            endActions = { if (end != null) end() },
            insideMargin = ITEM_MARGIN,
            onClick = onClick
        )
    }

    /** 带箭头的行（点进下一级） */
    @Composable
    fun ArrowRow(
        title: String,
        summary: String = "",
        icon: ImageVector? = null,
        start: @Composable (() -> Unit)? = null,
        enabled: Boolean = true,
        onClick: () -> Unit,
        end: @Composable (RowScope.() -> Unit)? = null
    ) {
        PlainRow(
            title = title,
            summary = summary,
            icon = icon,
            start = start,
            enabled = enabled,
            onClick = onClick,
            end = {
                if (end != null) end()
                TrailingArrow(enabled)
            }
        )
    }

    /** 带开关的行 */
    @Composable
    fun SwitchRow(
        title: String,
        summary: String = "",
        icon: ImageVector? = null,
        checked: Boolean,
        enabled: Boolean = true,
        onChecked: (Boolean) -> Unit
    ) {
        PlainRow(
            title = title,
            summary = summary,
            icon = icon,
            enabled = enabled,
            onClick = { if (enabled) onChecked(!checked) },
            end = { Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled) }
        )
    }

    /** 单选项行（放进 [RowGroup] 里构成一组） */
    @Composable
    fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
        PlainRow(
            title = text,
            onClick = onClick,
            end = { RadioButton(selected = selected, onClick = onClick) }
        )
    }

    /** 键值行（放 [Group] 里） */
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

    // ---------------------------------------------------------------- 独立卡片项
    // 下面三个是「一行一张卡」的写法，给不方便改成分组的页面用；
    // 内部复用上面的 Row，所以样式（边距、箭头、图标）始终跟分组版一致。

    @Composable
    fun Item(
        title: String,
        summary: String = "",
        icon: ImageVector? = null,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        end: @Composable (RowScope.() -> Unit)? = null
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
            pressFeedbackType = PressFeedbackType.None,
            onLongPress = onLongClick
        ) {
            PlainRow(
                title = title,
                summary = summary,
                icon = icon,
                enabled = enabled,
                onClick = onClick,
                end = end
            )
        }
    }

    @Composable
    fun ArrowItem(
        title: String,
        summary: String = "",
        icon: ImageVector? = null,
        start: @Composable (() -> Unit)? = null,
        enabled: Boolean = true,
        onClick: () -> Unit,
        end: @Composable (RowScope.() -> Unit)? = null
    ) {
        RowGroup {
            ArrowRow(
                title = title,
                summary = summary,
                icon = icon,
                start = start,
                enabled = enabled,
                onClick = onClick,
                end = end
            )
        }
    }

    @Composable
    fun SwitchItem(
        title: String,
        summary: String = "",
        icon: ImageVector? = null,
        checked: Boolean,
        enabled: Boolean = true,
        onChecked: (Boolean) -> Unit
    ) {
        RowGroup { SwitchRow(title, summary, icon, checked, enabled, onChecked) }
    }

    // ---------------------------------------------------------------- 其它

    /** 统计格 */
    @Composable
    fun Stats(items: List<Pair<String, String>>) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
        )
    }

    /** 小徽章（状态 / 分类） */
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
    fun HeroCard(ok: Boolean, title: String, subtitle: String, extra: String = "") {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                if (extra.isNotBlank()) Tag(text = extra)
            }
        }
    }

    /** 单选项组（整组一张卡，右侧圆点） */
    @Composable
    fun RadioGroup(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        RowGroup {
            items.forEachIndexed { i, text ->
                RadioRow(text = text, selected = i == selectedIndex, onClick = { onSelect(i) })
            }
        }
    }

    /** 老页面用的内容边距，逐步迁移到 [Page] 之后可以删 */
    fun pad(horizontal: Dp = H_PAD) = PaddingValues(horizontal = horizontal, vertical = 12.dp)
}

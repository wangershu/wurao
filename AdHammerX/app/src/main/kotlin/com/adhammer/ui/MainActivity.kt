package com.adhammer.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.adhammer.float.FloatController
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var pickApk: androidx.activity.result.ActivityResultLauncher<Array<String>>
    private lateinit var exportJson: androidx.activity.result.ActivityResultLauncher<String>
    private lateinit var importJson: androidx.activity.result.ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiState.init(this)
        UiPrefs.init(this)
        intent?.getStringExtra("snapshot")?.let { UiState.setSnapshot(it) }
        // 调试用：adb shell am start ... --es tab STATS 可直接落到某个页签
        applyTabExtra(intent)

        pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val dst = File(cacheDir, "picked.apk")
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                }
                UiState.updatePendingApk(dst.absolutePath)
            }.onFailure { UiState.updatePendingApk(null) }
        }

        exportJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(UiState.exportJson().toByteArray()) }
            }
        }

        importJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()?.let { UiState.importJson(it) }
        }

        setContent {
            AppTheme {
                Root(
                    ctx = this,
                    onPickApk = { pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                    onExport = { exportJson.launch("adhammer_rules.json") },
                    onImport = { importJson.launch(arrayOf("application/json", "text/plain")) }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("snapshot")?.let { UiState.setSnapshot(it) }
        applyTabExtra(intent)
    }

    private fun applyTabExtra(intent: android.content.Intent?) {
        intent?.getStringExtra("tab")?.let { name ->
            runCatching { Navigator.switch(Navigator.Tab.valueOf(name.uppercase())) }
        }
        // 调试用：--es dest appdetail:com.xxx / about / purify / homeact:com.xxx
        val dest = intent?.getStringExtra("dest") ?: return
        runCatching {
            when {
                dest == "about" -> {
                    Navigator.switch(Navigator.Tab.SETTINGS)
                    Navigator.go(Dest.About)
                }
                dest == "purify" -> {
                    Navigator.switch(Navigator.Tab.SETTINGS)
                    Navigator.go(Dest.SettingsPurify)
                }
                dest.startsWith("appdetail:") -> {
                    val pkg = dest.removePrefix("appdetail:")
                    Navigator.switch(Navigator.Tab.APPS)
                    Navigator.go(Dest.AppDetail(pkg))
                }
                dest.startsWith("homeact:") -> {
                    val pkg = dest.removePrefix("homeact:")
                    Navigator.switch(Navigator.Tab.APPS)
                    Navigator.go(Dest.AppDetail(pkg))
                    Navigator.go(Dest.HomeAct(pkg))
                }
                // 调试用：rulelist:<pkg>:<type> / tasklist:<pkg> / apphosts:<pkg>
                dest.startsWith("rulelist:") -> {
                    val rest = dest.removePrefix("rulelist:")
                    val pkg = rest.substringBeforeLast(':')
                    val type = rest.substringAfterLast(':').toInt()
                    Navigator.switch(Navigator.Tab.APPS)
                    Navigator.go(Dest.AppDetail(pkg))
                    Navigator.go(Dest.RuleList(pkg, type))
                }
                dest.startsWith("tasklist:") -> {
                    val pkg = dest.removePrefix("tasklist:")
                    Navigator.switch(Navigator.Tab.APPS)
                    Navigator.go(Dest.AppDetail(pkg))
                    Navigator.go(Dest.TaskList(pkg))
                }
                dest.startsWith("apphosts:") -> {
                    val pkg = dest.removePrefix("apphosts:")
                    Navigator.switch(Navigator.Tab.APPS)
                    Navigator.go(Dest.AppDetail(pkg))
                    Navigator.go(Dest.AppHosts(pkg))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FloatController.syncFrom(this)
    }
}

/**
 * 根容器，结构对齐 HyperIsland 的 AppShell：
 * - 四个根页放在 HorizontalPager 里（可左右滑）
 * - 底栏只在根页出现，进二级页时整层滑入覆盖（底栏收起）
 */
@Composable
private fun Root(ctx: Context, onPickApk: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tabs = Navigator.Tab.entries
    val pagerState = rememberPagerState(
        initialPage = Navigator.tab.ordinal,
        pageCount = { tabs.size }
    )

    // 点底栏 → 滑到对应页
    LaunchedEffect(Navigator.tab) {
        val target = Navigator.tab.ordinal
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    // 滑页 → 同步底栏选中。
    // drop(1) 很关键：pager 首帧的 settledPage 可能还是 0，
    // 不加就会在启动时把导航栈清成 HOME，使 --es dest 落到的二级页被弹回根页。
    LaunchedEffect(Unit) {
        snapshotFlow { pagerState.settledPage }
            .drop(1)
            .collect { page ->
                val t = tabs[page]
                // 二级页盖在 pager 上时不该改栈
                if (Navigator.tab != t && Navigator.atRoot) Navigator.switch(t)
            }
    }

    BackHandler(enabled = Navigator.canBack) { Navigator.back() }

    val detail = if (Navigator.atRoot) null else Navigator.current

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        bottomBar = { if (Navigator.atRoot) BottomBar() }
    ) { padding ->
        // 根 padding 只取「底栏自身的高度」下发给列表；系统栏内边距交给各页自己的 Scaffold，
        // 否则状态栏会被算两遍、整个顶栏下移一个状态栏高度（参考项目就是不套这层 padding 的）。
        val navBarInset = with(LocalDensity.current) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }
        val barInset = (padding.calculateBottomPadding() - navBarInset).coerceAtLeast(0.dp)

        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(Kit.LocalBottomBarInset provides barInset) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { page ->
                    when (tabs[page]) {
                        Navigator.Tab.HOME -> HomeScreen(ctx)
                        Navigator.Tab.APPS -> AppsScreen(ctx)
                        Navigator.Tab.STATS -> StatsScreen()
                        Navigator.Tab.SETTINGS -> SettingsScreen(ctx, onExport, onImport)
                    }
                }
            }

            AnimatedContent(
                targetState = detail,
                label = "detail-layer",
                transitionSpec = {
                    if (targetState != null) {
                        (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut()
                    } else {
                        fadeIn() togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                    }
                }
            ) { dest ->
                if (dest != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // miuix 的 background 是纯白（卡片色），页面底色是 surface，
                            // 二级页要和根页同色，否则会「白一块」
                            .background(MiuixTheme.colorScheme.surface)
                    ) {
                        DetailLayer(ctx, dest, onPickApk, onExport, onImport)
                    }
                }
            }
        }
    }
}

/** 二级页分发 */
@Composable
private fun DetailLayer(
    ctx: Context,
    dest: Dest,
    onPickApk: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    when (dest) {
        is Dest.AppDetail -> AppDetailScreen(ctx, dest.pkg)
        is Dest.RuleList -> RuleListScreen(ctx, dest.pkg, dest.ruleType)
        is Dest.TaskList -> TaskListScreen(ctx, dest.pkg)
        is Dest.AppHosts -> AppHostsScreen(ctx, dest.pkg)
        is Dest.RuleEdit -> RuleEditScreen(ctx, dest.pkg, dest.ruleType, dest.index)
        is Dest.HomeAct -> HomeActScreen(ctx, dest.pkg)
        Dest.Hosts -> HostsScreen(ctx)
        Dest.Analyzer -> AnalyzerScreen(ctx, onPickApk)
        Dest.Factory -> FactoryScreen(ctx)
        Dest.Logs -> LogsScreen(ctx)
        Dest.SettingsPurify -> PurifySettingsScreen()
        Dest.SettingsAppearance -> AppearanceSettingsScreen(ctx)
        Dest.SettingsSubscription -> SubscriptionSettingsScreen(ctx)
        Dest.SettingsBackup -> BackupSettingsScreen(ctx, onExport, onImport)
        Dest.SettingsAdvanced -> AdvancedSettingsScreen(ctx)
        Dest.SettingsHelp -> HelpSettingsScreen()
        Dest.About -> AboutScreen(ctx)
        else -> Unit
    }
}

private data class TabItem(val label: String, val icon: ImageVector)

/** 底栏：常规 / 悬浮两种，图标用 miuix 官方那套 */
@Composable
private fun BottomBar() {
    val items = listOf(
        TabItem("主页", MiuixIcons.Home),
        TabItem("应用", MiuixIcons.GridView),
        TabItem("统计", AppIcons.Stats),
        TabItem("设置", MiuixIcons.Settings)
    )
    val selected = Navigator.tab.ordinal
    val onSelect: (Int) -> Unit = { Navigator.switch(Navigator.Tab.entries[it]) }
    // 选中态：主色图标；未选中走 60% 灰。
    // miuix 默认两项都是 onSurfaceContainer，只靠 alpha 区分，在浅色悬浮栏上几乎看不出高亮。
    val colors = NavigationBarDefaults.navigationBarItemColors(
        unselectedContentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        selectedContentColor = MiuixTheme.colorScheme.primary
    )

    if (UiPrefs.floatingBar) {
        // 不给 fillMaxWidth：参考项目的悬浮底栏是「刚好包住 4 个图标」的窄胶囊，不是通栏
        FloatingNavigationBar {
            items.forEachIndexed { index, item ->
                val isSelected = selected == index
                // 悬浮栏的 item 本身不画选中背景，这里自己补一个胶囊底
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) {
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                Color.Transparent
                            }
                        )
                ) {
                    FloatingNavigationBarItem(
                        selected = isSelected,
                        onClick = { onSelect(index) },
                        icon = item.icon,
                        label = item.label,
                        colors = colors
                    )
                }
            }
        }
    } else {
        NavigationBar {
            items.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    icon = item.icon,
                    label = item.label,
                    colors = colors
                )
            }
        }
    }
}

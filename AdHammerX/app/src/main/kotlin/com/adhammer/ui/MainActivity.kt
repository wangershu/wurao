package com.adhammer.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.adhammer.float.FloatController
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
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
    }

    override fun onResume() {
        super.onResume()
        FloatController.syncFrom(this)
    }
}

@Composable
private fun Root(ctx: Context, onPickApk: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    BackHandler(enabled = Navigator.canBack) { Navigator.back() }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        bottomBar = { BottomBar() }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val d = Navigator.current) {
                Dest.Home -> HomeScreen(ctx)
                Dest.Apps -> AppsScreen(ctx)
                is Dest.AppDetail -> AppDetailScreen(ctx, d.pkg)
                is Dest.RuleEdit -> RuleEditScreen(ctx, d.pkg, d.ruleType, d.index)
                is Dest.HomeAct -> HomeActScreen(ctx, d.pkg)
                Dest.Hosts -> HostsScreen(ctx)
                Dest.Analyzer -> AnalyzerScreen(ctx, onPickApk)
                Dest.Factory -> FactoryScreen(ctx)
                Dest.Stats -> StatsScreen()
                Dest.Logs -> LogsScreen(ctx)
                Dest.Settings -> SettingsScreen(ctx, onExport, onImport)
                Dest.SettingsPurify -> PurifySettingsScreen()
                Dest.SettingsAppearance -> AppearanceSettingsScreen(ctx)
                Dest.SettingsSubscription -> SubscriptionSettingsScreen(ctx)
                Dest.SettingsBackup -> BackupSettingsScreen(ctx, onExport, onImport)
                Dest.SettingsAdvanced -> AdvancedSettingsScreen(ctx)
                Dest.SettingsHelp -> HelpSettingsScreen()
                Dest.About -> AboutScreen()
            }
        }
    }
}

private data class TabItem(val tab: Navigator.Tab, val label: String, val icon: ImageVector)

@Composable
private fun BottomBar() {
    val items = listOf(
        TabItem(Navigator.Tab.HOME, "主页", MiuixIcons.Home),
        TabItem(Navigator.Tab.APPS, "应用", MiuixIcons.GridView),
        TabItem(Navigator.Tab.STATS, "统计", AppIcons.Stats),
        TabItem(Navigator.Tab.SETTINGS, "设置", MiuixIcons.Settings)
    )

    if (UiPrefs.floatingBar) {
        FloatingNavigationBar(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    FloatingNavigationBarItem(
                        selected = Navigator.tab == item.tab,
                        onClick = { Navigator.switch(item.tab) },
                        icon = item.icon,
                        label = item.label,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    } else {
        NavigationBar {
            items.forEach { item ->
                NavigationBarItem(
                    selected = Navigator.tab == item.tab,
                    onClick = { Navigator.switch(item.tab) },
                    icon = item.icon,
                    label = item.label
                )
            }
        }
    }
}

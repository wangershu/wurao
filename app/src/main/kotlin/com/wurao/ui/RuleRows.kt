package com.wurao.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 规则属于哪个应用；Global 用空串。 */
fun ruleScopePkg(r: Rule): String = when (val s = r.scope) {
    is Scope.App -> s.pkg
    is Scope.Component -> s.pkg
    Scope.Global -> ""
}

/** 包名转应用名，拿不到就退回包名。 */
fun appLabelOf(ctx: Context, pkg: String): String =
    runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

/**
 * 一条规则：左边信息、右边启用开关；长按弹出「改备注 / 删除」。
 * 开关本身就是状态，不用猜点了「停用」到底生效没有。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RuleRow(
    r: Rule,
    onEditNote: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    val ctx = LocalContext.current
    val pkg = ruleScopePkg(r)
    var showMenu by remember { mutableStateOf(false) }
    val titleColor = if (r.enabled) MiuixTheme.colorScheme.onSurface
    else MiuixTheme.colorScheme.onSurfaceVariantSummary

    Box {
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true },
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        r.note.ifBlank { r.target },
                        fontSize = 14.sp,
                        color = titleColor,
                    )
                    val where = if (pkg.isBlank()) "所有应用" else appLabelOf(ctx, pkg)
                    Text(
                        "$where · ${actCn(r.act)} · ${domainCn(r.domain.key)}" +
                            if (r.enabled) " · 已启用" else " · 已停用",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Text(
                        r.target,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Switch(
                    checked = r.enabled,
                    onCheckedChange = { onToggle() },
                )
            }
        }

        // 锚定在行右上角的菜单，不再是全屏对话框
        if (showMenu) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(-24, 96),
                onDismissRequest = { showMenu = false },
                properties = PopupProperties(focusable = true),
            ) {
                Card(modifier = Modifier.width(168.dp)) {
                    Column {
                        MenuItem("改备注") {
                            showMenu = false
                            onEditNote()
                        }
                        MenuItem("删除这条规则", danger = true) {
                            showMenu = false
                            onDelete()
                        }
                    }
                }
            }
        }
    }

}


@Composable
private fun MenuItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 14.sp,
        color = if (danger) MiuixTheme.colorScheme.error
        else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
    )
}

/** 改备注弹框（长按菜单里的「改备注」用）。 */
@Composable
fun NoteEditDialog(
    rule: Rule?,
    draft: String,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Rule, String) -> Unit,
) {
    if (rule == null) return
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "改备注",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    rule.target,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextField(
                    value = draft,
                    onValueChange = onDraft,
                    label = "这条规则干嘛的",
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "取消", onClick = onDismiss)
                    TextButton(text = "保存", onClick = { onSave(rule, draft.trim()) })
                }
            }
        }
    }
}

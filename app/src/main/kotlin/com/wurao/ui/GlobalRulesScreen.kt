package com.wurao.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wurao.core.model.Rule
import com.wurao.core.model.Scope
import com.wurao.core.store.RuleStore

/**
 * 全局规则：和「应用」列表里每个 App 平级的一个入口。
 *
 * 全局规则对所有应用生效，所以不放进任何 App 的详情页 —— 否则在 A 里关掉，
 * 看起来像只影响 A，其实 B 也停了。这里单一入口管理，命中记录里会指过来。
 */
@Composable
fun GlobalRulesScreen() {
    val ctx = LocalContext.current
    val rev = Ui.ruleRev
    val rules = remember(rev) { RuleStore.all().filter { it.scope is Scope.Global } }
    var editNote by remember { mutableStateOf<Rule?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf<Rule?>(null) }

    Kit.DetailPage(title = "全局规则", onBack = { Navigator.back() }) {
        item {
            Kit.Note(
                "这些规则对所有应用生效。某个应用命中全局规则时，请来这里开关；" +
                    "停用或删除会影响其他应用。",
            )
        }
        item { Kit.Section("全局规则", "${rules.size} 条") }
        if (rules.isEmpty()) {
            item { Kit.Empty("还没有全局规则") }
        } else {
            items(rules, key = { "global:" + it.id }) { r ->
                RuleRow(
                    r = r,
                    onEditNote = { editNote = r; noteDraft = r.note },
                    onDelete = { confirmDel = r },
                    onToggle = {
                        RuleStore.setEnabled(r.id, !r.enabled)
                        Ui.pushRulesAsync(ctx)
                        Ui.ruleRev++
                    },
                )
            }
        }
    }

    ConfirmDialog(
        show = confirmDel != null,
        title = "删掉这条全局规则？",
        text = confirmDel?.target ?: "",
        confirmText = "删掉",
        onDismiss = { confirmDel = null },
        onConfirm = {
            confirmDel?.let { RuleStore.remove(it.id) }
            confirmDel = null
            Ui.pushRulesAsync(ctx)
            Ui.ruleRev++
        },
    )
    NoteEditDialog(
        rule = editNote,
        draft = noteDraft,
        onDraft = { noteDraft = it },
        onDismiss = { editNote = null },
        onSave = { r, text ->
            RuleStore.setNote(r.id, text)
            editNote = null
            Ui.pushRulesAsync(ctx)
            Ui.ruleRev++
        },
    )
}

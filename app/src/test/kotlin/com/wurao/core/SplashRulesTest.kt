package com.wurao.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SplashRulesTest {

    @Test
    fun 内置开屏规则集完整且格式合法() {
        val lines = File("src/main/assets/gkd_splash.tsv").readLines().filter { it.isNotBlank() }
        assertTrue("规则数：${lines.size}", lines.size > 50)
        val acts = setOf("click", "long_click", "back")
        assertTrue("每行 4 列且动作合法", lines.all {
            val c = it.split('\t')
            c.size == 4 && c[1] in acts && c[2].startsWith("[") && c[0].isNotBlank()
        })
        assertTrue("有全局规则", lines.any { it.startsWith("*\t") })
        assertTrue("有 App 专属规则", lines.any { !it.startsWith("*\t") })
    }
}

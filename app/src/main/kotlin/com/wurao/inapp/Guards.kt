package com.wurao.inapp

import android.app.Activity
import android.content.Intent
import com.wurao.core.HitLog
import com.wurao.core.RiskGate
import com.wurao.core.Seed
import com.wurao.core.model.Act
import com.wurao.core.model.Domain
import io.github.libxposed.api.XposedInterface
import java.net.InetAddress
// 扩展函数即使同包也要显式 import
import com.wurao.inapp.HookKit.hookSafe

/**
 * L0「渲染前」三件套：广告还没出来就掐掉。
 *
 * **全部默认关闭**，必须 [RiskGate] 按 App 打开 —— 拦掉 SDK 初始化有可能让 App 自己起不来，
 * 这事得你（用户）决定，不能模块替你决定。
 *
 * 三条共同规矩：
 * 1. 装不上、找不到类/方法都只是跳过并记一笔（"这个 App 没接这个 SDK"是正常情况）；
 * 2. 回调里抛异常不会传给宿主（[HookKit.hookSafe] 用了 PROTECTIVE）；
 * 3. 命中才动手，不命中时开销只是"查一次名单"。
 */
object Guards {

    fun install(
        base: XposedInterface,
        pkg: String,
        cl: ClassLoader,
        extraComponents: List<String> = emptyList(),
        extraHosts: List<String> = emptyList(),
    ) {
        if (RiskGate.isEnabled(pkg, RiskGate.Kind.COMPONENT)) installComponent(base, pkg, extraComponents)
        if (RiskGate.isEnabled(pkg, RiskGate.Kind.NET)) installNet(base, pkg, extraHosts)
        if (RiskGate.isEnabled(pkg, RiskGate.Kind.SDK)) installSdk(base, pkg, cl)
        if (RiskGate.isEnabled(pkg, RiskGate.Kind.SENSOR)) installSensor(base, pkg)
    }

    // ---------------- ① 拦广告页面 ----------------

    /**
     * 广告页还没打开就拦掉：在 `startActivity` 阶段把 Intent 掏空。
     *
     * 这是原版 at=9「防火墙拦截」的做法 —— 比"打开后再 finish"好得多：不闪、不崩。
     */
    private fun installComponent(base: XposedInterface, pkg: String, extra: List<String>) {
        val targets = ((Seed.adComponents[pkg] ?: emptyList()) + extra).distinct()
        if (targets.isEmpty()) return
        if (targets.isEmpty()) return

        val methods = HookKit.findAll(Activity::class.java, "startActivity")
        if (methods.isEmpty()) {
            Log.w("组件拦截：找不到 startActivity")
            return
        }
        methods.forEachIndexed { i, m ->
            base.hookSafe(m, "wurao_block_page_$i") { chain ->
                var blocked = false
                runCatching {
                    for (arg in chain.getArgs()) {
                        if (arg !is Intent) continue
                        val cn = arg.component ?: continue
                        if (cn.className in targets) {
                            // 掏空 Intent：调用方 startActivity 之后不会真正跳转
                            arg.component = null
                            arg.action = null
                            runCatching { arg.extras?.clear() }
                            blocked = true
                            Log.i("拦广告页: ${cn.className}")
                            HitLog.recordRaw(
                                pkg = pkg,
                                key = "component:${cn.className}",
                                note = "拦掉广告页面",
                                act = Act.BLOCK,
                                domain = Domain.COMPONENT,
                                target = cn.className,
                            )
                            Report.hitRaw(pkg, "component:${cn.className}", "拦掉广告页面", Act.BLOCK, Domain.COMPONENT, cn.className)
                            break
                        }
                    }
                }
                if (blocked) null else chain.proceed()
            }
        }
        Log.i("组件拦截已装配（${targets.size} 个广告组件）")
    }

    // ---------------- ② 不让广告联网 ----------------

    /**
     * DNS 层：广告域名解析到本地，请求出门前就失败。
     *
     * 只 hook `getByName` 这一个入口 —— 这是最便宜的一层（一次解析才一次查询）。
     * 刻意**不** hook Socket 读写层：那会让每次网络读写都变成一次规则匹配（架构里明确不做）。
     */
    private fun installNet(base: XposedInterface, pkg: String, extra: List<String>) {
        // 用 DomainSet（反转+扁平+二分）：这张表跑在每个被 hook 的进程里，
        // 直接 HashSet 接 AdClose 那种两万条的表要 1.9MB，这个只要 0.3MB
        val hosts = com.wurao.core.net.DomainSet.of(Seed.adHosts + extra)
        if (hosts.size == 0) return
        val m = HookKit.findMethod(InetAddress::class.java, "getByName", String::class.java) ?: run {
            Log.w("DNS 拦截：找不到 getByName")
            return
        }
        base.hookSafe(m, "wurao_block_net") { chain ->
            val host = chain.getArgs().firstOrNull() as? String
            if (hosts.matches(host)) {
                Log.i("拦域名: $host")
                HitLog.recordRaw(
                    pkg = pkg,
                    key = "host:$host",
                    note = "不让广告联网",
                    act = Act.BLOCK,
                    domain = Domain.HOST,
                    target = host ?: "",
                )
                Report.hitRaw(pkg, "host:$host", "不让广告联网", Act.BLOCK, Domain.HOST, host ?: "")
                // 不能再调 getByName（会递归回自己），直接按字节构造
                return@hookSafe runCatching {
                    InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                }.getOrNull() ?: chain.proceed()
            }
            chain.proceed()
        }
        // 把占用打出来：这张表常驻在每个被 hook 的进程里，得能看见它有多大
        Log.i("DNS 拦截已装配（${hosts.size} 个域名，占 ${hosts.bytes / 1024} KB）: $pkg")
    }


    // ---------------- ③ 让广告 SDK 起不来 ----------------

    /**
     * 让 SDK 的初始化不发生：后面所有广告 API 都拿不到实例。
     *
     * **这是最抗改版的一招** —— 界面天天变，SDK 的类名几年不变（AdClose 就是靠这个做到零规则）。
     * 代价也正是它属于风险级的原因：SDK 起不来，依赖它的业务逻辑可能跟着瘫。
     */
    private fun installSdk(base: XposedInterface, pkg: String, cl: ClassLoader) {
        var installed = 0
        for ((clsName, methodName) in Seed.adSdkInits) {
            val cls = runCatching { cl.loadClass(clsName) }.getOrNull()
            // 这个 App 没接这个 SDK 是正常情况，不是错误
            if (cls == null) {
                Log.d("SDK 拦截: 找不到类 $clsName")
                continue
            }
            val methods = HookKit.findAll(cls, methodName)
            if (methods.isEmpty()) {
                Log.d("SDK 拦截: $clsName 里没有 $methodName（有 ${cls.declaredMethods.size} 个方法）")
                continue
            }
            Log.d("SDK 拦截: 装配 $clsName#$methodName（${methods.size} 个重载）")
            methods.forEachIndexed { i, m ->
                base.hookSafe(m, "wurao_block_sdk_${clsName.hashCode()}_$i") { chain ->
                    Log.i("拦 SDK 初始化: $clsName#$methodName")
                    HitLog.recordRaw(
                        pkg = pkg,
                        key = "sdk:$clsName#$methodName",
                        note = "去掉广告 SDK",
                        act = Act.BLOCK,
                        domain = Domain.SDK,
                        target = "$clsName#$methodName",
                    )
                    Report.hitRaw(pkg, "sdk:$clsName#$methodName", "去掉广告 SDK", Act.BLOCK, Domain.SDK, "$clsName#$methodName")
                    // 不 proceed = init 从未发生
                    when (m.returnType) {
                        java.lang.Boolean.TYPE -> false
                        else -> null
                    }
                }
            }
            installed++
        }
        if (installed > 0) Log.i("SDK 拦截已装配（$installed 个 SDK）")
    }

    /** 摇一摇用的传感器：加速度 + 陀螺仪。 */
    private val MOTION_SENSORS = setOf(
        android.hardware.Sensor.TYPE_ACCELEROMETER,
        android.hardware.Sensor.TYPE_GYROSCOPE,
    )

    /**
     * 去摇一摇：不让 App 注册加速度/陀螺仪监听，摇一摇广告自然跳不了。
     *
     * **默认关**：加速度传感器不只服务于广告 —— 计步、摇一摇换歌、游戏的重力控制都靠它，
     * 一刀切会连累正常功能，所以归在风险级，你自己决定哪个 App 值得开。
     */
    private fun installSensor(base: XposedInterface, pkg: String) {
        val methods = HookKit.findAll(android.hardware.SensorManager::class.java, "registerListener")
        if (methods.isEmpty()) {
            Log.w("摇一摇：找不到 registerListener")
            return
        }
        methods.forEachIndexed { i, m ->
            base.hookSafe(m, "wurao_block_sensor_$i") { chain ->
                val isMotion = chain.getArgs().any {
                    it is android.hardware.Sensor && it.type in MOTION_SENSORS
                }
                if (isMotion) {
                    Log.i("拦摇一摇注册: $pkg")
                    HitLog.recordRaw(
                        pkg = pkg, key = "sensor:motion", note = "去掉摇一摇",
                        act = Act.BLOCK, domain = Domain.SDK, target = "加速度/陀螺仪",
                    )
                    Report.hitRaw(pkg, "sensor:motion", "去掉摇一摇", Act.BLOCK, Domain.SDK, "加速度/陀螺仪")
                    // 不注册 = App 收不到摇一摇事件
                    false
                } else {
                    chain.proceed()
                }
            }
        }
        Log.i("摇一摇已装配: $pkg")
    }
}

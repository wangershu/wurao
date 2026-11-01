package com.wurao.core

/**
 * 种子数据：**今天用真机 APK 实测出来的**广告组件与 SDK（见 `docs/plan.md` 第一节）。
 *
 * 与旧规则库的区别：旧库是"格式"（要解析、要迁移、要背字段语义），
 * 这里是"结论"（只要名单本身），不引入任何旧格式解析代码。
 *
 * 全部属于**风险级**，默认不生效，必须 [RiskGate] 按 App 打开。
 */
object Seed {

    /**
     * 已知广告页面/组件。
     * 来源：adb 拉真机 APK 解 Manifest 实测（美团 12.44.203 / 懂车帝 com.ss.android.auto）。
     */
    val adComponents: Map<String, List<String>> = mapOf(
        // 美团：优量汇 GDT 的广告展示页 + 贝斯广告 + 自家广告页
        "com.sankuai.meituan" to listOf(
            "com.qq.e.ads.ADActivity",
            "com.qq.e.ads.PortraitADActivity",
            "com.qq.e.ads.LandscapeADActivity",
            "com.qq.e.ads.RewardvideoPortraitADActivity",
            "com.qq.e.ads.RewardvideoLandscapeADActivity",
            "com.qq.e.ads.DialogActivity",
            "com.beizi.ad.AdActivity",
            "com.meituan.android.mgb.ad.page.MGBAdMainActivity",
            "com.meituan.android.msv.page.outsidead.splashad.MSVSplashAdActivity",
        ),
        // 懂车帝：开屏 / 全屏 / 落地页都是独立 Activity（组件名是改版时最稳的特征）
        "com.ss.android.auto" to listOf(
            "com.ss.android.article.base.feature.splash.SplashAdActivity",
            "com.ss.android.ad.adwebview.AdBrowserActivity",
            "com.ss.android.ad.adwebview.AdVideoBrowserDrawerActivity",
            "com.ss.android.ad.landingpage.activity.AdBannerLandingPageActivity",
            "com.ss.android.article.base.feature.app.schema.AdsAppActivity",
            "com.ss.android.article.base.feature.app.schema.SingleTaskAdsAppActivity",
            "com.ss.android.adimp.AdFormActivity",
            "com.ss.android.adimp.AdFullscreenActivity",
        ),
    )

    /**
     * 已知广告 SDK 的初始化入口：让 init 不发生，后面所有广告 API 都拿不到实例。
     *
     * 只放**类名稳定**的（SDK 的类名几年不变，界面天天变 —— 这是这条路最划算的原因）。
     */
    val adSdkInits: List<Pair<String, String>> = listOf(
        // 穿山甲 / CSJ / Pangolin（字节系，懂车帝很可能用）
        "com.bytedance.sdk.openadsdk.TTAdSdk" to "init",
        // 优量汇 GDT（美团实测命中 99 次）
        "com.qq.e.comm.managers.GDTAdSdk" to "init",
        "com.qq.e.comm.managers.GDTAdSdk" to "initWithoutStart",
    )

    /** 少量明确的广告域名（大规模域名表走订阅导入，见 HostList）。 */
    val adHosts: List<String> = listOf(
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "sdk.e.qq.com",
        "ad.toutiao.com",
        "pangolin-sdk-toutiao.byteimg.com",
    )
}

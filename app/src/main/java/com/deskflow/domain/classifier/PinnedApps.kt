package com.deskflow.domain.classifier

/**
 * 一键整理时固定到首页（第一张未删除页）的应用，按此顺序铺放；包名精确匹配（大小写不敏感），
 * 缺失即跳过、同包名多处时优先取桌面上的那份。钉选项不参与分类分组与收夹，
 * 且会从既有文件夹 / Dock 中强制抽出（见 AutoArranger）。
 */
object PinnedApps {
    val packages = listOf(
        "bin.mt.plus",                 // MT管理器
        "com.tencent.mobileqq",        // QQ
        "com.tencent.mm",              // 微信
        "com.coolapk.market",          // 酷安
        "com.autonavi.minimap",        // 高德地图
        "me.rerere.rikkahub",          // RikkaHub
        "com.eg.android.AlipayGphone", // 支付宝
        "tv.danmaku.bili",             // 哔哩哔哩
        "com.android.settings",        // 设置
        "com.netease.cloudmusic",      // 网易云音乐
        "org.lsposed.manager",         // LSPosed
    )
}

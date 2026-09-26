package com.deskflow.domain.classifier

/**
 * 一键整理时固定到首页（第一张未删除页）的应用，按此顺序铺放；包名精确匹配，
 * 缺失即跳过、重复包名只取第一个。钉选项不参与分类分组与收夹，
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
    )
}

package com.deskflow.domain.classifier

enum class Category(val id: String, val label: String, val order: Int) {
    SYSTEM("system", "系统", 0),
    SOCIAL("social", "社交", 1),
    MEDIA("media", "影音", 2),
    READING("reading", "资讯阅读", 3),
    SHOPPING("shopping", "购物生活", 4),
    FINANCE("finance", "金融支付", 5),
    WORK("work", "办公效率", 6),
    GAME("game", "游戏", 7),
    PHOTO("photo", "拍摄美化", 8),
    TOOLS("tools", "实用工具", 9),
    XP("xp", "XP插件", 10),
    OTHER("other", "未分类", 11),
    ;

    companion object {
        fun byId(id: String): Category? = entries.firstOrNull { it.id == id }
        val ordered: List<Category> get() = entries.sortedBy { it.order }

        /**
         * 按类别名认领：整理生成的文件夹标题就是 [label]（续编号形如「影音 2」），
         * 用户手工改成同类名字的文件夹也能被识别、归到同一类。
         */
        fun byLabel(raw: String): Category? {
            val withoutIndex = raw.trim().replace(Regex("\\s+\\d+$"), "").trim()
            return entries.firstOrNull { it.label == withoutIndex }
        }
    }
}

/**
 * 分类规则。三层匹配，命中即返回：
 * 1. [exactPackages] 全包名精确匹配
 * 2. [keywords] 包名分段包含匹配（同分段命中多个时取最长关键词）
 * 3. [titleKeywords] 标题包含匹配（同上取最长）
 */
data class CategoryRules(
    val exactPackages: Map<String, Category> = emptyMap(),
    val keywords: Map<String, Category> = emptyMap(),
    val titleKeywords: Map<String, Category> = emptyMap(),
) {
    /** 后者优先，用于用户映射表覆盖内置规则。 */
    fun merge(other: CategoryRules): CategoryRules = CategoryRules(
        exactPackages = exactPackages + other.exactPackages,
        keywords = keywords + other.keywords,
        titleKeywords = titleKeywords + other.titleKeywords,
    )

    val isEmpty: Boolean get() = exactPackages.isEmpty() && keywords.isEmpty() && titleKeywords.isEmpty()
}

object DefaultRules {

    val rules: CategoryRules = CategoryRules(
        exactPackages = mapOf(
            "com.tencent.mm" to Category.SOCIAL,
            "com.tencent.mobileqq" to Category.SOCIAL,
            "com.tencent.qqlive" to Category.MEDIA,
            "com.ss.android.ugc.aweme" to Category.MEDIA,
            "com.smile.gifmaker" to Category.MEDIA,
            "com.eg.android.AlipayGphone" to Category.FINANCE,
            "com.android.settings" to Category.SYSTEM,
            "com.android.systemui" to Category.SYSTEM,
            "com.bbk.launcher2" to Category.SYSTEM,
            "com.android.contacts" to Category.SYSTEM,
            "com.android.dialer" to Category.SYSTEM,
            "com.android.mms" to Category.SYSTEM,
            "com.android.camera" to Category.PHOTO,
            "com.android.gallery3d" to Category.PHOTO,
            "com.android.calculator2" to Category.TOOLS,
            "com.android.deskclock" to Category.TOOLS,
            "com.android.calendar" to Category.TOOLS,
            "com.android.soundrecorder" to Category.TOOLS,
            "com.android.documentsui" to Category.TOOLS,
            "com.android.chrome" to Category.TOOLS,
            "com.android.vending" to Category.SYSTEM,
        ),
        keywords = mapOf(
            // 社交
            "weixin" to Category.SOCIAL,
            "wechat" to Category.SOCIAL,
            "mobileqq" to Category.SOCIAL,
            "weibo" to Category.SOCIAL,
            "momo" to Category.SOCIAL,
            "soulapp" to Category.SOCIAL,
            "tantan" to Category.SOCIAL,
            "xhs" to Category.SOCIAL,
            "tieba" to Category.SOCIAL,
            "telegram" to Category.SOCIAL,
            "discord" to Category.SOCIAL,
            "whatsapp" to Category.SOCIAL,
            "messenger" to Category.SOCIAL,
            "instagram" to Category.SOCIAL,
            // 影音
            "qqlive" to Category.MEDIA,
            "qiyi" to Category.MEDIA,
            "youku" to Category.MEDIA,
            "bilibili" to Category.MEDIA,
            "danmaku" to Category.MEDIA,
            "aweme" to Category.MEDIA,
            "douyin" to Category.MEDIA,
            "gifmaker" to Category.MEDIA,
            "kuaishou" to Category.MEDIA,
            "music" to Category.MEDIA,
            "ximalaya" to Category.MEDIA,
            "huya" to Category.MEDIA,
            "douyu" to Category.MEDIA,
            "youtube" to Category.MEDIA,
            "netflix" to Category.MEDIA,
            "player" to Category.MEDIA,
            "video" to Category.MEDIA,
            // 资讯阅读
            "zhihu" to Category.READING,
            "douban" to Category.READING,
            "weread" to Category.READING,
            "qidian" to Category.READING,
            "fanqie" to Category.READING,
            "news" to Category.READING,
            "toutiao" to Category.READING,
            "novel" to Category.READING,
            "reader" to Category.READING,
            // 购物生活
            "taobao" to Category.SHOPPING,
            "tmall" to Category.SHOPPING,
            "jingdong" to Category.SHOPPING,
            "pinduoduo" to Category.SHOPPING,
            "suning" to Category.SHOPPING,
            "vipshop" to Category.SHOPPING,
            "dangdang" to Category.SHOPPING,
            "meituan" to Category.SHOPPING,
            "sankuai" to Category.SHOPPING,
            "alibaba" to Category.SHOPPING,
            "amazon" to Category.SHOPPING,
            "mall" to Category.SHOPPING,
            "shop" to Category.SHOPPING,
            // 金融
            "alipay" to Category.FINANCE,
            "unionpay" to Category.FINANCE,
            "bank" to Category.FINANCE,
            "icbc" to Category.FINANCE,
            "abchina" to Category.FINANCE,
            "cmbchina" to Category.FINANCE,
            "pingan" to Category.FINANCE,
            "eastmoney" to Category.FINANCE,
            "hexin" to Category.FINANCE,
            "tonghuashun" to Category.FINANCE,
            "wallet" to Category.FINANCE,
            // 办公
            "wework" to Category.WORK,
            "rimet" to Category.WORK,
            "lark" to Category.WORK,
            "wps" to Category.WORK,
            "moffice" to Category.WORK,
            "office" to Category.WORK,
            "evernote" to Category.WORK,
            "notion" to Category.WORK,
            "email" to Category.WORK,
            "mail" to Category.WORK,
            // 游戏
            "game" to Category.GAME,
            "tmgp" to Category.GAME,
            "mihoyo" to Category.GAME,
            "hoyoverse" to Category.GAME,
            "supercell" to Category.GAME,
            // 拍摄美化
            "camera" to Category.PHOTO,
            "gallery" to Category.PHOTO,
            "photo" to Category.PHOTO,
            "meitu" to Category.PHOTO,
            "picsart" to Category.PHOTO,
            "snapseed" to Category.PHOTO,
            "capcut" to Category.PHOTO,
            "lightroom" to Category.PHOTO,
            // 工具
            "browser" to Category.TOOLS,
            "chrome" to Category.TOOLS,
            "filemanager" to Category.TOOLS,
            "fileexplorer" to Category.TOOLS,
            "calculator" to Category.TOOLS,
            "deskclock" to Category.TOOLS,
            "weather" to Category.TOOLS,
            "calendar" to Category.TOOLS,
            "netdisk" to Category.TOOLS,
            "xunlei" to Category.TOOLS,
            "translate" to Category.TOOLS,
            "baidumap" to Category.TOOLS,
            "autonavi" to Category.TOOLS,
            "amap" to Category.TOOLS,
            "cleanmaster" to Category.TOOLS,
            "magisk" to Category.TOOLS,
            "kernelsu" to Category.TOOLS,
            // 系统
            "settings" to Category.SYSTEM,
            "systemui" to Category.SYSTEM,
            "launcher" to Category.SYSTEM,
            "packageinstaller" to Category.SYSTEM,
            "dialer" to Category.SYSTEM,
            "telephony" to Category.SYSTEM,
            "providers" to Category.SYSTEM,
            "vivo" to Category.SYSTEM,
            "bbk" to Category.SYSTEM,
        ),
        titleKeywords = mapOf(
            "微信" to Category.SOCIAL,
            "微博" to Category.SOCIAL,
            "陌陌" to Category.SOCIAL,
            "小红书" to Category.SOCIAL,
            "贴吧" to Category.SOCIAL,
            "视频" to Category.MEDIA,
            "音乐" to Category.MEDIA,
            "抖音" to Category.MEDIA,
            "快手" to Category.MEDIA,
            "游戏" to Category.GAME,
            "淘宝" to Category.SHOPPING,
            "京东" to Category.SHOPPING,
            "拼多多" to Category.SHOPPING,
            "美团" to Category.SHOPPING,
            "饿了么" to Category.SHOPPING,
            "支付宝" to Category.FINANCE,
            "银行" to Category.FINANCE,
            "钱包" to Category.FINANCE,
            "相机" to Category.PHOTO,
            "相册" to Category.PHOTO,
            "图库" to Category.PHOTO,
            "新闻" to Category.READING,
            "头条" to Category.READING,
            "知乎" to Category.READING,
            "小说" to Category.READING,
            "阅读" to Category.READING,
            "邮箱" to Category.WORK,
            "邮件" to Category.WORK,
            "浏览器" to Category.TOOLS,
            "文件管理" to Category.TOOLS,
            "计算器" to Category.TOOLS,
            "天气" to Category.TOOLS,
            "日历" to Category.TOOLS,
            "时钟" to Category.TOOLS,
            "地图" to Category.TOOLS,
            "设置" to Category.SYSTEM,
            "桌面" to Category.SYSTEM,
            "电话" to Category.SYSTEM,
            "短信" to Category.SYSTEM,
            "联系人" to Category.SYSTEM,
        ),
    )
}

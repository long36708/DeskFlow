package com.deskflow.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * 应用使用频率来源：`PACKAGE_USAGE_STATS` 是特殊的 AppOps 权限（非运行时权限），
 * 需用户在系统「使用情况访问」页手动授权，`ACTION_USAGE_ACCESS_SETTINGS` 可直达。
 *
 * 计数用事件流而非 queryUsageStats 分桶：厂商 ROM 对分桶聚合口径不一，
 * 事件流按 ACTIVITY_RESUMED 数数语义最直接。minSdk 28 上该常量值与 MOVE_TO_FOREGROUND 相同。
 */
class UsageStatsProvider(private val context: Context) {

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 近 [days] 天每个包的前台启动次数；无权限或系统尚无统计数据时返回空 map。 */
    fun launchCounts(days: Int = WINDOW_DAYS): Map<String, Int> {
        if (!hasPermission()) return emptyMap()
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val end = System.currentTimeMillis()
        val begin = end - days * DAY_MS
        val counts = HashMap<String, Int>()
        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                event.packageName?.let { counts.merge(it, 1, Int::plus) }
            }
        }
        return counts
    }

    companion object {
        const val WINDOW_DAYS = 30
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

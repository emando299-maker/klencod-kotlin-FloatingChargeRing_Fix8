package com.floatingchargering

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process

/** One row of the "most used apps" list. share is this app's part of all listed apps' time (0..1). */
class AppUsageItem(
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
    val share: Float,
    val icon: Drawable?
)

/**
 * Screen (foreground) time per app, from UsageStatsManager.
 *
 * This is NOT real battery consumption. Android does not give normal apps per-app battery
 * statistics (that needs a system-only permission). Foreground time is only a rough indicator:
 * background drain (location, wake locks, sync) is invisible here.
 */
object AppUsage {

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Heavy work: call from a background thread. */
    fun query(context: Context, windowMs: Long, limit: Int): List<AppUsageItem> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - windowMs, end) ?: return emptyList()

        val resumedAt = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            val ts = event.timeStamp
            // 1 = activity resumed (foreground), 2 = paused, 23 = stopped, 16 = screen off.
            if (type == 16) {
                for ((pkg, start) in resumedAt) totals[pkg] = (totals[pkg] ?: 0L) + (ts - start)
                resumedAt.clear()
                continue
            }
            val pkg = event.packageName ?: continue
            when (type) {
                1 -> if (!resumedAt.containsKey(pkg)) resumedAt[pkg] = ts
                2, 23 -> {
                    val start = resumedAt.remove(pkg)
                    if (start != null) totals[pkg] = (totals[pkg] ?: 0L) + (ts - start)
                }
            }
        }
        for ((pkg, start) in resumedAt) totals[pkg] = (totals[pkg] ?: 0L) + (end - start)

        // Keep only apps whose label can be resolved (visible, launchable apps) and skip this app.
        val pm = context.packageManager
        class Row(val pkg: String, val label: String, val ms: Long)
        val rows = ArrayList<Row>()
        for ((pkg, ms) in totals) {
            if (pkg == context.packageName || ms < 1000L) continue
            try {
                @Suppress("DEPRECATION")
                val info = pm.getApplicationInfo(pkg, 0)
                rows.add(Row(pkg, pm.getApplicationLabel(info).toString(), ms))
            } catch (e: PackageManager.NameNotFoundException) {
                // Not visible to this app: skip.
            }
        }
        rows.sortByDescending { it.ms }
        val total = rows.sumOf { it.ms }.toFloat()
        if (total <= 0f) return emptyList()

        return rows.take(limit).map { r ->
            val icon = try { pm.getApplicationIcon(r.pkg) } catch (e: Exception) { null }
            AppUsageItem(r.pkg, r.label, r.ms, r.ms / total, icon)
        }
    }
}

package com.floatingchargering

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class PowerState { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

enum class SignConvention { NORMAL, INVERTED, UNSIGNED }

/**
 * One consistent reading of the battery.
 *
 * currentMa is signed: positive = current entering the battery, negative = leaving it.
 * It is null when no reliable reading exists. powerW is the battery-side power
 * (|current| x voltage); it is NOT the charger's output or the USB input power.
 */
data class BatterySnapshot(
    val levelPercent: Int,
    val state: PowerState,
    val pluggedType: Int,
    val voltageV: Double?,
    val temperatureC: Double?,
    val currentMa: Double?,
    val currentSource: String?,
    val powerW: Double?,
    val levelPrecise: Double?
)

class BatteryReader(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    private class CurrentReading(val ma: Double, val source: String, val trustedSign: Boolean)
    private class CounterSample(val timeMs: Long, val microAh: Long)

    private val counterSamples = ArrayDeque<CounterSample>()
    private var lastState: PowerState? = null
    private var lastPlugged = -1
    private val sysfsFailed = HashSet<String>()

    // Sign evidence learned from real readings (see convention()).
    private var chargingSign = 0
    private var dischargingSign = 0

    // Precise battery percentage (e.g. 22.545 %) = CHARGE_COUNTER / full capacity.
    private val prefs = appContext.getSharedPreferences("battery_reader", Context.MODE_PRIVATE)
    private var learnedFullUah: Double = prefs.getLong("full_uah_v3", 0L).toDouble()
    private val fullSamples = ArrayList<Double>()
    private var anchorPct = Double.NaN
    private var anchorCounter = 0L
    private var smoothPct = Double.NaN
    private var smoothTimeMs = 0L
    private var dTargetPct = Double.NaN

    init {
        if (learnedFullUah > 0.0) fullSamples.add(learnedFullUah)
    }
    private var designFullUah: Double? = null
    private var designTried = false
    private var lastIntLevel = -1
    private var lastLevelReadMs = 0L
    private var dPreciseSource = "none"
    private var dFullUsedUah = 0.0

    // Diagnostics (shown in MainActivity).
    private var dRawNow = Long.MIN_VALUE
    private var dRawAvg = Long.MIN_VALUE
    private var dRawCounter = Long.MIN_VALUE
    private var dSysNow: Long? = null
    private var dSysAvg: Long? = null
    private var dMv = -1
    private var lastSnapshot: BatterySnapshot? = null

    // Freshness diagnostics: the sticky broadcast can lag behind BatteryManager properties.
    private var dIntentLevel = -1
    private var dPropLevel = Int.MIN_VALUE
    private var dIntentStatus = -1
    private var dPropStatus = Int.MIN_VALUE
    private var tIntentLevelChanged = 0L
    private var tPropLevelChanged = 0L
    private var tCounterChanged = 0L
    private var lastCounterSeen = Long.MIN_VALUE

    fun read(): BatterySnapshot {
        // Passing a null receiver returns the sticky ACTION_BATTERY_CHANGED intent
        // without registering anything, so there is nothing to leak or unregister.
        val intent: Intent? = appContext.registerReceiver(null, batteryFilter)

        val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val intentLevel = if (rawLevel >= 0 && scale > 0) (rawLevel * 100 / scale).coerceIn(0, 100) else -1
        val nowMs = SystemClock.elapsedRealtime()

        // The sticky ACTION_BATTERY_CHANGED intent is only refreshed by the system every
        // several seconds on many Samsung builds, so it lags behind reality. The
        // BatteryManager properties are read from the battery driver on every call, so
        // they are used first and the intent is only the fallback.
        val propLevel = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val propStatus = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        if (intentLevel != dIntentLevel) { dIntentLevel = intentLevel; tIntentLevelChanged = nowMs }
        if (propLevel != dPropLevel) { dPropLevel = propLevel; tPropLevelChanged = nowMs }
        dPropStatus = propStatus

        // A property level is trusted only if it is plausible and close to the intent level
        // (0 is also what some firmware returns for "unsupported").
        val propLevelOk = propLevel != Int.MIN_VALUE && propLevel in 0..100 &&
            (intentLevel < 0 || abs(propLevel - intentLevel) <= 2) &&
            !(propLevel == 0 && intentLevel > 1)
        val level = if (propLevelOk) propLevel else intentLevel

        val intentStatus = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        dIntentStatus = intentStatus
        val status = if (propStatus in 1..5) propStatus else intentStatus
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> PowerState.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> PowerState.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> PowerState.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> PowerState.NOT_CHARGING
            else -> PowerState.UNKNOWN
        }

        // EXTRA_VOLTAGE is in millivolts; accept only a plausible Li-ion range.
        val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        dMv = mv
        val voltage = if (mv in 2500..5500) mv / 1000.0 else null

        // EXTRA_TEMPERATURE is in tenths of a degree Celsius.
        val tempRaw = if (intent != null && intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE))
            intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) else Int.MIN_VALUE
        val temp = if (tempRaw in -400..1200) tempRaw / 10.0 else null

        if (state != lastState || plugged != lastPlugged) {
            counterSamples.clear()
            smoothPct = Double.NaN
            lastState = state
            lastPlugged = plugged
        }

        val reading = readCurrent(nowMs)
        var signedMa: Double? = null
        if (reading != null) {
            signedMa = if (reading.trustedSign) {
                reading.ma
            } else {
                updateSignEvidence(reading.ma, state, plugged)
                applyDirection(reading.ma, state)
            }
        }

        val power = if (signedMa != null && voltage != null) abs(signedMa) * voltage / 1000.0 else null
        val precise = computePreciseLevel(level, dRawCounter, nowMs, signedMa, state)

        val snap = BatterySnapshot(level, state, plugged, voltage, temp, signedMa, reading?.source, power, precise)
        lastSnapshot = snap
        return snap
    }

    // ---- current acquisition -------------------------------------------------

    private fun readCurrent(nowMs: Long): CurrentReading? {
        dRawNow = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        dRawAvg = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        dRawCounter = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (dRawCounter != lastCounterSeen) { lastCounterSeen = dRawCounter; tCounterChanged = nowMs }
        sampleCounter(nowMs, dRawCounter)

        toMilliAmps(dRawNow)?.let { return CurrentReading(it, "CURRENT_NOW", false) }
        toMilliAmps(dRawAvg)?.let { return CurrentReading(it, "CURRENT_AVERAGE", false) }

        // Some firmware blocks the BatteryManager properties but leaves sysfs readable.
        // On Android 14 SELinux usually denies this for apps; failure is simply skipped.
        dSysNow = readSysfs("current_now")
        dSysAvg = readSysfs("current_avg")
        dSysNow?.let { v -> toMilliAmps(v)?.let { return CurrentReading(it, "sysfs current_now", false) } }
        dSysAvg?.let { v -> toMilliAmps(v)?.let { return CurrentReading(it, "sysfs current_avg", false) } }

        // Last resort: derive an average from how fast CHARGE_COUNTER changes.
        // Coarse and delayed (needs >= 30 s of history); labelled as an estimate.
        estimateFromCounter()?.let { return CurrentReading(it, "CHARGE_COUNTER (estimate)", true) }
        return null
    }

    /**
     * Android specifies microamps, but some Samsung builds return milliamps.
     * The wallpaper is only visible while the screen is on, where the real battery
     * current is far above 30 mA, i.e. above 30,000 in microamps. So |raw| >= 30,000
     * is treated as microamps and anything smaller as milliamps. This is a heuristic;
     * the unit actually used is visible in the diagnostics screen. Zero and
     * "unsupported" are never turned into a value.
     */
    private fun toMilliAmps(raw: Long): Double? {
        if (raw == Long.MIN_VALUE || raw == 0L) return null
        val ma = if (abs(raw) >= 30_000L) raw / 1000.0 else raw.toDouble()
        if (abs(ma) < 1.0 || abs(ma) > 12_000.0) return null
        return ma
    }

    private fun readSysfs(name: String): Long? {
        if (name in sysfsFailed) return null
        return try {
            File("/sys/class/power_supply/battery/$name").readText().trim().toLongOrNull()
        } catch (e: Exception) {
            sysfsFailed.add(name)
            null
        }
    }

    private fun sampleCounter(nowMs: Long, counterUah: Long) {
        // Below 20,000 the value is probably mAh, not uAh; do not guess the unit.
        if (counterUah == Long.MIN_VALUE || counterUah < 20_000L) {
            counterSamples.clear()
            return
        }
        counterSamples.addLast(CounterSample(nowMs, counterUah))
        while (counterSamples.size > 1 && nowMs - counterSamples.first().timeMs > 90_000L) {
            counterSamples.removeFirst()
        }
    }

    private fun estimateFromCounter(): Double? {
        if (counterSamples.size < 2) return null
        val first = counterSamples.first()
        val last = counterSamples.last()
        val dtMs = last.timeMs - first.timeMs
        if (dtMs < 30_000L) return null
        val deltaUah = last.microAh - first.microAh
        if (deltaUah == 0L) return null
        val ma = (deltaUah / 1000.0) / (dtMs / 3_600_000.0)
        if (abs(ma) < 1.0 || abs(ma) > 12_000.0) return null
        return ma
    }

    // ---- precise percentage ------------------------------------------------------

    /**
     * Returns a smooth percentage with decimals, or null when it cannot be computed reliably
     * (the UI then shows only the integer level).
     *
     * How it works:
     *  - Every time the integer level changes by exactly one step (seen within a few seconds,
     *    from the fresh BatteryManager level) a transition is recorded. The counter difference
     *    between two consecutive same-direction transitions is exactly 1 % of the full capacity,
     *    which gives the capacity without caring whether the system rounds or truncates.
     *    The median of the last 7 such samples is used, so one noisy step cannot move it.
     *  - Between transitions: percent = anchor + counter change / capacity. The value therefore
     *    lines up with the system level at every step.
     *  - Before the first transition is seen, capacity from the previous run (or the hidden
     *    design capacity) is used with an absolute formula, accepted only if it agrees with
     *    the integer level within 1.5 points.
     *  - CHARGE_COUNTER moves in coarse steps, so the shown value is smoothed: it advances at the
     *    rate implied by the measured current and is pulled gently toward the counter value,
     *    and it never moves against the charging direction.
     * The system level is assumed to be truncated; if it is rounded, decimals can be off by up to 0.5.
     */
    private fun computePreciseLevel(
        level: Int, counterUah: Long, nowMs: Long, signedMa: Double?, state: PowerState
    ): Double? {
        val counterOk = counterUah != Long.MIN_VALUE && counterUah >= 20_000L
        if (level < 0 || !counterOk) {
            lastIntLevel = level
            lastLevelReadMs = nowMs
            smoothPct = Double.NaN
            dPreciseSource = "none (no usable CHARGE_COUNTER)"
            return null
        }

        val fresh = lastIntLevel != -1 && nowMs - lastLevelReadMs <= 5_000L
        if (fresh && abs(level - lastIntLevel) == 1 && level in 5..99) {
            val pctAtChange = if (level > lastIntLevel) level.toDouble() else level + 1.0
            if (!anchorPct.isNaN() && abs(abs(pctAtChange - anchorPct) - 1.0) < 0.01) {
                val sample = abs(counterUah - anchorCounter) * 100.0
                if (sample in 1_500_000.0..20_000_000.0) addFullSample(sample)
            }
            anchorPct = pctAtChange
            anchorCounter = counterUah
        }
        lastIntLevel = level
        lastLevelReadMs = nowMs

        if (!designTried) {
            designTried = true
            designFullUah = readDesignCapacityUah()
        }

        val full: Double? = if (learnedFullUah > 0.0) learnedFullUah else designFullUah
        var target: Double? = null
        var usedFull = 0.0
        var source = ""

        if (full != null && !anchorPct.isNaN()) {
            val p = anchorPct + (counterUah - anchorCounter) * 100.0 / full
            if (p in 0.0..100.0 && abs(p - level) <= 1.5) {
                target = p
                usedFull = full
                source = if (learnedFullUah > 0.0) "anchored + learned capacity" else "anchored + design capacity"
            }
        }
        if (target == null) {
            val candidates = ArrayList<Pair<String, Double>>(2)
            if (learnedFullUah > 0.0) candidates.add(Pair("learned capacity (absolute)", learnedFullUah))
            designFullUah?.let { candidates.add(Pair("design capacity (absolute)", it)) }
            for ((name, f) in candidates) {
                val p = counterUah * 100.0 / f
                if (p in 0.0..100.0 && abs(p - level) <= 1.5) {
                    target = p
                    usedFull = f
                    source = name
                    break
                }
            }
            if (target == null) {
                smoothPct = Double.NaN
                dPreciseSource = if (candidates.isEmpty() && anchorPct.isNaN())
                    "none (waiting for the first level change)" else "none (capacity inconsistent with level)"
                return null
            }
        }

        val tgt: Double = target ?: return null
        dPreciseSource = source
        dFullUsedUah = usedFull
        dTargetPct = tgt

        val ratePerSec = if (signedMa != null && (state == PowerState.CHARGING || state == PowerState.DISCHARGING))
            (signedMa * 1000.0 / usedFull * 100.0 / 3600.0).coerceIn(-0.05, 0.05) else 0.0
        return smooth(tgt, ratePerSec, nowMs, state)
    }

    private fun addFullSample(sample: Double) {
        fullSamples.add(sample)
        if (fullSamples.size > 7) fullSamples.removeAt(0)
        val sorted = fullSamples.sorted()
        val mid = sorted.size / 2
        learnedFullUah = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        prefs.edit().putLong("full_uah_v3", learnedFullUah.toLong()).apply()
    }

    private fun smooth(target: Double, ratePerSec: Double, nowMs: Long, state: PowerState): Double {
        if (smoothPct.isNaN() || abs(target - smoothPct) > 2.0 || nowMs - smoothTimeMs > 15_000L) {
            smoothPct = target
            smoothTimeMs = nowMs
            return smoothPct
        }
        val dt = (nowMs - smoothTimeMs) / 1000.0
        smoothTimeMs = nowMs
        var v = smoothPct + ratePerSec * dt
        v += (target - v) * (1.0 - exp(-dt / 20.0))
        when (state) {
            PowerState.CHARGING -> v = max(v, smoothPct)
            PowerState.DISCHARGING -> v = min(v, smoothPct)
            else -> {}
        }
        smoothPct = v.coerceIn(0.0, 100.0)
        return smoothPct
    }

    private fun readDesignCapacityUah(): Double? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val profile = cls.getConstructor(Context::class.java).newInstance(appContext)
        val mah = cls.getMethod("getBatteryCapacity").invoke(profile) as Double
        if (mah in 1000.0..20000.0) mah * 1000.0 else null
    } catch (e: Throwable) {
        null
    }

    // ---- sign handling ---------------------------------------------------------

    private fun updateSignEvidence(ma: Double, state: PowerState, plugged: Int) {
        if (state == PowerState.CHARGING && plugged != 0 && abs(ma) >= 300.0) {
            chargingSign = if (ma > 0) 1 else -1
        }
        if (state == PowerState.DISCHARGING && plugged == 0 && abs(ma) >= 100.0) {
            dischargingSign = if (ma > 0) 1 else -1
        }
    }

    /**
     * Android's convention: positive = charging, negative = discharging. Some devices
     * invert it, and some report magnitude only. The convention is learned from real
     * readings taken while the battery status clearly says charging or discharging.
     */
    private fun convention(): SignConvention = when {
        chargingSign != 0 && dischargingSign != 0 -> when {
            chargingSign > 0 && dischargingSign < 0 -> SignConvention.NORMAL
            chargingSign < 0 && dischargingSign > 0 -> SignConvention.INVERTED
            else -> SignConvention.UNSIGNED
        }
        dischargingSign > 0 -> SignConvention.UNSIGNED
        chargingSign < 0 -> SignConvention.INVERTED
        else -> SignConvention.NORMAL
    }

    private fun applyDirection(ma: Double, state: PowerState): Double = when (convention()) {
        SignConvention.NORMAL -> ma
        SignConvention.INVERTED -> -ma
        SignConvention.UNSIGNED -> when (state) {
            PowerState.DISCHARGING -> -abs(ma)
            else -> abs(ma)
        }
    }

    // ---- diagnostics -----------------------------------------------------------

    private fun raw(v: Long): String = if (v == Long.MIN_VALUE) "unsupported" else v.toString()

    fun diagnosticText(): String {
        val s = lastSnapshot
        val sb = StringBuilder()
        sb.append("Level: ").append(s?.levelPercent ?: -1).append("%\n")
        sb.append("Status: ").append(s?.state ?: "?").append("  plugged=").append(s?.pluggedType ?: 0).append('\n')
        val nowMs = SystemClock.elapsedRealtime()
        fun age(t: Long): String = if (t == 0L) "?" else "${(nowMs - t) / 1000}s"
        sb.append("Level intent=").append(dIntentLevel).append(" (unchanged ").append(age(tIntentLevelChanged))
            .append(")  property=").append(if (dPropLevel == Int.MIN_VALUE) "n/a" else dPropLevel.toString())
            .append(" (unchanged ").append(age(tPropLevelChanged)).append(")\n")
        sb.append("Status intent=").append(dIntentStatus).append("  property=")
            .append(if (dPropStatus == Int.MIN_VALUE) "n/a" else dPropStatus.toString()).append('\n')
        sb.append("CHARGE_COUNTER unchanged for ").append(age(tCounterChanged)).append('\n')
        sb.append("EXTRA_VOLTAGE: ").append(dMv).append(" mV\n")
        sb.append("CURRENT_NOW raw: ").append(raw(dRawNow)).append('\n')
        sb.append("CURRENT_AVERAGE raw: ").append(raw(dRawAvg)).append('\n')
        sb.append("CHARGE_COUNTER raw: ").append(raw(dRawCounter)).append('\n')
        sb.append("sysfs current_now: ").append(dSysNow?.toString() ?: "n/a").append('\n')
        sb.append("sysfs current_avg: ").append(dSysAvg?.toString() ?: "n/a").append('\n')
        val p = s?.levelPrecise
        sb.append("Precise level: ")
            .append(if (p != null) String.format(java.util.Locale.US, "%.3f%%", p) else "--")
            .append("  via ").append(dPreciseSource)
        if (p != null) sb.append(String.format(java.util.Locale.US, "  (full=%.0f mAh)", dFullUsedUah / 1000.0))
        if (p != null && !dTargetPct.isNaN()) sb.append(String.format(java.util.Locale.US, "  raw=%.3f", dTargetPct))
        sb.append('\n')
        sb.append("Sign convention: ").append(convention())
            .append("  (chg=").append(chargingSign).append(", dis=").append(dischargingSign).append(")\n")
        val ma = s?.currentMa
        if (ma != null) {
            sb.append("Result: ").append(String.format(java.util.Locale.US, "%.0f", ma)).append(" mA  via ")
                .append(s?.currentSource).append('\n')
        } else {
            sb.append("Result: no reliable current reading\n")
        }
        val w = s?.powerW
        sb.append("Battery power: ")
            .append(if (w != null) String.format(java.util.Locale.US, "%.2f W", w) else "-- W")
        return sb.toString()
    }
}

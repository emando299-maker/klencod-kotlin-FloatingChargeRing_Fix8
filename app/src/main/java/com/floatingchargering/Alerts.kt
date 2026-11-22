package com.floatingchargering

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/** Preference keys (stored in the same file as the ring settings) and sound lookup. */
object AlertSettings {
    const val KEY_FULL_ON = "alert_full_on"
    const val KEY_FULL_LEVEL = "alert_full_level"
    const val KEY_FULL_SOUND = "alert_full_sound"
    const val KEY_LOW_ON = "alert_low_on"
    const val KEY_LOW_LEVEL = "alert_low_level"
    const val KEY_LOW_SOUND = "alert_low_sound"
    const val KEY_VIBRATE = "alert_vibrate"

    const val DEFAULT_FULL_LEVEL = 100
    const val DEFAULT_LOW_LEVEL = 20

    /**
     * Not set -> the system default for [defaultType]; empty string -> silent (null);
     * otherwise the URI the user picked.
     */
    fun soundUri(prefs: SharedPreferences, key: String, defaultType: Int): Uri? {
        val stored = prefs.getString(key, null)
        return when {
            stored == null -> RingtoneManager.getDefaultUri(defaultType)
            stored.isEmpty() -> null
            else -> Uri.parse(stored)
        }
    }
}

/**
 * Plays an alert sound (alarm stream) and vibration.
 * One-shot mode stops by itself after a limit; loop mode repeats sound and vibration until
 * [stop] is called, and keeps the CPU awake (capped at 6 hours) so it keeps ringing with the screen off.
 */
object AlertPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var appContext: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var looping = false
    private var loopVibrate = false
    private var loopTicks = 0

    private val stopRunnable = Runnable { stop() }

    private val loopTick = object : Runnable {
        override fun run() {
            if (!looping) return
            try {
                val r = ringtone
                if (r != null && !r.isPlaying) r.play()
            } catch (_: Exception) {}
            loopTicks++
            val ctx = appContext
            if (loopVibrate && ctx != null && loopTicks % 3 == 0) vibratePattern(ctx)
            handler.postDelayed(this, 1000L)
        }
    }

    /** True while a looping alarm is ringing. */
    val isLooping: Boolean get() = looping

    fun play(context: Context, uri: Uri?, vibrate: Boolean, maxMs: Long = 10_000L, loop: Boolean = false) {
        // A test sound must never interrupt a real ringing alarm.
        if (looping && !loop) return
        stop()
        val app = context.applicationContext
        appContext = app
        if (uri != null) {
            try {
                val r = RingtoneManager.getRingtone(app, uri)
                if (r != null) {
                    r.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    r.play()
                    ringtone = r
                }
            } catch (e: Exception) {
                ringtone = null
            }
        }
        if (vibrate) vibratePattern(app)

        if (loop) {
            looping = true
            loopVibrate = vibrate
            loopTicks = 0
            acquireWake(app)
            handler.postDelayed(loopTick, 1000L)
        } else {
            handler.postDelayed(stopRunnable, maxMs)
        }
    }

    /** Stops a one-shot/test sound but leaves a real looping alarm alone. */
    fun stopIfNotLooping() {
        if (!looping) stop()
    }

    fun stop() {
        looping = false
        handler.removeCallbacks(loopTick)
        handler.removeCallbacks(stopRunnable)
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        releaseWake()
        val ctx = appContext ?: return
        try { vibrator(ctx)?.cancel() } catch (_: Exception) {}
    }

    private fun acquireWake(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "floatingchargering:alarm")
            wl.setReferenceCounted(false)
            wl.acquire(6L * 60L * 60L * 1000L)
            wakeLock = wl
        } catch (_: Exception) {}
    }

    private fun releaseWake() {
        try {
            val wl = wakeLock
            if (wl != null && wl.isHeld) wl.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): Vibrator? = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    @Suppress("DEPRECATION")
    private fun vibratePattern(ctx: Context) {
        val v = vibrator(ctx) ?: return
        val pattern = longArrayOf(0, 500, 250, 500, 250, 500)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                v.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }
}

/**
 * Watches ACTION_BATTERY_CHANGED (event driven, works with the screen off) and raises
 * one alert per charge / discharge session:
 *  - "charge complete": a charger is connected and the level reached the chosen limit (or status is FULL).
 *    This alarm keeps ringing until the charger is unplugged;
 *  - "low battery": no charger and the level is at or below the chosen limit.
 * Settings are read on every event, so changes apply immediately. The system sends this
 * broadcast with some delay on certain devices, so an alert can come a few seconds late.
 */
class BatteryAlertMonitor(private val context: Context, private val prefs: SharedPreferences) {

    companion object {
        private const val CHANNEL_ID = "battery_alerts"
        const val ID_FULL = 2
        const val ID_LOW = 3
    }

    private var initialized = false
    private var fullAlerted = false
    private var lowAlerted = false

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Battery alerts", NotificationManager.IMPORTANCE_HIGH)
            channel.setSound(null, null)       // the sound is played by AlertPlayer
            channel.enableVibration(false)
            nm.createNotificationChannel(channel)
        }
    }

    fun onBattery(intent: Intent) {
        val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (rawLevel < 0 || scale <= 0) return
        val level = rawLevel * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        // Unplugging ends the charge-complete alarm.
        if (plugged == 0) stopFullAlarm()

        val fullLevel = prefs.getInt(AlertSettings.KEY_FULL_LEVEL, AlertSettings.DEFAULT_FULL_LEVEL)
        val lowLevel = prefs.getInt(AlertSettings.KEY_LOW_LEVEL, AlertSettings.DEFAULT_LOW_LEVEL)
        val fullReached = plugged != 0 && (level >= fullLevel || status == BatteryManager.BATTERY_STATUS_FULL)
        val lowReached = plugged == 0 && level <= lowLevel

        if (!initialized) {
            // Do not alert for a condition that already holds when monitoring starts.
            initialized = true
            fullAlerted = fullReached
            lowAlerted = lowReached
            return
        }

        if (plugged == 0 || (level < fullLevel - 2 && status != BatteryManager.BATTERY_STATUS_FULL)) {
            fullAlerted = false
        }
        if (plugged != 0 || level >= lowLevel + 3) lowAlerted = false

        if (prefs.getBoolean(AlertSettings.KEY_FULL_ON, true) && fullReached && !fullAlerted) {
            fullAlerted = true
            fire(true, level)
        }
        if (prefs.getBoolean(AlertSettings.KEY_LOW_ON, true) && lowReached && !lowAlerted) {
            lowAlerted = true
            fire(false, level)
        }
    }

    /** ACTION_POWER_DISCONNECTED arrives faster than the battery broadcast, so it ends the alarm sooner. */
    fun onPowerDisconnected() {
        stopFullAlarm()
        fullAlerted = false
    }

    private fun stopFullAlarm() {
        if (!AlertPlayer.isLooping) return
        AlertPlayer.stop()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ID_FULL)
    }

    private fun fire(full: Boolean, level: Int) {
        val uri = AlertSettings.soundUri(
            prefs,
            if (full) AlertSettings.KEY_FULL_SOUND else AlertSettings.KEY_LOW_SOUND,
            if (full) RingtoneManager.TYPE_NOTIFICATION else RingtoneManager.TYPE_ALARM
        )
        AlertPlayer.play(context, uri, prefs.getBoolean(AlertSettings.KEY_VIBRATE, true), loop = full)
        postNotification(full, level)
    }

    private fun postNotification(full: Boolean, level: Int) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            context, 11,
            Intent(context, FloatingRingService::class.java).setAction(FloatingRingService.ACTION_STOP_ALERT),
            flags
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        val notification = builder
            .setSmallIcon(if (full) android.R.drawable.ic_lock_idle_charging else android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle(if (full) "اكتمل الشحن" else "البطارية منخفضة")
            .setContentText(
                if (full) "وصلت البطارية إلى $level%، افصل الشاحن لإيقاف التنبيه"
                else "المتبقي $level%، وصّل الشاحن"
            )
            .setContentIntent(open)
            .setOngoing(full)
            .setAutoCancel(!full)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "إيقاف الصوت", stop
                ).build()
            )
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(if (full) ID_FULL else ID_LOW, notification)
    }
}

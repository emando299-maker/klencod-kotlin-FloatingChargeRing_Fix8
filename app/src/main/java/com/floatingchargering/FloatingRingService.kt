package com.floatingchargering

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.max

class FloatingRingService : Service() {

    companion object {
        const val PREFS = "ring_prefs"
        const val KEY_SIZE = "size"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_THEME = "theme"
        const val DEFAULT_SIZE = 400
        const val MAX_SIZE = 1000
        const val ACTION_STOP = "com.floatingchargering.STOP"
        const val ACTION_STOP_ALERT = "com.floatingchargering.STOP_ALERT"
        private const val CHANNEL_ID = "floating_ring"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var wm: WindowManager
    private var ring: RingView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenOn = true
    private var receiverRegistered = false
    private var alertMonitor: BatteryAlertMonitor? = null
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED) alertMonitor?.onPowerDisconnected()
            else alertMonitor?.onBattery(intent)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_SIZE) applySize()
        if (key == KEY_THEME) ring?.setTheme(Themes.byId(prefs.getString(KEY_THEME, null)))
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
            }
            updateRunning()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_ALERT) {
            AlertPlayer.stop()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(BatteryAlertMonitor.ID_FULL)
            nm.cancel(BatteryAlertMonitor.ID_LOW)
            if (!running) {
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }

        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ring == null && !createOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAlertMonitor()
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            batteryReceiverRegistered = false
        }
        alertMonitor = null
        AlertPlayer.stop()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(BatteryAlertMonitor.ID_FULL)
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        ring?.let {
            it.setRunning(false)
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        ring = null
        params = null
        super.onDestroy()
    }

    // ---- battery alerts --------------------------------------------------------------

    /** Event-driven (no polling): runs while this service runs, even with the screen off. */
    private fun startAlertMonitor() {
        if (batteryReceiverRegistered) return
        alertMonitor = BatteryAlertMonitor(this, prefs)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        batteryReceiverRegistered = true
    }

    // ---- foreground notification -------------------------------------------------

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating ring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(
            this, 0, Intent(this, FloatingRingService::class.java).setAction(ACTION_STOP), flags
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Floating Charge Ring")
            .setContentText("النافذة العائمة تعمل")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "إيقاف", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- overlay window ------------------------------------------------------------

    private fun createOverlay(): Boolean {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val size = prefs.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(0, MAX_SIZE)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            max(size, 1), max(size, 1), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START

        val dm = resources.displayMetrics
        lp.x = prefs.getInt(KEY_X, (dm.widthPixels - size) / 2)
        lp.y = prefs.getInt(KEY_Y, (dm.heightPixels * 0.30f).toInt())
        clamp(lp)

        val view = RingView(this)
        view.setTheme(Themes.byId(prefs.getString(KEY_THEME, null)))
        view.setOnTouchListener(dragListener(lp))

        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            return false
        }
        ring = view
        params = lp

        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        applySize()
        return true
    }

    private fun dragListener(lp: WindowManager.LayoutParams) = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = lp.x
                dragStartY = lp.y
                touchStartX = e.rawX
                touchStartY = e.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x = dragStartX + (e.rawX - touchStartX).toInt()
                lp.y = dragStartY + (e.rawY - touchStartY).toInt()
                clamp(lp)
                ring?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
                true
            }
            MotionEvent.ACTION_UP -> {
                prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                true
            }
            else -> false
        }
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f

    /** Keeps the window centre on the screen so it can always be grabbed. */
    private fun clamp(lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        lp.x = lp.x.coerceIn(-lp.width / 2, dm.widthPixels - lp.width / 2)
        lp.y = lp.y.coerceIn(-lp.height / 2, dm.heightPixels - lp.height / 2)
    }

    /** Size 0 hides the ring (1 px, untouchable window); 1..1000 is the diameter in pixels. */
    private fun applySize() {
        val view = ring ?: return
        val lp = params ?: return
        val newSize = prefs.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(0, MAX_SIZE)

        val centerX = lp.x + lp.width / 2
        val centerY = lp.y + lp.height / 2
        if (newSize <= 0) {
            lp.width = 1
            lp.height = 1
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            view.visibility = View.INVISIBLE
        } else {
            lp.width = newSize
            lp.height = newSize
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            view.visibility = View.VISIBLE
        }
        lp.x = centerX - lp.width / 2
        lp.y = centerY - lp.height / 2
        clamp(lp)
        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
        updateRunning()
    }

    private fun updateRunning() {
        val size = if (::prefs.isInitialized) prefs.getInt(KEY_SIZE, DEFAULT_SIZE) else DEFAULT_SIZE
        ring?.setRunning(screenOn && size > 0)
    }
}

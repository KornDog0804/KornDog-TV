package com.lumora.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lumora.R

/**
 * Keeps the phone-side Cast relay/transcode alive while the screen is off.
 *
 * Chromecast is pulling media from CastRelayServer on this phone, so Android
 * must not suspend the process, CPU, or Wi-Fi while that relay is active.
 */
class CastForegroundService : Service() {

    private fun serviceLog(message: String) {
        runCatching {
            java.io.File("/sdcard/Download/castservice.log")
                .appendText(
                    "${System.currentTimeMillis()}: $message\n"
                )
        }
        android.util.Log.d("CastForegroundService", message)
    }


    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        serviceLog("SERVICE_CREATE")

        acquireWakeLock()
        acquireWifiLock()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        serviceLog(
            "SERVICE_START_COMMAND flags=$flags startId=$startId"
        )

        createNotificationChannel()

        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Casting to your TV")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        // Re-acquire if Android recreated the service.
        acquireWakeLock()
        acquireWifiLock()

        return START_STICKY
    }

    override fun onDestroy() {
        serviceLog("SERVICE_DESTROY")
        releaseLocks()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager =
            getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Casting",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Keeps Cast streaming active while the screen is off"
                setShowBadge(false)
            }
        )
    }

    private fun acquireWakeLock() {
        val existing = wakeLock

        if (existing?.isHeld == true) {
            return
        }

        val powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock =
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:cast"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

        serviceLog("WAKE_LOCK_ACQUIRED held=${wakeLock?.isHeld}")
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val existing = wifiLock

        if (existing?.isHeld == true) {
            return
        }

        val wifiManager =
            applicationContext.getSystemService(
                Context.WIFI_SERVICE
            ) as WifiManager

        wifiLock =
            wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$packageName:cast"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

        serviceLog("WIFI_LOCK_ACQUIRED held=${wifiLock?.isHeld}")
    }

    private fun releaseLocks() {
        runCatching {
            wakeLock
                ?.takeIf { it.isHeld }
                ?.release()
        }

        wakeLock = null

        runCatching {
            wifiLock
                ?.takeIf { it.isHeld }
                ?.release()
        }

        wifiLock = null

        serviceLog("LOCKS_RELEASED")
    }

    companion object {
        private const val CHANNEL_ID = "cast_stream"
        private const val NOTIF_ID = 43

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(
                    context,
                    CastForegroundService::class.java
                )
            )
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(
                    context,
                    CastForegroundService::class.java
                )
            )
        }
    }
}

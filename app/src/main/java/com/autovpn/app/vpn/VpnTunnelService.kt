package com.autovpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.autovpn.app.MainActivity
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * NOTE ON THE GOMOBILE BINDING NAMES:
 * The exact class/method names below (Libv2ray.newCoreController, CoreController, etc.)
 * come from https://pkg.go.dev/github.com/2dust/AndroidLibXrayLite as of the version this
 * project targets. gomobile sometimes renames things slightly between releases -
 * after you add libv2ray.aar to app/libs, let Android Studio autocomplete confirm the
 * exact names and adjust here if a rename happened upstream.
 */
class VpnTunnelService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.autovpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.autovpn.app.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_SERVER_NAME = "server_name"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIF_ID = 1

        @Volatile var isRunning = false
            private set
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null

    private val callbackHandler = object : libv2ray.CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long {
            isRunning = false
            return 0
        }
        override fun onEmitStatus(level: Long, msg: String): Long = 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel()
                stopSelf()
            }
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "server"
                if (configJson != null) {
                    startForeground(NOTIF_ID, buildNotification(serverName))
                    startTunnel(configJson)
                }
            }
        }
        return START_STICKY
    }

    private fun startTunnel(configJson: String) {
        // Stop only the running Xray core loop, keep the TUN interface alive if we
        // already have one (this is what makes "next config" instant and avoids
        // asking for VPN permission again every time the user switches servers).
        try { coreController?.stopLoop() } catch (e: Exception) { }
        coreController = null

        if (tunFd == null) {
            val builder = Builder()
                .setSession("AutoVPN")
                .addAddress("10.10.14.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            // Exclude our own app from the tunnel so Xray's own outbound connections
            // go out directly instead of looping back into the VPN interface.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) { /* ignore */ }

            tunFd = builder.establish()
        }
        val fd = tunFd ?: return

        coreController = Libv2ray.newCoreController(callbackHandler)
        try {
            coreController?.startLoop(configJson, fd.fd)
            isRunning = true
        } catch (e: Exception) {
            isRunning = false
        }
    }

    private fun stopTunnel() {
        try { coreController?.stopLoop() } catch (e: Exception) { }
        coreController = null
        try { tunFd?.close() } catch (e: Exception) { }
        tunFd = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun buildNotification(serverName: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "VPN status", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("متصل به VPN")
            .setContentText(serverName)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}

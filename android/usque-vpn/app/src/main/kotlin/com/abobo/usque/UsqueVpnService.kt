package com.abobo.usque

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import usqueandroid.PacketFlow
import usqueandroid.Usqueandroid
import usqueandroid.VpnStateCallback
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

class UsqueVpnService : VpnService() {

    companion object {
        private const val TAG = "UsqueVpnService"
        const val ACTION_DISCONNECT = "com.abobo.usque.DISCONNECT"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "usque_tunnel"
        const val PROXY_PORT = 58080
        var isRunning = false
            private set
        private var instance: UsqueVpnService? = null
        fun stop() { instance?.disconnect() }
        var totalRx = 0L
        var totalTx = 0L
        @Volatile var proxyReady = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private var speedUpdater: Runnable? = null
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSpeedTime = 0L
    private var notificationManager: NotificationManager? = null
    private var proxyThread: Thread? = null
    private val protectedSockets = mutableListOf<DatagramSocket>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Usque 隧道", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Usque MASQUE 隧道状态"
                    setShowBadge(false)
                })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) { disconnect(); return START_NOT_STICKY }
        if (isRunning) return START_STICKY

        val prefs = getSharedPreferences("UsqueVpnPrefs", MODE_PRIVATE)
        val configPath = "${filesDir.absolutePath}/config.json"

        if (!Usqueandroid.isRegistered(configPath)) {
            val deviceName = prefs.getString("device_name", "Pixel 10") ?: "Pixel 10"
            val error = Usqueandroid.register(configPath, deviceName)
            if (error.isNotEmpty()) {
                Log.e(TAG, "注册失败: $error"); stopSelf(); return START_NOT_STICKY
            }
            prefs.edit()
                .putString("endpoint_v4", Usqueandroid.getDefaultEndpoint(configPath))
                .putString("endpoint_v6", Usqueandroid.getAssignedIPv6(configPath))
                .putString("sni", Usqueandroid.getSNI())
                .putString("private_key", Usqueandroid.getPrivateKeyB64(configPath))
                .putString("endpoint_pubkey", Usqueandroid.getEndpointPubKeyPEM(configPath))
                .apply()
        }

        val vpnIpv4 = Usqueandroid.getAssignedIPv4(configPath)
        if (vpnIpv4.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val vpnIpv6 = Usqueandroid.getAssignedIPv6(configPath)

        // ★ 保护 endpoint IP 的 UDP socket，让库的控制流量绕过 VPN
        val ep4 = prefs.getString("endpoint_v4", 
            Usqueandroid.getDefaultEndpoint(configPath)) ?: ""
        protectEndpoint(ep4)

        try {
            val builder = Builder().setSession("Usque").setMtu(1280)
            builder.addAddress(vpnIpv4, 32)
            builder.addRoute("0.0.0.0", 0)
            if (vpnIpv6.isNotEmpty()) {
                try { builder.addAddress(vpnIpv6, 128); builder.addRoute("::", 0) } catch (_: Exception) {}
            }
            val dnsStr = prefs.getString("dns_servers", "8.8.8.8\n8.8.4.4\n9.9.9.9\n149.112.112.112") ?: "8.8.8.8\n8.8.4.4"
            dnsStr.lines().map { it.trim() }.filter { it.isNotBlank() }
                .forEach { try { builder.addDnsServer(it) } catch (_: Exception) {} }
            // ★ 不排除整个 App，endpoint 已经 protect 了
            vpnInterface = builder.establish() ?: run { stopSelf(); return START_NOT_STICKY }
            outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            isRunning = true; proxyReady = false
            totalRx = 0L; totalTx = 0L; lastRx = 0L; lastTx = 0L; lastSpeedTime = 0L

            val pf = object : PacketFlow {
                override fun writePacket(data: ByteArray?) {
                    if (data != null && data.isNotEmpty()) {
                        totalTx += data.size
                        try { outputStream?.write(data) } catch (_: Exception) {}
                    }
                }
            }
            val cb = object : VpnStateCallback {
                override fun onConnected() { Log.i(TAG, "隧道已连接") }
                override fun onDisconnected(r: String?) { disconnect() }
                override fun onError(m: String?) { Log.e(TAG, "错误: $m") }
            }
            if (Usqueandroid.startTunnel(configPath, vpnInterface!!.fd.toLong(), 1280, pf, cb).isNotEmpty()) {
                Log.e(TAG, "启动失败"); isRunning = false; vpnInterface?.close(); stopSelf(); return START_NOT_STICKY
            }
            startForeground(NOTIFICATION_ID, buildNotification("连接中…"))
            startSpeedUpdater()
            startHttpProxy()
        } catch (e: Exception) {
            Log.e(TAG, "VPN 接口失败", e); stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    // ★ 保护 endpoint IP 的所有端口组合
    private fun protectEndpoint(ip: String) {
        if (ip.isEmpty()) return
        for (offset in 0..2) {
            for (port in listOf(443, 8443)) {
                try {
                    val parts = ip.split(".")
                    if (parts.size == 4) {
                        val last = parts[3].toInt() + offset
                        val targetIp = "${parts[0]}.${parts[1]}.${parts[2]}.$last"
                        val ds = DatagramSocket()
                        protect(ds)
                        ds.connect(InetSocketAddress(targetIp, port))
                        protectedSockets.add(ds)
                        Log.d(TAG, "protected: $targetIp:$port")
                    }
                } catch (_: Exception) {}
                try {
                    // IPv6 同理：末尾 + offset
                    val ep6 = java.net.Inet6Address.getByName(ip)
                    if (ep6 is java.net.Inet6Address) {
                        val bytes = ep6.address.copyOf()
                        bytes[15] = (bytes[15].toInt() + offset).toByte()
                        val targetIp = java.net.Inet6Address.getByAddress(null, bytes, 0).hostAddress
                        val ds = DatagramSocket()
                        protect(ds)
                        ds.connect(InetSocketAddress(targetIp!!, port))
                        protectedSockets.add(ds)
                        Log.d(TAG, "protected v6: $targetIp:$port")
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ── HTTP 正向代理 (轻量，MainActivity 用它查 IP) ──
    private fun startHttpProxy() {
        proxyThread = thread(name = "usque-proxy") {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress("127.0.0.1", PROXY_PORT))
                proxyReady = true
                Log.i(TAG, "HTTP 代理已启动 127.0.0.1:$PROXY_PORT")
                while (isRunning) {
                    try {
                        val client = server.accept()
                        thread(name = "proxy-req") { handleProxyRequest(client) }
                    } catch (_: Exception) { if (!isRunning) break }
                }
                server.close()
            } catch (e: Exception) { Log.e(TAG, "代理启动失败: ${e.message}") }
            proxyReady = false
        }
    }

    private fun handleProxyRequest(client: Socket) {
        try {
            client.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val target = parts[1]

            var line: String?
            do { line = reader.readLine() } while (line != null && line.isNotEmpty())

            val url = URL(target)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "UsqueProxy/1.0")

            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else ""
            conn.disconnect()

            writer.write("HTTP/1.1 $code OK\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(body); writer.flush()
        } catch (_: Exception) {}
        finally { try { client.close() } catch (_: Exception) {} }
    }

    // ── 通知 / 网速 ──
    private fun flagEmoji(code: String): String {
        if (code.length != 2) return ""
        return try { String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (code[1] - 'A'))) }
        catch (_: Exception) { "" }
    }

    private fun buildNotification(status: String): Notification {
        val flag = flagEmoji(MainActivity.countryCode)
        val location = MainActivity.countryName
        val title = if (flag.isNotEmpty()) "$flag Usque" else "Usque"
        val content = if (location.isNotEmpty()) "$location · $status" else status
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val di = PendingIntent.getService(this, 1,
            Intent(this, UsqueVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_share).setContentIntent(pi)
            .setOngoing(true).addAction(android.R.drawable.ic_media_pause, "断开", di)
            .setPriority(Notification.PRIORITY_LOW).setShowWhen(false).build()
    }

    private fun startSpeedUpdater() {
        stopSpeedUpdater()
        speedUpdater = object : Runnable {
            override fun run() {
                if (isRunning) {
                    val rx = totalRx; val tx = totalTx; val now = System.currentTimeMillis()
                    var status = "已连接"
                    if (lastSpeedTime > 0) {
                        val e = (now - lastSpeedTime) / 1000.0
                        if (e > 0) status = "↓ ${fmt(((rx - lastRx) / e).toLong())}/s  ↑ ${fmt(((tx - lastTx) / e).toLong())}/s"
                    }
                    lastRx = rx; lastTx = tx; lastSpeedTime = now
                    notificationManager?.notify(NOTIFICATION_ID, buildNotification(status))
                }
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(speedUpdater!!, 2000)
    }

    private fun stopSpeedUpdater() { speedUpdater?.let { handler.removeCallbacks(it) }; speedUpdater = null }

    private fun fmt(b: Long) = when {
        b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
        b >= 1_000 -> "%.1f KB".format(b / 1_000.0)
        b >= 0 -> "$b B" else -> "0 B"
    }

    fun disconnect() {
        if (!isRunning) return
        isRunning = false; proxyReady = false
        protectedSockets.forEach { try { it.close() } catch (_: Exception) {} }
        protectedSockets.clear()
        stopSpeedUpdater()
        try { Usqueandroid.stopTunnel() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        totalRx = 0L; totalTx = 0L
    }

    override fun onDestroy() { if (isRunning) disconnect(); instance = null; super.onDestroy() }
    override fun onRevoke() { disconnect() }
}

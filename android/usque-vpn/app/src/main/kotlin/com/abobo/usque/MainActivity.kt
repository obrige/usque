package com.abobo.usque

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import org.json.JSONObject
import usqueandroid.Usqueandroid
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        private const val PREFS_NAME = "UsqueVpnPrefs"
        private const val KEY_SNI = "sni"
        private const val KEY_ENDPOINT_V4 = "endpoint_v4"
        private const val KEY_ENDPOINT_V6 = "endpoint_v6"
        private const val KEY_DNS = "dns_servers"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_ENDPOINT_PUBKEY = "endpoint_pubkey"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PRESETS = "presets_json"
        private const val KEY_JWT = "jwt"
        private const val KEY_LICENSE = "license"
        private const val KEY_TOKEN = "token"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_MODEL = "model"
        private const val KEY_LOCALE = "locale"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_CLIENT_VERSION = "client_version"
        private const val KEY_ZERO_TIER = "zero_tier"
        private const val DEFAULT_DNS = "8.8.8.8\n8.8.4.4\n9.9.9.9\n149.112.112.112"
        private const val DEFAULT_DEVICE = "Pixel 10"
        private const val DEFAULT_SNI = "cdnjs.cloudflare.com"
        private const val DEFAULT_MODEL = "PC"
        private const val DEFAULT_LOCALE = "en_US"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var exitIpText: TextView
    private lateinit var latencyText: TextView
    private lateinit var countryText: TextView
    private lateinit var countryLabel: TextView
    private lateinit var ipVersionText: TextView
    private lateinit var infoRow: LinearLayout
    private lateinit var pulseRing: View
    private var pulseAnimator: ValueAnimator? = null
    private val presets = mutableListOf<PresetConfig>()
    private var currentPresetName: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var speedUpdater: Runnable? = null
    private var latencyUpdater: Runnable? = null
    private var connectTime = 0L
    private var durationUpdater: Runnable? = null
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSpeedTime = 0L

    data class PresetConfig(
        val name: String, val sni: String,
        val endpointV4: String, val endpointV6: String,
        val dnsServers: String, val privateKey: String,
        val endpointPubKey: String, val deviceName: String,
        val jwt: String, val license: String,
        val token: String, val accountId: String,
        val model: String, val locale: String,
        val userAgent: String, val clientVersion: String,
        val zeroTier: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        connectButton = findViewById(R.id.connect_button)
        statusText = findViewById(R.id.status_text)
        speedText = findViewById(R.id.speed_text)
        exitIpText = findViewById(R.id.exit_ip_text)
        latencyText = findViewById(R.id.latency_text)
        countryText = findViewById(R.id.country_text)
        countryLabel = findViewById(R.id.country_label)
        ipVersionText = findViewById(R.id.ip_version_text)
        infoRow = findViewById(R.id.info_row)
        pulseRing = findViewById(R.id.pulse_ring)
        findViewById<ImageButton>(R.id.settings_icon)
            .setOnClickListener { showSettingsDialog() }
        findViewById<ImageButton>(R.id.log_button)
            .setOnClickListener { showLogDialog() }
        loadPresets()
        loadSavedSettings()
        connectButton.setOnClickListener {
            if (UsqueVpnService.isRunning) stopVpn() else startVpn()
        }
        startPulseAnimation()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        fetchIpLocation()
    }

    override fun onPause() {
        super.onPause()
        stopSpeedUpdater()
        stopLatencyUpdater()
        stopDurationUpdater()
    }

    private fun configPath() = "${filesDir.absolutePath}/config.json"

    private fun getStr(k: String, f: String): String {
        return prefs.getString(k, null)?.takeIf { it.isNotEmpty() } ?: f
    }

    private fun getBool(k: String, d: Boolean): Boolean {
        return prefs.getBoolean(k, d)
    }

    private fun getRegStr(f: String, g: (String) -> String): String {
        return g(configPath()).ifEmpty { f }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.25f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val s = it.animatedValue as Float
                pulseRing.scaleX = s
                pulseRing.scaleY = s
                pulseRing.alpha = 2f - s
            }
            start()
        }
    }

    private fun flagEmoji(code: String): String {
        if (code.length != 2) return String(Character.toChars(0x1F310))
        return try {
            val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
            val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
            String(first) + String(second)
        } catch (_: Exception) {
            String(Character.toChars(0x1F310))
        }
    }

    private fun fetchIpLocation() {
        thread {
            try {
                val c = (URL("https://ors.de5.net/ip").openConnection()
                    as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    connect()
                }
                if (c.responseCode == 200) {
                    val j = JSONObject(c.inputStream.bufferedReader().readText())
                    val co = j.optString("country", "")
                    val ci = j.optString("city", "")
                    runOnUiThread {
                        countryText.text = flagEmoji(co)
                        countryLabel.text = if (ci.isNotEmpty()) ci else co
                    }
                }
                c.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun measureLatency() {
        thread {
            try {
                val s = Socket()
                val st = System.currentTimeMillis()
                s.connect(InetSocketAddress("1.1.1.1", 443), 3000)
                val lat = System.currentTimeMillis() - st
                s.close()
                runOnUiThread { latencyText.text = "${lat}ms" }
            } catch (_: Exception) {
                runOnUiThread { latencyText.text = "-- ms" }
            }
        }
    }

    private fun startLatencyUpdater() {
        stopLatencyUpdater()
        latencyUpdater = object : Runnable {
            override fun run() {
                if (UsqueVpnService.isRunning) measureLatency()
                handler.postDelayed(this, 5000)
            }
        }
        handler.post(latencyUpdater!!)
    }

    private fun stopLatencyUpdater() {
        latencyUpdater?.let { handler.removeCallbacks(it) }
        latencyUpdater = null
    }

    private fun startDurationUpdater() {
        stopDurationUpdater()
        connectTime = System.currentTimeMillis()
        durationUpdater = object : Runnable {
            override fun run() {
                val e = (System.currentTimeMillis() - connectTime) / 1000
                val h = e / 3600
                val m = (e % 3600) / 60
                val s = e % 60
                statusText.text = if (h > 0)
                    "Connected \u00b7 %d:%02d:%02d".format(h, m, s)
                else
                    "%02d:%02d".format(m, s)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(durationUpdater!!)
    }

    private fun stopDurationUpdater() {
        durationUpdater?.let { handler.removeCallbacks(it) }
        durationUpdater = null
    }

    private fun startSpeedUpdater() {
        stopSpeedUpdater()
        speedUpdater = object : Runnable {
            override fun run() {
                if (UsqueVpnService.isRunning) {
                    val rx = UsqueVpnService.totalRx
                    val tx = UsqueVpnService.totalTx
                    val n = System.currentTimeMillis()
                    if (lastSpeedTime > 0) {
                        val e = (n - lastSpeedTime) / 1000.0
                        if (e > 0) {
                            val dr = ((rx - lastRx) / e).toLong()
                            val dt = ((tx - lastTx) / e).toLong()
                            speedText.text = "D " + fmt(dr) + "/s  U " + fmt(dt) + "/s"
                        }
                    }
                    lastRx = rx
                    lastTx = tx
                    lastSpeedTime = n
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(speedUpdater!!)
    }

    private fun stopSpeedUpdater() {
        speedUpdater?.let { handler.removeCallbacks(it) }
        speedUpdater = null
    }

    private fun fmt(b: Long): String = when {
        b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
        b >= 1_000 -> "%.1f KB".format(b / 1_000.0)
        b >= 0 -> "$b B"
        else -> "0 B"
    }

    private fun showLogDialog() {
        val info = if (Usqueandroid.isRegistered(configPath()))
            Usqueandroid.getRegisterInfo(configPath()) else "(not registered)"
        val lv = TextView(this).apply {
            text = info
            textSize = 10f
            setTextColor(Color.parseColor("#475569"))
            setPadding(20, 20, 20, 20)
            movementMethod = ScrollingMovementMethod()
            setLineSpacing(4f, 1f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        AlertDialog.Builder(this)
            .setTitle("Log / Info")
            .setView(lv)
            .setPositiveButton("DNS Test") { _, _ -> runDnsTest() }
            .setNegativeButton("Close", null)
            .setNeutralButton("Export") { _, _ -> showExportDialog() }
            .show()
    }

    private fun runDnsTest() {
        thread {
            val sb = StringBuilder().apply {
                appendLine("=== DNS Test ===")
                appendLine(
                    "DNS: " + (getStr(KEY_DNS, DEFAULT_DNS)
                        ?: DEFAULT_DNS).replace("\n", ", ")
                )
                listOf("cloudflare.com", "google.com", "github.com").forEach { d ->
                    try {
                        val ip = java.net.InetAddress.getByName(d).hostAddress
                        appendLine("[OK] $d -> $ip")
                    } catch (e: Exception) {
                        appendLine("[FAIL] $d -> ${e.message}")
                    }
                }
                appendLine("===============")
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("DNS Test Result")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun loadPresets() {
        presets.clear()
        val j = prefs.getString(KEY_PRESETS, null) ?: return
        try {
            val a = org.json.JSONArray(j)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                presets.add(
                    PresetConfig(
                        o.getString("name"),
                        o.optString("sni", DEFAULT_SNI),
                        o.optString("endpointV4", ""),
                        o.optString("endpointV6", ""),
                        o.optString("dnsServers", DEFAULT_DNS),
                        o.optString("privateKey", ""),
                        o.optString("endpointPubKey", ""),
                        o.optString("deviceName", DEFAULT_DEVICE),
                        o.optString("jwt", ""),
                        o.optString("license", ""),
                        o.optString("token", ""),
                        o.optString("accountId", ""),
                        o.optString("model", DEFAULT_MODEL),
                        o.optString("locale", DEFAULT_LOCALE),
                        o.optString("userAgent", ""),
                        o.optString("clientVersion", ""),
                        o.optBoolean("zeroTier", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun savePresets() {
        val a = org.json.JSONArray()
        presets.forEach { p ->
            a.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("sni", p.sni)
                    put("endpointV4", p.endpointV4)
                    put("endpointV6", p.endpointV6)
                    put("dnsServers", p.dnsServers)
                    put("privateKey", p.privateKey)
                    put("endpointPubKey", p.endpointPubKey)
                    put("deviceName", p.deviceName)
                    put("jwt", p.jwt)
                    put("license", p.license)
                    put("token", p.token)
                    put("accountId", p.accountId)
                    put("model", p.model)
                    put("locale", p.locale)
                    put("userAgent", p.userAgent)
                    put("clientVersion", p.clientVersion)
                    put("zeroTier", p.zeroTier)
                }
            )
        }
        prefs.edit().putString(KEY_PRESETS, a.toString()).apply()
    }

    private fun showPresetDialog() {
        if (presets.isEmpty()) {
            Toast.makeText(this, "No presets", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Presets")
            .setItems(presets.map { it.name }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this)
                    .setTitle("Action: ${presets[i].name}")
                    .setItems(arrayOf("Apply", "Edit", "Delete")) { _, op ->
                        when (op) {
                            0 -> {
                                applyPreset(presets[i])
                                Toast.makeText(this, "Applied", Toast.LENGTH_SHORT).show()
                            }
                            1 -> showEditPresetDialog(i)
                            2 -> {
                                presets.removeAt(i)
                                savePresets()
                                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
            .show()
    }

    private fun showEditPresetDialog(idx: Int) {
        val p = presets[idx]
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        v.findViewById<EditText>(R.id.sni_input).setText(p.sni)
        v.findViewById<EditText>(R.id.endpoint_v4_input).setText(p.endpointV4)
        v.findViewById<EditText>(R.id.endpoint_v6_input).setText(p.endpointV6)
        v.findViewById<EditText>(R.id.dns_input).setText(p.dnsServers)
        v.findViewById<EditText>(R.id.private_key_input).setText(p.privateKey)
        v.findViewById<EditText>(R.id.endpoint_pubkey_input).setText(p.endpointPubKey)
        v.findViewById<EditText>(R.id.device_name_input).setText(p.deviceName)
        v.findViewById<EditText>(R.id.jwt_input).setText(p.jwt)
        v.findViewById<EditText>(R.id.license_input).setText(p.license)
        v.findViewById<EditText>(R.id.token_input).setText(p.token)
        v.findViewById<EditText>(R.id.account_id_input).setText(p.accountId)
        v.findViewById<EditText>(R.id.model_input).setText(p.model)
        v.findViewById<EditText>(R.id.locale_input).setText(p.locale)
        v.findViewById<EditText>(R.id.user_agent_input).setText(p.userAgent)
        v.findViewById<EditText>(R.id.client_version_input).setText(p.clientVersion)
        (v.findViewById<Switch>(R.id.zero_tier_switch)).isChecked = p.zeroTier
        listOf(
            R.id.registered_device_id,
            R.id.registered_license,
            R.id.registered_pubkey
        ).forEach {
            v.findViewById<TextView>(it)?.visibility = View.GONE
        }
        AlertDialog.Builder(this)
            .setTitle("Edit: ${p.name}")
            .setView(v)
            .setPositiveButton("Save") { _, _ ->
                presets[idx] = PresetConfig(
                    p.name,
                    v.findViewById<EditText>(R.id.sni_input).text.toString(),
                    v.findViewById<EditText>(R.id.endpoint_v4_input).text.toString(),
                    v.findViewById<EditText>(R.id.endpoint_v6_input).text.toString(),
                    v.findViewById<EditText>(R.id.dns_input).text.toString(),
                    v.findViewById<EditText>(R.id.private_key_input).text.toString().trim(),
                    v.findViewById<EditText>(R.id.endpoint_pubkey_input).text.toString().trim(),
                    v.findViewById<EditText>(R.id.device_name_input).text.toString(),
                    v.findViewById<EditText>(R.id.jwt_input).text.toString().trim(),
                    v.findViewById<EditText>(R.id.license_input).text.toString().trim(),
                    v.findViewById<EditText>(R.id.token_input).text.toString().trim(),
                    v.findViewById<EditText>(R.id.account_id_input).text.toString().trim(),
                    v.findViewById<EditText>(R.id.model_input).text.toString(),
                    v.findViewById<EditText>(R.id.locale_input).text.toString(),
                    v.findViewById<EditText>(R.id.user_agent_input).text.toString(),
                    v.findViewById<EditText>(R.id.client_version_input).text.toString(),
                    (v.findViewById<Switch>(R.id.zero_tier_switch)).isChecked
                )
                savePresets()
                Toast.makeText(this, "Updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPreset(p: PresetConfig) {
        currentPresetName = p.name
        saveSettings(
            p.sni, p.endpointV4, p.endpointV6, p.dnsServers,
            p.privateKey, p.endpointPubKey, p.deviceName,
            p.jwt, p.license, p.token, p.accountId,
            p.model, p.locale, p.userAgent, p.clientVersion, p.zeroTier
        )
        loadSavedSettings()
        updateUI()
    }

    private fun loadSavedSettings() {
        Usqueandroid.setSNI(getStr(KEY_SNI, DEFAULT_SNI))
        getStr(KEY_ENDPOINT_V4, "").let {
            if (it.isNotEmpty()) Usqueandroid.setEndpointV4(it)
        }
        getStr(KEY_ENDPOINT_V6, "").let {
            if (it.isNotEmpty()) Usqueandroid.setEndpointV6(it)
        }
        getStr(KEY_PRIVATE_KEY, "").let {
            if (it.isNotEmpty()) Usqueandroid.setPrivateKey(it)
        }
        getStr(KEY_ENDPOINT_PUBKEY, "").let {
            if (it.isNotEmpty()) Usqueandroid.setEndpointPublicKey(it)
        }
        getStr(KEY_JWT, "").let {
            if (it.isNotEmpty()) Usqueandroid.setJWT(it)
        }
        getStr(KEY_LICENSE, "").let {
            if (it.isNotEmpty()) Usqueandroid.setLicense(it)
        }
        getStr(KEY_TOKEN, "").let {
            if (it.isNotEmpty()) Usqueandroid.setToken(it)
        }
        getStr(KEY_ACCOUNT_ID, "").let {
            if (it.isNotEmpty()) Usqueandroid.setAccountID(it)
        }
        // New fields
        val m = getStr(KEY_MODEL, DEFAULT_MODEL)
        Usqueandroid.setModel(m)
        val l = getStr(KEY_LOCALE, DEFAULT_LOCALE)
        Usqueandroid.setLocale(l)
        val ua = getStr(KEY_USER_AGENT, "")
        if (ua.isNotEmpty()) Usqueandroid.setUserAgent(ua)
        val cv = getStr(KEY_CLIENT_VERSION, "")
        if (cv.isNotEmpty()) Usqueandroid.setClientVersion(cv)
        val zt = getBool(KEY_ZERO_TIER, false)
        Usqueandroid.setUseZeroTier(zt)
    }

    private fun saveSettings(
        sni: String, ep4: String, ep6: String, dns: String,
        pk: String, pub: String, dev: String,
        jwt: String, lic: String, tok: String, aid: String,
        model: String = DEFAULT_MODEL,
        locale: String = DEFAULT_LOCALE,
        userAgent: String = "",
        clientVersion: String = "",
        zeroTier: Boolean = false
    ) {
        prefs.edit()
            .putString(KEY_SNI, sni)
            .putString(KEY_ENDPOINT_V4, ep4)
            .putString(KEY_ENDPOINT_V6, ep6)
            .putString(KEY_DNS, dns)
            .putString(KEY_PRIVATE_KEY, pk)
            .putString(KEY_ENDPOINT_PUBKEY, pub)
            .putString(KEY_DEVICE_NAME, dev)
            .putString(KEY_JWT, jwt)
            .putString(KEY_LICENSE, lic)
            .putString(KEY_TOKEN, tok)
            .putString(KEY_ACCOUNT_ID, aid)
            .putString(KEY_MODEL, model)
            .putString(KEY_LOCALE, locale)
            .putString(KEY_USER_AGENT, userAgent)
            .putString(KEY_CLIENT_VERSION, clientVersion)
            .putBoolean(KEY_ZERO_TIER, zeroTier)
            .apply()
    }

    private fun showSettingsDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val sni = v.findViewById<EditText>(R.id.sni_input)
        val ep4 = v.findViewById<EditText>(R.id.endpoint_v4_input)
        val ep6 = v.findViewById<EditText>(R.id.endpoint_v6_input)
        val dns = v.findViewById<EditText>(R.id.dns_input)
        val pk = v.findViewById<EditText>(R.id.private_key_input)
        val pub = v.findViewById<EditText>(R.id.endpoint_pubkey_input)
        val dev = v.findViewById<EditText>(R.id.device_name_input)
        val jwt = v.findViewById<EditText>(R.id.jwt_input)
        val lic = v.findViewById<EditText>(R.id.license_input)
        val tok = v.findViewById<EditText>(R.id.token_input)
        val aid = v.findViewById<EditText>(R.id.account_id_input)
        val model = v.findViewById<EditText>(R.id.model_input)
        val locale = v.findViewById<EditText>(R.id.locale_input)
        val ua = v.findViewById<EditText>(R.id.user_agent_input)
        val cv = v.findViewById<EditText>(R.id.client_version_input)
        val zt = v.findViewById<Switch>(R.id.zero_tier_switch)
        val rid = v.findViewById<TextView>(R.id.registered_device_id)
        val rlic = v.findViewById<TextView>(R.id.registered_license)
        val rpk = v.findViewById<TextView>(R.id.registered_pubkey)
        val cp = configPath()
        sni.setText(getStr(KEY_SNI, Usqueandroid.getSNI()))
        ep4.setText(getStr(KEY_ENDPOINT_V4, Usqueandroid.getEndpointV4()))
        ep6.setText(getStr(KEY_ENDPOINT_V6, Usqueandroid.getEndpointV6()))
        dns.setText(getStr(KEY_DNS, DEFAULT_DNS))
        pk.setText(getStr(KEY_PRIVATE_KEY,
            getRegStr("") { Usqueandroid.getPrivateKeyB64(it) }))
        pub.setText(getStr(KEY_ENDPOINT_PUBKEY,
            getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }))
        dev.setText(getStr(KEY_DEVICE_NAME, DEFAULT_DEVICE))
        jwt.setText(getStr(KEY_JWT, ""))
        lic.setText(getStr(KEY_LICENSE, ""))
        tok.setText(getStr(KEY_TOKEN, ""))
        aid.setText(getStr(KEY_ACCOUNT_ID, ""))
        model.setText(getStr(KEY_MODEL, Usqueandroid.getModel()))
        locale.setText(getStr(KEY_LOCALE, Usqueandroid.getLocale()))
        ua.setText(getStr(KEY_USER_AGENT, Usqueandroid.getUserAgent()))
        cv.setText(getStr(KEY_CLIENT_VERSION, Usqueandroid.getClientVersion()))
        zt.isChecked = getBool(KEY_ZERO_TIER, Usqueandroid.getUseZeroTier())
        if (Usqueandroid.isRegistered(cp)) {
            rid.visibility = View.VISIBLE
            rlic.visibility = View.VISIBLE
            rpk.visibility = View.VISIBLE
            rid.text = "DeviceID: " +
                getRegStr("(N/A)") { Usqueandroid.getDeviceID(it) }
            rlic.text = "License: " +
                getRegStr("") { Usqueandroid.getLicense(it) }
            rpk.text = "PubKey: " +
                getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }
        }

        // Re-Enroll button
        v.findViewById<Button>(R.id.reenroll_button).setOnClickListener {
            if (!Usqueandroid.isRegistered(cp)) {
                Toast.makeText(this, "Register first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dn = dev.text.toString().ifEmpty { DEFAULT_DEVICE }
            thread {
                val err = Usqueandroid.reEnroll(cp, dn, false)
                runOnUiThread {
                    if (err.isEmpty()) {
                        Toast.makeText(this, "Re-Enrolled OK", Toast.LENGTH_SHORT).show()
                        // Refresh displayed values
                        pk.setText(getRegStr("") { Usqueandroid.getPrivateKeyB64(it) })
                        pub.setText(getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) })
                        rpk.text = "PubKey: " +
                            getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }
                    } else {
                        Toast.makeText(this, "Fail: $err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Regen + Enroll button
        v.findViewById<Button>(R.id.regen_key_button).setOnClickListener {
            if (!Usqueandroid.isRegistered(cp)) {
                Toast.makeText(this, "Register first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dn = dev.text.toString().ifEmpty { DEFAULT_DEVICE }
            thread {
                val err = Usqueandroid.reEnroll(cp, dn, true)
                runOnUiThread {
                    if (err.isEmpty()) {
                        Toast.makeText(this, "Regen + Enrolled OK", Toast.LENGTH_SHORT).show()
                        pk.setText(getRegStr("") { Usqueandroid.getPrivateKeyB64(it) })
                        pub.setText(getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) })
                        rpk.text = "PubKey: " +
                            getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }
                    } else {
                        Toast.makeText(this, "Fail: $err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(v)
            .setPositiveButton("Save") { _, _ ->
                saveSettings(
                    sni.text.toString(), ep4.text.toString(), ep6.text.toString(),
                    dns.text.toString(),
                    pk.text.toString().trim(), pub.text.toString().trim(),
                    dev.text.toString(),
                    jwt.text.toString().trim(), lic.text.toString().trim(),
                    tok.text.toString().trim(), aid.text.toString().trim(),
                    model.text.toString(), locale.text.toString(),
                    ua.text.toString(), cv.text.toString(),
                    zt.isChecked
                )
                loadSavedSettings()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                updateUI()
                AlertDialog.Builder(this)
                    .setTitle("Save as preset?")
                    .setMessage("Save current settings as a preset?")
                    .setPositiveButton("Save") { _, _ ->
                        showSavePresetDialog(
                            sni.text.toString(), ep4.text.toString(), ep6.text.toString(),
                            dns.text.toString(),
                            pk.text.toString().trim(), pub.text.toString().trim(),
                            dev.text.toString(),
                            jwt.text.toString().trim(), lic.text.toString().trim(),
                            tok.text.toString().trim(), aid.text.toString().trim(),
                            model.text.toString(), locale.text.toString(),
                            ua.text.toString(), cv.text.toString(),
                            zt.isChecked
                        )
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Export") { _, _ -> showExportDialog() }
            .show()
    }

    private fun showSavePresetDialog(
        sni: String, ep4: String, ep6: String, dns: String,
        pk: String, pub: String, dev: String,
        jwt: String, lic: String, tok: String, aid: String,
        model: String, locale: String,
        userAgent: String, clientVersion: String,
        zeroTier: Boolean
    ) {
        val input = EditText(this)
        input.hint = "Preset name (e.g. MyNode)"
        AlertDialog.Builder(this)
            .setTitle("Save Preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                presets.removeAll { it.name == name }
                presets.add(
                    PresetConfig(
                        name, sni, ep4, ep6, dns, pk, pub, dev,
                        jwt, lic, tok, aid,
                        model, locale, userAgent, clientVersion, zeroTier
                    )
                )
                savePresets()
                Toast.makeText(this, "Preset '$name' saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExportDialog() {
        val cp = configPath()
        val sb = StringBuilder().apply {
            appendLine("=== Usque Config Export ===")
            appendLine("SNI: " + getStr(KEY_SNI, Usqueandroid.getSNI()))
            appendLine(
                "IPv4: " + getStr(
                    KEY_ENDPOINT_V4,
                    getRegStr("") { Usqueandroid.getDefaultEndpoint(it) })
            )
            appendLine(
                "IPv6: " + getStr(
                    KEY_ENDPOINT_V6,
                    getRegStr("") { Usqueandroid.getAssignedIPv6(it) })
            )
            appendLine(
                "DNS: " + (getStr(KEY_DNS, DEFAULT_DNS)
                    ?: DEFAULT_DNS).replace("\n", ", ")
            )
            getRegStr("") { Usqueandroid.getPrivateKeyB64(it) }.let {
                if (it.isNotEmpty()) appendLine("PrivateKey: $it")
            }
            getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }.let {
                if (it.isNotEmpty()) appendLine("PubKey: $it")
            }
            getRegStr("") { Usqueandroid.getDeviceID(it) }.let {
                if (it.isNotEmpty()) appendLine("DeviceID: $it")
            }
            getRegStr("") { Usqueandroid.getLicense(it) }.let {
                if (it.isNotEmpty()) appendLine("License: $it")
            }
            appendLine("Model: " + getStr(KEY_MODEL, DEFAULT_MODEL))
            appendLine("Locale: " + getStr(KEY_LOCALE, DEFAULT_LOCALE))
            appendLine("ZeroTier: " + getBool(KEY_ZERO_TIER, false))
            appendLine("JWT: " + getStr(KEY_JWT, "(none)"))
            appendLine("============================")
        }
        val lv = TextView(this).apply {
            text = sb.toString()
            textSize = 11f
            setTextColor(Color.parseColor("#0f172a"))
            setPadding(24, 24, 24, 24)
            movementMethod = ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(4f, 1f)
        }
        AlertDialog.Builder(this)
            .setTitle("Export Config")
            .setView(lv)
            .setPositiveButton("Copy") { _, _ ->
                val cm =
                    getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "usque_config", sb.toString()
                    )
                )
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Share") { _, _ ->
                val si = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, sb.toString())
                }
                startActivity(Intent.createChooser(si, "Share Config"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE)
        else onVpnPermissionGranted()
    }

    private fun stopVpn() {
        UsqueVpnService.stop()
        val i = Intent(this, UsqueVpnService::class.java)
        i.action = UsqueVpnService.ACTION_DISCONNECT
        startService(i)
        connectButton.postDelayed({ updateUI() }, 1000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) onVpnPermissionGranted()
            else Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onVpnPermissionGranted() {
        startService(Intent(this, UsqueVpnService::class.java))
        connectButton.postDelayed({ updateUI() }, 1500)
    }

    private fun updateUI() {
        if (UsqueVpnService.isRunning) {
            connectButton.text = "Disconnect"
            startSpeedUpdater()
            startLatencyUpdater()
            startDurationUpdater()
            fetchIpLocation()
        } else {
            connectButton.text = "Connect"
            statusText.text = "Disconnected"
            speedText.text = "D --  U --"
            latencyText.text = "-- ms"
            countryText.text = String(Character.toChars(0x1F310))
            countryLabel.text = "--"
            exitIpText.text = "Exit: ---"
            stopSpeedUpdater()
            stopLatencyUpdater()
            stopDurationUpdater()
            lastRx = 0
            lastTx = 0
            lastSpeedTime = 0
        }
    }
}

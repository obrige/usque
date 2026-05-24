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
import org.json.JSONArray
import org.json.JSONObject
import usqueandroid.Usqueandroid
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
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
        private const val DEFAULT_MODEL = "Android"
        private const val DEFAULT_LOCALE = "en_US"

        val ENDPOINT_V4_PRESETS = arrayOf(
            "162.159.198.2:443", "162.159.198.1:443", "162.159.198.0:443",
            "162.159.198.2:8443", "162.159.198.1:8443", "162.159.198.0:8443"
        )
        val ENDPOINT_V6_PRESETS = arrayOf(
            "[2606:4700:103::2]:443", "[2606:4700:103::2]:8443",
            "[2606:4700:103::1]:443", "[2606:4700:103::1]:8443"
        )
        val ENDPOINT_V4_ALL = arrayOf("(custom)") + ENDPOINT_V4_PRESETS
        val ENDPOINT_V6_ALL = arrayOf("(custom)") + ENDPOINT_V6_PRESETS
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var latencyText: TextView
    private lateinit var infoRow: LinearLayout
    private lateinit var speedRow: LinearLayout
    private lateinit var speedDownText: TextView
    private lateinit var speedUpText: TextView
    private lateinit var totalDataText: TextView
    private lateinit var currentPresetText: TextView
    private lateinit var registeredInfoText: TextView
    private lateinit var pulseRing: View
    private lateinit var pulseRingOuter: View
    private var pulseAnimator: ValueAnimator? = null
    private val presets = mutableListOf<PresetConfig>()
    private var currentPresetName: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var speedUpdater: Runnable? = null
    private var latencyUpdater: Runnable? = null
    private var durationUpdater: Runnable? = null
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSpeedTime = 0L
    private var totalRxBase = 0L
    private var totalTxBase = 0L

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
        latencyText = findViewById(R.id.latency_text)
        infoRow = findViewById(R.id.info_row)
        speedRow = findViewById(R.id.speed_row)
        speedDownText = findViewById(R.id.speed_down_text)
        speedUpText = findViewById(R.id.speed_up_text)
        totalDataText = findViewById(R.id.total_data_text)
        currentPresetText = findViewById(R.id.current_preset_text)
        registeredInfoText = findViewById(R.id.registered_info_text)
        pulseRing = findViewById(R.id.pulse_ring)
        pulseRingOuter = findViewById(R.id.pulse_ring_outer)

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
        updateRegisteredInfo()
    }

    override fun onPause() {
        super.onPause()
        stopSpeedUpdater()
        stopLatencyUpdater()
        stopDurationUpdater()
    }

    private fun configPath() = "${filesDir.absolutePath}/config.json"

    private fun getStr(k: String, f: String): String =
        prefs.getString(k, null)?.takeIf { it.isNotEmpty() } ?: f

    private fun getBool(k: String, d: Boolean): Boolean = prefs.getBoolean(k, d)

    private fun getRegStr(f: String, g: (String) -> String): String =
        g(configPath()).ifEmpty { f }

    private fun startPulseAnimation() {
        val rings = listOf(pulseRing, pulseRingOuter)
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.25f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val s = it.animatedValue as Float
                rings.forEach { ring ->
                    ring.scaleX = s; ring.scaleY = s; ring.alpha = 2f - s
                }
            }
            start()
        }
    }

    private fun updateRegisteredInfo() {
        val cp = configPath()
        if (Usqueandroid.isRegistered(cp)) {
            val v4 = getRegStr("") { Usqueandroid.getAssignedIPv4(it) }
            val v6 = getRegStr("") { Usqueandroid.getAssignedIPv6(it) }
            val sb = StringBuilder()
            if (v4.isNotEmpty()) sb.append("IPv4: $v4")
            if (v6.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("IPv6: $v6")
            }
            registeredInfoText.text = sb.toString().ifEmpty { "" }
        } else {
            registeredInfoText.text = ""
        }
    }

    private fun measureLatency() {
        thread {
            try {
                val st: Long
                val s: Socket
                if (UsqueVpnService.proxyReady) {
                    val p = Proxy(Proxy.Type.HTTP,
                        InetSocketAddress("127.0.0.1", UsqueVpnService.PROXY_PORT))
                    s = Socket(p)
                    st = System.currentTimeMillis()
                    s.connect(InetSocketAddress("8.8.8.8", 53), 8000)
                } else {
                    s = Socket()
                    st = System.currentTimeMillis()
                    s.connect(InetSocketAddress("8.8.8.8", 53), 8000)
                }
                val lat = System.currentTimeMillis() - st
                s.close()
                runOnUiThread { latencyText.text = "${lat} ms" }
            } catch (_: Exception) {
                runOnUiThread { latencyText.text = "超时" }
            }
        }
    }

    private fun startLatencyUpdater() {
        stopLatencyUpdater()
        latencyUpdater = object : Runnable {
            override fun run() {
                if (UsqueVpnService.isRunning) measureLatency()
                handler.postDelayed(this, 6000)
            }
        }
        handler.postDelayed(latencyUpdater!!, 4000)
    }

    private fun stopLatencyUpdater() {
        latencyUpdater?.let { handler.removeCallbacks(it) }
        latencyUpdater = null
    }

    private fun startDurationUpdater() {
        stopDurationUpdater()
        durationUpdater = object : Runnable {
            override fun run() {
                if (UsqueVpnService.isRunning && UsqueVpnService.connectStartTime > 0) {
                    val e = (System.currentTimeMillis() - UsqueVpnService.connectStartTime) / 1000
                    val h = e / 3600; val m = (e % 3600) / 60; val s = e % 60
                    statusText.text = if (h > 0)
                        "已连接 · %d:%02d:%02d".format(h, m, s)
                    else "%02d:%02d".format(m, s)
                }
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
        totalRxBase = UsqueVpnService.totalRx
        totalTxBase = UsqueVpnService.totalTx
        lastRx = totalRxBase; lastTx = totalTxBase
        speedUpdater = object : Runnable {
            override fun run() {
                if (UsqueVpnService.isRunning) {
                    val rx = UsqueVpnService.totalRx
                    val tx = UsqueVpnService.totalTx
                    val n = System.currentTimeMillis()
                    if (lastSpeedTime > 0) {
                        val e = (n - lastSpeedTime) / 1000.0
                        if (e > 0) {
                            speedDownText.text = fmt(((rx - lastRx) / e).toLong()) + "/s"
                            speedUpText.text = fmt(((tx - lastTx) / e).toLong()) + "/s"
                        }
                    }
                    lastRx = rx; lastTx = tx; lastSpeedTime = n
                    totalDataText.text = "↓" + fmtCompact(rx - totalRxBase) +
                        "\n↑" + fmtCompact(tx - totalTxBase)
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

    private fun fmt(b: Long) = when {
        b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
        b >= 1_000 -> "%.1f KB".format(b / 1_000.0)
        b >= 0 -> "$b B"
        else -> "0 B"
    }

    private fun fmtCompact(b: Long) = when {
        b >= 1_000_000 -> "%.1fM".format(b / 1_000_000.0)
        b >= 1_000 -> "%.1fK".format(b / 1_000.0)
        b >= 0 -> "${b}B"
        else -> "0"
    }

    private fun showLogDialog() {
        val info = if (Usqueandroid.isRegistered(configPath()))
            Usqueandroid.getRegisterInfo(configPath()) else "(not registered)"
        val lv = TextView(this).apply {
            text = info; textSize = 10f
            setTextColor(Color.parseColor("#8892b0"))
            setPadding(20, 20, 20, 20)
            movementMethod = ScrollingMovementMethod()
            setLineSpacing(4f, 1f)
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#161b30"))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Log / Info").setView(lv)
            .setPositiveButton("DNS Test") { _, _ -> runDnsTest() }
            .setNegativeButton("Close", null)
            .setNeutralButton("Export") { _, _ -> showExportDialog() }
            .show()
    }

    private fun runDnsTest() {
        thread {
            val sb = StringBuilder().apply {
                appendLine("=== DNS Test ===")
                appendLine("DNS: " + (getStr(KEY_DNS, DEFAULT_DNS)
                    ?: DEFAULT_DNS).replace("\n", ", "))
                listOf("cloudflare.com", "google.com", "github.com").forEach { d ->
                    try {
                        appendLine("[OK] $d -> " +
                            java.net.InetAddress.getByName(d).hostAddress)
                    } catch (e: Exception) {
                        appendLine("[FAIL] $d -> ${e.message}")
                    }
                }
                appendLine("===============")
            }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("DNS Test Result")
                    .setMessage(sb.toString()).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun loadPresets() {
        presets.clear()
        val j = prefs.getString(KEY_PRESETS, null) ?: return
        try {
            val a = JSONArray(j)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                presets.add(PresetConfig(
                    o.getString("name"),
                    o.optString("sni", DEFAULT_SNI),
                    o.optString("endpointV4", ""), o.optString("endpointV6", ""),
                    o.optString("dnsServers", DEFAULT_DNS),
                    o.optString("privateKey", ""), o.optString("endpointPubKey", ""),
                    o.optString("deviceName", DEFAULT_DEVICE),
                    o.optString("jwt", ""), o.optString("license", ""),
                    o.optString("token", ""), o.optString("accountId", ""),
                    o.optString("model", DEFAULT_MODEL),
                    o.optString("locale", DEFAULT_LOCALE),
                    o.optString("userAgent", ""), o.optString("clientVersion", ""),
                    o.optBoolean("zeroTier", false)
                ))
            }
        } catch (_: Exception) { }
    }

    private fun savePresets() {
        val a = JSONArray()
        presets.forEach { p ->
            a.put(JSONObject().apply {
                put("name", p.name); put("sni", p.sni)
                put("endpointV4", p.endpointV4); put("endpointV6", p.endpointV6)
                put("dnsServers", p.dnsServers)
                put("privateKey", p.privateKey); put("endpointPubKey", p.endpointPubKey)
                put("deviceName", p.deviceName)
                put("jwt", p.jwt); put("license", p.license)
                put("token", p.token); put("accountId", p.accountId)
                put("model", p.model); put("locale", p.locale)
                put("userAgent", p.userAgent); put("clientVersion", p.clientVersion)
                put("zeroTier", p.zeroTier)
            })
        }
        prefs.edit().putString(KEY_PRESETS, a.toString()).apply()
    }

    private fun showPresetDialog() {
        if (presets.isEmpty()) {
            Toast.makeText(this, "No presets", Toast.LENGTH_SHORT).show(); return
        }
        AlertDialog.Builder(this).setTitle("Presets")
            .setItems(presets.map { it.name }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this).setTitle("Action: ${presets[i].name}")
                    .setItems(arrayOf("Apply", "Edit", "Delete")) { _, op ->
                        when (op) {
                            0 -> { applyPreset(presets[i])
                                Toast.makeText(this, "Applied", Toast.LENGTH_SHORT).show() }
                            1 -> showEditPresetDialog(i)
                            2 -> { presets.removeAt(i); savePresets()
                                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show() }
                        }
                    }.show()
            }.show()
    }

    private fun showEditPresetDialog(idx: Int) {
        val p = presets[idx]
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)

        listOf(R.id.registered_device_id, R.id.registered_license,
            R.id.registered_ipv4, R.id.registered_ipv6, R.id.registered_pubkey)
            .forEach { v.findViewById<TextView>(it)?.visibility = View.GONE }
        v.findViewById<Button>(R.id.reenroll_button)?.visibility = View.GONE
        v.findViewById<Button>(R.id.regen_key_button)?.visibility = View.GONE

        v.findViewById<EditText>(R.id.sni_input).setText(p.sni)
        setupEndpointSpinners(v, p.endpointV4, p.endpointV6)
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

        AlertDialog.Builder(this).setTitle("Edit: ${p.name}").setView(v)
            .setPositiveButton("Save") { _, _ ->
                presets[idx] = PresetConfig(
                    p.name,
                    v.findViewById<EditText>(R.id.sni_input).text.toString(),
                    getEndpointFromSpinner(v, true),
                    getEndpointFromSpinner(v, false),
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
            .setNegativeButton("Cancel", null).show()
    }

    private fun findPresetIndex(presets: Array<String>, value: String): Int {
        val idx = presets.indexOf(value)
        return if (idx >= 0) idx + 1 else 0
    }

    private fun setupEndpointSpinners(v: View, currentV4: String, currentV6: String) {
        val sp4 = v.findViewById<Spinner>(R.id.endpoint_v4_spinner)
        val sp6 = v.findViewById<Spinner>(R.id.endpoint_v6_spinner)
        val ep4 = v.findViewById<EditText>(R.id.endpoint_v4_input)
        val ep6 = v.findViewById<EditText>(R.id.endpoint_v6_input)

        val v4Adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ENDPOINT_V4_ALL)
        v4Adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp4.adapter = v4Adapter
        sp4.setSelection(findPresetIndex(ENDPOINT_V4_PRESETS, currentV4))
        ep4.setText(currentV4)

        val v6Adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ENDPOINT_V6_ALL)
        v6Adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp6.adapter = v6Adapter
        sp6.setSelection(findPresetIndex(ENDPOINT_V6_PRESETS, currentV6))
        ep6.setText(currentV6)

        sp4.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos > 0) ep4.setText(ENDPOINT_V4_PRESETS[pos - 1])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        sp6.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos > 0) ep6.setText(ENDPOINT_V6_PRESETS[pos - 1])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getEndpointFromSpinner(v: View, isV4: Boolean): String {
        val sp = if (isV4) v.findViewById<Spinner>(R.id.endpoint_v4_spinner)
                 else v.findViewById<Spinner>(R.id.endpoint_v6_spinner)
        val ep = if (isV4) v.findViewById<EditText>(R.id.endpoint_v4_input)
                 else v.findViewById<EditText>(R.id.endpoint_v6_input)
        val presets = if (isV4) ENDPOINT_V4_PRESETS else ENDPOINT_V6_PRESETS
        return if (sp.selectedItemPosition > 0) presets[sp.selectedItemPosition - 1]
               else ep.text.toString()
    }

    private fun applyPreset(p: PresetConfig) {
        currentPresetName = p.name
        saveSettings(p.sni, p.endpointV4, p.endpointV6, p.dnsServers,
            p.privateKey, p.endpointPubKey, p.deviceName,
            p.jwt, p.license, p.token, p.accountId,
            p.model, p.locale, p.userAgent, p.clientVersion, p.zeroTier)
        loadSavedSettings(); updateUI()
    }

    private fun loadSavedSettings() {
        Usqueandroid.setSNI(getStr(KEY_SNI, DEFAULT_SNI))
        getStr(KEY_ENDPOINT_V4, "").let { if (it.isNotEmpty()) Usqueandroid.setEndpointV4(it) }
        getStr(KEY_ENDPOINT_V6, "").let { if (it.isNotEmpty()) Usqueandroid.setEndpointV6(it) }
        getStr(KEY_PRIVATE_KEY, "").let { if (it.isNotEmpty()) Usqueandroid.setPrivateKey(it) }
        getStr(KEY_ENDPOINT_PUBKEY, "").let { if (it.isNotEmpty()) Usqueandroid.setEndpointPublicKey(it) }
        getStr(KEY_JWT, "").let { if (it.isNotEmpty()) Usqueandroid.setJWT(it) }
        getStr(KEY_LICENSE, "").let { if (it.isNotEmpty()) Usqueandroid.setLicense(it) }
        getStr(KEY_TOKEN, "").let { if (it.isNotEmpty()) Usqueandroid.setToken(it) }
        getStr(KEY_ACCOUNT_ID, "").let { if (it.isNotEmpty()) Usqueandroid.setAccountID(it) }
        Usqueandroid.setModel(getStr(KEY_MODEL, DEFAULT_MODEL))
        Usqueandroid.setLocale(getStr(KEY_LOCALE, DEFAULT_LOCALE))
        getStr(KEY_USER_AGENT, "").let { if (it.isNotEmpty()) Usqueandroid.setUserAgent(it) }
        getStr(KEY_CLIENT_VERSION, "").let { if (it.isNotEmpty()) Usqueandroid.setClientVersion(it) }
        Usqueandroid.setUseZeroTier(getBool(KEY_ZERO_TIER, false))
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
            .putString(KEY_SNI, sni).putString(KEY_ENDPOINT_V4, ep4)
            .putString(KEY_ENDPOINT_V6, ep6).putString(KEY_DNS, dns)
            .putString(KEY_PRIVATE_KEY, pk).putString(KEY_ENDPOINT_PUBKEY, pub)
            .putString(KEY_DEVICE_NAME, dev)
            .putString(KEY_JWT, jwt).putString(KEY_LICENSE, lic)
            .putString(KEY_TOKEN, tok).putString(KEY_ACCOUNT_ID, aid)
            .putString(KEY_MODEL, model).putString(KEY_LOCALE, locale)
            .putString(KEY_USER_AGENT, userAgent).putString(KEY_CLIENT_VERSION, clientVersion)
            .putBoolean(KEY_ZERO_TIER, zeroTier)
            .apply()
    }

    private fun showSettingsDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val sni = v.findViewById<EditText>(R.id.sni_input)
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
        val rip4 = v.findViewById<TextView>(R.id.registered_ipv4)
        val rip6 = v.findViewById<TextView>(R.id.registered_ipv6)
        val rpk = v.findViewById<TextView>(R.id.registered_pubkey)
        val cp = configPath()

        sni.setText(getStr(KEY_SNI, Usqueandroid.getSNI()))
        dns.setText(getStr(KEY_DNS, DEFAULT_DNS))
        pk.setText(getStr(KEY_PRIVATE_KEY, getRegStr("") { Usqueandroid.getPrivateKeyB64(it) }))
        pub.setText(getStr(KEY_ENDPOINT_PUBKEY, getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }))
        dev.setText(getStr(KEY_DEVICE_NAME, DEFAULT_DEVICE))
        jwt.setText(getStr(KEY_JWT, "")); lic.setText(getStr(KEY_LICENSE, ""))
        tok.setText(getStr(KEY_TOKEN, "")); aid.setText(getStr(KEY_ACCOUNT_ID, ""))
        model.setText(getStr(KEY_MODEL, Usqueandroid.getModel()))
        locale.setText(getStr(KEY_LOCALE, Usqueandroid.getLocale()))
        ua.setText(getStr(KEY_USER_AGENT, Usqueandroid.getUserAgent()))
        cv.setText(getStr(KEY_CLIENT_VERSION, Usqueandroid.getClientVersion()))
        zt.isChecked = getBool(KEY_ZERO_TIER, Usqueandroid.getUseZeroTier())

        setupEndpointSpinners(v,
            getStr(KEY_ENDPOINT_V4, Usqueandroid.getEndpointV4()),
            getStr(KEY_ENDPOINT_V6, Usqueandroid.getEndpointV6()))

        if (Usqueandroid.isRegistered(cp)) {
            rid.text = "DeviceID: " + getRegStr("(N/A)") { Usqueandroid.getDeviceID(it) }
            rlic.text = "License: " + getRegStr("") { Usqueandroid.getLicense(it) }
            rip4.text = getRegStr("(N/A)") { Usqueandroid.getAssignedIPv4(it) }
            rip6.text = getRegStr("(N/A)") { Usqueandroid.getAssignedIPv6(it) }
            rpk.text = "PubKey: " + getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }
        }

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
                        pk.setText(getRegStr("") { Usqueandroid.getPrivateKeyB64(it) })
                        pub.setText(getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) })
                        rpk.text = "PubKey: " + getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }
                        rip4.text = getRegStr("(N/A)") { Usqueandroid.getAssignedIPv4(it) }
                        rip6.text = getRegStr("(N/A)") { Usqueandroid.getAssignedIPv6(it) }
                    } else Toast.makeText(this, "Fail: $err", Toast.LENGTH_LONG).show()
                }
            }
        }

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
                        rpk.text = "PubKey: " + getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }
                        rip4.text = getRegStr("(N/A)") { Usqueandroid.getAssignedIPv4(it) }
                        rip6.text = getRegStr("(N/A)") { Usqueandroid.getAssignedIPv6(it) }
                    } else Toast.makeText(this, "Fail: $err", Toast.LENGTH_LONG).show()
                }
            }
        }

        val proxyStatus = v.findViewById<TextView>(R.id.proxy_status_text)
        val proxyToggle = v.findViewById<Button>(R.id.proxy_toggle_button)
        val proxyMode = v.findViewById<Spinner>(R.id.proxy_mode_spinner)
        val proxyBind = v.findViewById<EditText>(R.id.proxy_bind_input)
        val proxyPort = v.findViewById<EditText>(R.id.proxy_port_input)
        val proxyUser = v.findViewById<EditText>(R.id.proxy_user_input)
        val proxyPass = v.findViewById<EditText>(R.id.proxy_pass_input)

        proxyBind.setText("127.0.0.1")
        proxyPort.setText("8080")
        updateProxyUI(proxyStatus, proxyToggle)

        proxyToggle.setOnClickListener {
            if (Usqueandroid.isProxyRunning()) {
                thread {
                    Usqueandroid.stopProxy()
                    runOnUiThread { updateProxyUI(proxyStatus, proxyToggle) }
                }
            } else {
                thread {
                    val mode = if (proxyMode.selectedItemPosition == 1) "http" else "socks5"
                    val bind = proxyBind.text.toString().ifEmpty { "127.0.0.1" }
                    val port = proxyPort.text.toString().toLongOrNull() ?: 8080L
                    val user = proxyUser.text.toString()
                    val pass = proxyPass.text.toString()
                    val err = Usqueandroid.startProxy(cp, mode, bind, port, user, pass)
                    runOnUiThread {
                        if (err.isEmpty()) {
                            Toast.makeText(this, "Proxy started on $bind:$port", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Proxy error: $err", Toast.LENGTH_LONG).show()
                        }
                        updateProxyUI(proxyStatus, proxyToggle)
                    }
                }
            }
        }

        AlertDialog.Builder(this).setTitle("Settings").setView(v)
            .setPositiveButton("Save") { _, _ ->
                saveSettings(
                    sni.text.toString(),
                    getEndpointFromSpinner(v, true),
                    getEndpointFromSpinner(v, false),
                    dns.text.toString(),
                    pk.text.toString().trim(), pub.text.toString().trim(),
                    dev.text.toString(),
                    jwt.text.toString().trim(), lic.text.toString().trim(),
                    tok.text.toString().trim(), aid.text.toString().trim(),
                    model.text.toString(), locale.text.toString(),
                    ua.text.toString(), cv.text.toString(), zt.isChecked
                )
                loadSavedSettings()
                updateRegisteredInfo()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Export") { _, _ -> showExportDialog() }.show()
    }

    private fun updateProxyUI(statusText: TextView, toggleButton: Button) {
        if (Usqueandroid.isProxyRunning()) {
            statusText.text = "Status: Running on port ${Usqueandroid.getProxyPort()}"
            statusText.setTextColor(Color.parseColor("#059669"))
            toggleButton.text = "Stop Proxy"
            toggleButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#b91c1c"))
        } else {
            statusText.text = "Status: Stopped"
            statusText.setTextColor(Color.parseColor("#94a3b8"))
            toggleButton.text = "Start Proxy"
            toggleButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#059669"))
        }
    }

    private fun showExportDialog() {
        val sb = StringBuilder().apply {
            appendLine("=== Usque Config Export ===")
            appendLine("SNI: " + getStr(KEY_SNI, Usqueandroid.getSNI()))
            appendLine("EndpointV4: " + getStr(KEY_ENDPOINT_V4, Usqueandroid.getEndpointV4()))
            appendLine("EndpointV6: " + getStr(KEY_ENDPOINT_V6, Usqueandroid.getEndpointV6()))
            appendLine("DNS: " + (getStr(KEY_DNS, DEFAULT_DNS) ?: DEFAULT_DNS).replace("\n", ", "))
            getRegStr("") { Usqueandroid.getPrivateKeyB64(it) }
                .let { if (it.isNotEmpty()) appendLine("PrivateKey: $it") }
            getRegStr("") { Usqueandroid.getEndpointPubKeyPEM(it) }
                .let { if (it.isNotEmpty()) appendLine("PubKey: $it") }
            getRegStr("") { Usqueandroid.getDeviceID(it) }
                .let { if (it.isNotEmpty()) appendLine("DeviceID: $it") }
            getRegStr("") { Usqueandroid.getLicense(it) }
                .let { if (it.isNotEmpty()) appendLine("License: $it") }
            appendLine("Model: " + getStr(KEY_MODEL, DEFAULT_MODEL))
            appendLine("Locale: " + getStr(KEY_LOCALE, DEFAULT_LOCALE))
            appendLine("ZeroTier: " + getBool(KEY_ZERO_TIER, false))
            appendLine("JWT: " + getStr(KEY_JWT, "(none)"))
            appendLine("============================")
        }
        val lv = TextView(this).apply {
            text = sb.toString(); textSize = 11f
            setTextColor(Color.parseColor("#e8ecf4"))
            setBackgroundColor(Color.parseColor("#161b30"))
            setPadding(24, 24, 24, 24)
            movementMethod = ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(4f, 1f)
        }
        AlertDialog.Builder(this).setTitle("Export Config").setView(lv)
            .setPositiveButton("Copy") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("usque_config", sb.toString()))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sb.toString())
                    }, "Share Config"))
            }
            .setNegativeButton("Close", null).show()
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
        handler.postDelayed({ updateUI(); updateRegisteredInfo() }, 3500)
    }

    private fun updateUI() {
        if (UsqueVpnService.isRunning) {
            connectButton.text = "断开"; connectButton.isSelected = true
            statusText.text = "已连接"
            statusText.setTextColor(Color.parseColor("#00d4aa"))
            infoRow.visibility = View.VISIBLE; speedRow.visibility = View.VISIBLE
            startSpeedUpdater(); startLatencyUpdater(); startDurationUpdater()
            currentPresetText.text = currentPresetName ?: ""
        } else {
            connectButton.text = "连接"; connectButton.isSelected = false
            statusText.text = "未连接"
            statusText.setTextColor(Color.parseColor("#636e84"))
            infoRow.visibility = View.GONE; speedRow.visibility = View.GONE
            latencyText.text = "-- ms"
            totalDataText.text = "0 B"; currentPresetText.text = ""
            stopSpeedUpdater(); stopLatencyUpdater(); stopDurationUpdater()
            lastRx = 0; lastTx = 0; lastSpeedTime = 0
        }
    }
}

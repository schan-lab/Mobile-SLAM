package com.example.mobilendt

import android.app.Activity
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/** Reads / changes the Mid-360's IP address and the destination (this phone) of its data. */
class Mid360NetworkActivity : Activity() {

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    @Volatile private var network: Network? = null
    /** IPv4 address / prefix length of the Ethernet interface (null = not connected). */
    @Volatile private var phone: Pair<String, Int>? = null
    @Volatile private var detectedIP = ""

    private var currentIP = ""
    private var lidarIP = ""
    private var mask = "255.255.255.0"
    private var gateway = ""
    private var hostIP = ""
    private var busy = false
    private var prefilled = false

    private lateinit var phoneValue: TextView
    private lateinit var detectedValue: TextView
    private lateinit var currentField: EditText
    private lateinit var lidarField: EditText
    private lateinit var maskField: EditText
    private lateinit var gatewayField: EditText
    private lateinit var hostField: EditText
    private lateinit var loadBtn: TextView
    private lateinit var writeBtn: TextView
    private lateinit var subnetWarn: TextView
    private lateinit var sameWarn: TextView
    private lateinit var rebootNote: TextView
    private lateinit var message: TextView

    private val dev by lazy { Ui.deviceName(this) }

    // ------------------------------------------------------------------ layout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val pad = Ui.dpi(c, 12f)

        val back = Ui.button(c, "←", Ui.GREY, radius = 20f, textSize = 18f).apply { setOnClickListener { finish() } }
        val title = Ui.text(c, 20f, bold = true).apply { text = getString(R.string.net_title) }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(Ui.dpi(c, 40f), Ui.dpi(c, 40f)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(c, 12f) })
        }

        // ---- connection ----
        phoneValue = valueText()
        detectedValue = valueText()
        val statusCard = Ui.section(c, getString(R.string.net_connection)).apply {
            addView(row(getString(R.string.net_wired, dev), phoneValue))
            addView(row(getString(R.string.net_detected), detectedValue), lp(6f))
            if (Settings.demoMode) addView(Ui.note(c, getString(R.string.net_demo_on), Ui.ORANGE), lp(8f))
        }

        // ---- current Mid-360 ----
        currentField = Ui.ipField(c) { currentIP = it; refresh() }
        loadBtn = Ui.button(c, getString(R.string.net_load), Ui.GREY, radius = 20f, textSize = 14f).apply {
            setOnClickListener { load() }
        }
        val currentCard = Ui.section(c, getString(R.string.net_current)).apply {
            addField(this, getString(R.string.net_lidar_ip), currentField)
            addView(Ui.note(c, getString(R.string.net_current_note, dev)), lp(6f))
            addView(loadBtn, LinearLayout.LayoutParams(-1, Ui.dpi(c, 40f)).apply { topMargin = Ui.dpi(c, 10f) })
        }

        // ---- new settings ----
        lidarField = Ui.ipField(c) { lidarIP = it; refresh() }
        maskField = Ui.ipField(c) { mask = it; refresh() }
        gatewayField = Ui.ipField(c) { gateway = it; refresh() }
        hostField = Ui.ipField(c) { hostIP = it; refresh() }
        subnetWarn = Ui.note(c, getString(R.string.net_not_same_subnet), Ui.ORANGE)
        sameWarn = Ui.note(c, getString(R.string.net_same_ip), Ui.RED)
        rebootNote = Ui.note(c, "")
        writeBtn = Ui.button(c, getString(R.string.net_write), Ui.ACCENT, textSize = 15f).apply {
            setOnClickListener { confirmWrite() }
        }
        val newCard = Ui.section(c, getString(R.string.net_new)).apply {
            addField(this, getString(R.string.net_lidar_ip), lidarField)
            addField(this, getString(R.string.net_mask), maskField)
            addField(this, getString(R.string.net_gateway), gatewayField)
            addField(this, getString(R.string.net_host_ip, dev), hostField)
            addView(subnetWarn, lp(8f))
            addView(sameWarn, lp(8f))
            addView(rebootNote, lp(8f))
            addView(writeBtn, LinearLayout.LayoutParams(-1, Ui.dpi(c, 48f)).apply { topMargin = Ui.dpi(c, 10f) })
        }

        message = Ui.text(c, 13f, Ui.GREEN, bold = true).apply {
            background = Ui.card(c)
            val p = Ui.dpi(c, 14f)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }

        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(top)
            addView(statusCard, lp(14f))
            addView(currentCard, lp(12f))
            addView(newCard, lp(12f))
            addView(message, lp(12f))
            addView(Ui.note(c, getString(R.string.net_footer)), lp(12f))
        }
        val root = ScrollView(c).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(Ui.capWidth(c, column, 700f))
            Ui.padForSystemBars(this)
        }
        setContentView(root)
        maskField.setText(mask)
        refresh()
    }

    private fun lp(topDp: Float) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(this@Mid360NetworkActivity, topDp) }

    private fun valueText() = Ui.text(this, 14f, bold = true).apply { typeface = android.graphics.Typeface.MONOSPACE }

    private fun row(label: String, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.text(context, 14f, Ui.TEXT_DIM).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(value)
    }

    private fun addField(parent: LinearLayout, label: String, field: EditText) {
        parent.addView(Ui.text(this, 13f, bold = true).apply { text = label }, lp(if (parent.childCount > 1) 10f else 0f))
        parent.addView(field, LinearLayout.LayoutParams(-1, Ui.dpi(this, 40f)).apply { topMargin = Ui.dpi(this@Mid360NetworkActivity, 4f) })
    }

    // ------------------------------------------------------------------ state

    private val allValid get() = listOf(currentIP, lidarIP, mask, gateway, hostIP).all { Mid360Config.isValid(it) }
    private val changesLidarIP get() = lidarIP != currentIP

    private fun refresh() {
        if (!::message.isInitialized) return
        phoneValue.text = phone?.let { (ip, prefix) -> "$ip / ${Mid360Config.prefixToMask(prefix)}" }
            ?: getString(R.string.net_not_connected)
        detectedValue.text = detectedIP.ifEmpty { getString(R.string.not_detected) }
        subnetWarn.visibility = if (allValid && !Mid360Config.sameSubnet(lidarIP, hostIP, mask)) View.VISIBLE else View.GONE
        sameWarn.visibility = if (allValid && lidarIP == hostIP) View.VISIBLE else View.GONE
        rebootNote.text = getString(if (changesLidarIP) R.string.net_reboot_note else R.string.net_no_reboot_note)
        enable(loadBtn, !busy && Mid360Config.isValid(currentIP))
        enable(writeBtn, !busy && !Settings.demoMode && allValid && lidarIP != hostIP)
        writeBtn.text = getString(if (busy) R.string.net_busy else R.string.net_write)
    }

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.4f
    }

    private fun showMessage(text: String, isError: Boolean) {
        message.text = text
        message.setTextColor(if (isError) Ui.RED else Ui.GREEN)
        message.visibility = View.VISIBLE
    }

    /** Fills in the detected LiDAR and this phone's address once both are known; reads the LiDAR's settings. */
    private fun prefill() {
        if (prefilled) return
        val p = phone
        if (p != null && hostIP.isEmpty()) {
            hostField.setText(p.first)
            maskField.setText(Mid360Config.prefixToMask(p.second))
        }
        if (detectedIP.isNotEmpty() && currentIP.isEmpty()) {
            prefilled = true
            currentField.setText(detectedIP)
            lidarField.setText(detectedIP)
            load()
        }
    }

    private fun errorText(e: Throwable): String = when (e) {
        is Mid360Config.Failure.BadAddress -> getString(R.string.err_bad_address, e.address)
        is Mid360Config.Failure.Socket -> getString(R.string.err_socket, e.reason)
        is Mid360Config.Failure.NoResponse -> getString(R.string.err_no_response, dev)
        is Mid360Config.Failure.Rejected -> getString(R.string.err_rejected, e.code, e.key)
        is Mid360Config.Failure.Malformed -> getString(R.string.err_malformed)
        else -> getString(R.string.err_socket, e.message ?: e.javaClass.simpleName)
    }

    /** Binds command sockets to the Ethernet network, so they never go out over Wi-Fi / mobile data. */
    private val bindEthernet: (DatagramSocket) -> Unit = { s -> network?.bindSocket(s) }

    // ------------------------------------------------------------------ actions

    private fun load() {
        val ip = currentIP
        busy = true
        message.visibility = View.GONE
        refresh()
        thread(name = "mid360-read") {
            val r = runCatching { Mid360Config.read(ip, bindEthernet) }
            runOnUiThread {
                busy = false
                r.onSuccess { n ->
                    lidarField.setText(n.lidarIP); maskField.setText(n.mask); gatewayField.setText(n.gateway)
                    if (hostIP.isEmpty()) hostField.setText(n.hostIP)
                    showMessage(getString(R.string.net_loaded, n.hostIP), false)
                }.onFailure { showMessage(errorText(it), true) }
                refresh()
            }
        }
    }

    private fun confirmWrite() {
        AlertDialog.Builder(this)
            .setTitle(R.string.net_confirm_title)
            .setMessage(getString(R.string.net_confirm_msg, currentIP, lidarIP, hostIP))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.net_write_ok) { _, _ -> write() }
            .show()
    }

    private fun write() {
        val cur = currentIP
        val net = Mid360Config.Network(lidarIP, mask, gateway, hostIP)
        val change = changesLidarIP
        busy = true
        message.visibility = View.GONE
        refresh()
        thread(name = "mid360-write") {
            val r = runCatching { Mid360Config.write(cur, net, change, bindEthernet) }
            runOnUiThread {
                busy = false
                r.onSuccess { rebooted ->
                    var s = getString(if (rebooted) R.string.net_written_reboot else R.string.net_written)
                    val p = phone
                    if (p != null && (p.first != net.hostIP ||
                            !Mid360Config.sameSubnet(p.first, net.lidarIP, Mid360Config.prefixToMask(p.second)))) {
                        s += "\n" + getString(R.string.net_set_phone_ip, dev, net.hostIP, net.mask)
                    }
                    showMessage(s, false)
                    currentField.setText(net.lidarIP)
                    LidarReceiver.lastLidarIp = net.lidarIP
                }.onFailure { showMessage(errorText(it), true) }
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ Ethernet + discovery

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { network = n }
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            network = n
            val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
            phone = v4?.let { (it.address.hostAddress ?: "") to it.prefixLength }
            runOnUiThread { refresh(); prefill() }
        }
        override fun onLost(n: Network) {
            if (n != network) return
            network = null; phone = null
            runOnUiThread { refresh() }
        }
    }

    @Volatile private var discovering = false
    private var discovery: Thread? = null
    private var discoverySocket: DatagramSocket? = null

    override fun onStart() {
        super.onStart()
        detectedIP = LidarReceiver.lastLidarIp.takeIf { !Settings.demoMode } ?: ""
        cm.requestNetwork(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build(), callback)
        startDiscovery()
        refresh()
        prefill()
    }

    override fun onStop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        stopDiscovery()
        super.onStop()
    }

    /** Broadcasts Livox discovery requests on the Ethernet subnet and shows the IP of the Mid-360 that answers. */
    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        discovery = thread(name = "mid360-net-detect", isDaemon = true) {
            val s = try {
                DatagramSocket(null).apply {
                    reuseAddress = true; broadcast = true; soTimeout = 300
                    bind(InetSocketAddress(Livox.HOST_DETECT_PORT))
                }
            } catch (e: Exception) {
                Log.w(LidarReceiver.TAG, "detect socket: ${e.message}"); return@thread
            }
            discoverySocket = s
            var bound: Network? = null
            val buf = ByteArray(1500)
            val rx = DatagramPacket(buf, buf.size)
            var seq = 0
            var nextTx = 0L
            s.use {
                while (discovering) {
                    val n = network
                    if (n != null && n != bound) { runCatching { n.bindSocket(s) }; bound = n }
                    if (System.nanoTime() >= nextTx) {
                        nextTx = System.nanoTime() + 1_000_000_000L
                        val req = Livox.buildDetectRequest(seq++)
                        val targets = listOfNotNull("255.255.255.255", phone?.let { Mid360Config.broadcast(it.first, it.second) })
                        for (t in targets) runCatching {
                            s.send(DatagramPacket(req, req.size, InetAddress.getByName(t), Livox.LIDAR_DETECT_PORT))
                        }
                    }
                    try {
                        rx.length = buf.size
                        s.receive(rx)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    val ack = Livox.parseDetectAck(buf, rx.length) ?: continue
                    if (ack.ip != detectedIP) {
                        detectedIP = ack.ip
                        LidarReceiver.lastLidarIp = ack.ip
                        runOnUiThread { refresh(); prefill() }
                    }
                }
            }
        }
    }

    private fun stopDiscovery() {
        discovering = false
        discoverySocket?.close()
        discovery?.join(1000)
        discovery = null
        discoverySocket = null
    }
}

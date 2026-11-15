package com.example.mobilendt

import android.app.Activity
import android.content.ContentValues
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private companion object { const val REQ_PICK_MAP = 10 }

    private val points = PointBuffer(40_000)   // ~0.2 s of Mid-360 output
    private lateinit var receiver: LidarReceiver
    private lateinit var slam: SlamNative

    private lateinit var mapView: Map3DView
    private lateinit var chipEth: TextView
    private lateinit var chipLidar: TextView
    private lateinit var chipImu: TextView
    private lateinit var phase: TextView
    private lateinit var headline: TextView
    private lateinit var details: TextView
    private lateinit var hint: TextView
    private lateinit var mainBtn: TextView
    private lateinit var saveBtn: TextView
    private lateinit var recBtn: TextView
    private lateinit var modeMapBtn: TextView
    private lateinit var modeLocBtn: TextView
    private lateinit var mapPickBtn: TextView
    private lateinit var initBtn: TextView
    private lateinit var locRow: LinearLayout

    /** 0 = mapping (SLAM), 1 = localisation (NDT). */
    private var mode = 0
    private var mapName: String? = null
    private var loadingMap = false

    private val ui = Handler(Looper.getMainLooper())
    private var prevT = 0L
    private var prevPkts = 0L
    private var prevPts = 0L
    private var prevImu = 0L
    private var tick = 0
    private var recFile: File? = null
    private var mapStartMs = 0L
    private var showDetails = false
    private val st = DoubleArray(SlamNative.S.SIZE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        slam = SlamNative()
        receiver = LidarReceiver(this, points)
        receiver.packetSink = { type, buf, len -> slam.push(type, buf, len) }
        // test hook: adb shell am start -n com.example.mobilendt/.MainActivity --ez demo true
        if (intent.getBooleanExtra("demo", false)) Settings.demoMode = true

        setContentView(buildUi())
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val c = this
        val pad = Ui.dpi(c, 12f)

        mapView = Map3DView(c, slam, points)

        // --- top status card ---
        chipEth = Ui.chip(c); chipLidar = Ui.chip(c); chipImu = Ui.chip(c)
        val chips = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(chipEth, chipLidar, chipImu).forEach {
                addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dpi(c, 6f) })
            }
        }
        phase = Ui.text(c, 12f, bold = true).apply {
            setPadding(Ui.dpi(c, 10f), Ui.dpi(c, 3f), Ui.dpi(c, 10f), Ui.dpi(c, 3f))
        }
        headline = Ui.text(c, 15f, bold = true)
        val viewerBtn = Ui.button(c, getString(R.string.saved_maps), Ui.GREY, radius = 16f, textSize = 12f).apply {
            setPadding(Ui.dpi(c, 12f), 0, Ui.dpi(c, 12f), 0)
            setOnClickListener { startActivity(android.content.Intent(c, PcdListActivity::class.java)) }
        }
        val settingsBtn = Ui.button(c, getString(R.string.settings), Ui.GREY, radius = 16f, textSize = 12f).apply {
            setPadding(Ui.dpi(c, 12f), 0, Ui.dpi(c, 12f), 0)
            setOnClickListener { startActivity(android.content.Intent(c, SettingsActivity::class.java)) }
        }
        val phaseRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(phase)
            addView(headline, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(c, 10f) })
            addView(viewerBtn, LinearLayout.LayoutParams(-2, Ui.dpi(c, 32f)))
            addView(settingsBtn, LinearLayout.LayoutParams(-2, Ui.dpi(c, 32f)).apply { marginStart = Ui.dpi(c, 6f) })
        }
        details = Ui.text(c, 11f, Ui.TEXT_DIM).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }
        val cardBody = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(c)
            setPadding(pad, pad, pad, pad)
            addView(chips)
            addView(phaseRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 10f) })
            addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 8f) })
            setOnClickListener {
                showDetails = !showDetails
                details.visibility = if (showDetails) View.VISIBLE else View.GONE
            }
        }
        // scrolls only when the details make it taller than the space it gets (landscape / small screens)
        val card = android.widget.ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(cardBody)
        }

        // --- bottom controls ---
        recBtn = Ui.button(c, getString(R.string.record), Ui.GREY, textSize = 14f).apply { setOnClickListener { toggleRecord() } }
        mainBtn = Ui.button(c, getString(R.string.start_mapping), Ui.ACCENT, textSize = 17f).apply { setOnClickListener { toggleMapping() } }
        saveBtn = Ui.button(c, getString(R.string.save_pcd), Ui.GREY, textSize = 14f).apply {
            setOnClickListener { savePcd() }; alpha = 0.4f; isEnabled = false
        }
        val h = Ui.dpi(c, 56f)
        val controls = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.card(c)
            setPadding(Ui.dpi(c, 8f), Ui.dpi(c, 8f), Ui.dpi(c, 8f), Ui.dpi(c, 8f))
            addView(recBtn, LinearLayout.LayoutParams(0, h, 1f))
            addView(mainBtn, LinearLayout.LayoutParams(0, h, 1.8f).apply {
                marginStart = Ui.dpi(c, 8f); marginEnd = Ui.dpi(c, 8f)
            })
            addView(saveBtn, LinearLayout.LayoutParams(0, h, 1f))
        }
        hint = Ui.text(c, 11f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.hint_view)
        }
        modeMapBtn = Ui.button(c, getString(R.string.mode_mapping), Ui.ACCENT, radius = 18f, textSize = 13f).apply { setOnClickListener { switchMode(0) } }
        modeLocBtn = Ui.button(c, getString(R.string.mode_loc), Ui.GREY, radius = 18f, textSize = 13f).apply { setOnClickListener { switchMode(1) } }
        val modeRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.card(c)
            setPadding(Ui.dpi(c, 6f), Ui.dpi(c, 6f), Ui.dpi(c, 6f), Ui.dpi(c, 6f))
            addView(modeMapBtn, LinearLayout.LayoutParams(0, Ui.dpi(c, 38f), 1f))
            addView(modeLocBtn, LinearLayout.LayoutParams(0, Ui.dpi(c, 38f), 1f).apply { marginStart = Ui.dpi(c, 6f) })
        }
        mapPickBtn = Ui.button(c, getString(R.string.choose_map), Ui.GREY, radius = 18f, textSize = 13f).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(Ui.dpi(c, 10f), 0, Ui.dpi(c, 10f), 0)
            setOnClickListener { pickMap() }
        }
        initBtn = Ui.button(c, getString(R.string.set_start), Ui.GREY, radius = 18f, textSize = 13f).apply { setOnClickListener { togglePickPose() } }
        locRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(mapPickBtn, LinearLayout.LayoutParams(0, Ui.dpi(c, 40f), 1.5f))
            addView(initBtn, LinearLayout.LayoutParams(0, Ui.dpi(c, 40f), 1f).apply { marginStart = Ui.dpi(c, 8f) })
        }
        mapView.onPosePicked = { x, y, yaw -> onPosePicked(x, y, yaw) }
        val bottom = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(c, 6f) })
            addView(locRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(c, 8f) })
            addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(c, 8f) })
            addView(controls, LinearLayout.LayoutParams(-1, -2))
        }

        val overlay = OverlayLayout(c, card, bottom)
        val root = FrameLayout(c).apply {
            setBackgroundColor(Ui.BG)
            addView(mapView, FrameLayout.LayoutParams(-1, -1))
            addView(overlay, FrameLayout.LayoutParams(-1, -1).apply { setMargins(pad, pad, pad, pad) })
            Ui.padForSystemBars(this)
        }
        updatePhase()
        return root
    }

    // ------------------------------------------------------------------ actions

    private fun mapping() = st[SlamNative.S.RUNNING] > 0.5

    private fun toggleMapping() {
        slam.status(st)
        if (!mapping()) {
            if (mode == 1 && st[SlamNative.S.HAS_PRIOR] < 0.5) { pickMap(); return }
            if (mapView.pickMode) togglePickPose()
            receiver.restartDemoMotion()   // demo: stand still for the IMU initialisation
            slam.start(mode)
            mapStartMs = System.currentTimeMillis()
        } else {
            slam.stop()
        }
        slam.status(st)
        updatePhase()
    }

    private fun switchMode(m: Int) {
        if (m == mode) return
        slam.status(st)
        if (mapping()) { Toast.makeText(this, R.string.stop_to_switch, Toast.LENGTH_SHORT).show(); return }
        val apply = {
            mode = m
            slam.prepare(m)
            if (mapView.pickMode) togglePickPose()
            mapView.guess = null
            updateModeUi()
            if (m == 1 && st[SlamNative.S.HAS_PRIOR] < 0.5) pickMap()
        }
        if (mode == 0 && st[SlamNative.S.VIS_SIZE] > 0) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.switch_to_loc_title)
                .setMessage(R.string.switch_to_loc_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.switch_) { _, _ -> apply() }
                .show()
        } else apply()
    }

    private fun updateModeUi() {
        Ui.setButtonColor(modeMapBtn, if (mode == 0) Ui.ACCENT else Ui.GREY, 18f)
        Ui.setButtonColor(modeLocBtn, if (mode == 1) Ui.ACCENT else Ui.GREY, 18f)
        locRow.visibility = if (mode == 1) View.VISIBLE else View.GONE
        mapPickBtn.text = when {
            loadingMap -> getString(R.string.loading)
            mapName != null -> "🗺 $mapName"
            else -> getString(R.string.choose_map)
        }
        Ui.setButtonColor(initBtn, if (mapView.pickMode) Ui.ORANGE else Ui.GREY, 18f)
        initBtn.text = getString(if (mapView.pickMode) R.string.cancel_pick else R.string.set_start)
        hint.text = getString(if (mapView.pickMode) R.string.hint_pick else R.string.hint_view)
        hint.setTextColor(if (mapView.pickMode) Ui.ORANGE else Ui.TEXT_DIM)
        updatePhase()
    }

    private fun pickMap() {
        if (loadingMap) return
        slam.status(st)
        if (mapping()) { Toast.makeText(this, R.string.stop_to_pick, Toast.LENGTH_SHORT).show(); return }
        @Suppress("DEPRECATION")
        startActivityForResult(android.content.Intent(this, PcdListActivity::class.java)
            .putExtra(PcdListActivity.EXTRA_PICK, true), REQ_PICK_MAP)
    }

    @Deprecated("startActivityForResult is fine for this single picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_MAP || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = data.getStringExtra(PcdViewActivity.EXTRA_NAME) ?: "map.pcd"
        loadingMap = true
        updateModeUi()
        kotlin.concurrent.thread(name = "prior-map") {
            val r = runCatching {
                val d = contentResolver.openInputStream(uri)!!.use { PcdFile.read(it) }
                require(d.n > 100) { getString(R.string.too_few_points, d.n) }
                check(slam.setPriorMap(d.xyz, d.inten, d.n)) { getString(R.string.ndt_failed) }
                d.n
            }
            runOnUiThread {
                loadingMap = false
                r.onSuccess { n ->
                    mapName = name
                    Toast.makeText(this, getString(R.string.map_loaded, "%,d".format(n)), Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(this, getString(R.string.map_load_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                updateModeUi()
            }
        }
    }

    private fun togglePickPose() {
        if (mode != 1) return
        if (st[SlamNative.S.HAS_PRIOR] < 0.5) { pickMap(); return }
        mapView.pickMode = !mapView.pickMode
        if (!mapView.pickMode) mapView.pitch = 50f
        updateModeUi()
    }

    private fun onPosePicked(x: Float, y: Float, yaw: Float) {
        val hasYaw = !yaw.isNaN()
        slam.setInitialPose(x, y, 0f, if (hasYaw) yaw else 0f, hasYaw)
        mapView.pickMode = false
        mapView.pitch = 70f
        updateModeUi()
        val msg = if (hasYaw) getString(R.string.initial_pose_yaw, x, y, Math.toDegrees(yaw.toDouble()))
                  else getString(R.string.initial_pose_auto, x, y)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Writes the map to a temp file, then copies it to Download/MobileNDT/ via MediaStore. */
    private fun savePcd() {
        val name = "map_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pcd"
        val tmp = File(cacheDir, name)
        val msg = try {
            check(slam.savePcd(tmp.absolutePath)) { getString(R.string.pcd_write_failed) }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MobileNDT")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error(getString(R.string.mediastore_failed))
            contentResolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            getString(R.string.saved_to, "Download/MobileNDT/$name")
        } catch (e: Exception) {
            getString(R.string.save_failed, e.message ?: "")
        } finally {
            tmp.delete()
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        Log.i(LidarReceiver.TAG, msg)
    }

    private fun toggleRecord() {
        val cur = receiver.recorder
        if (cur == null) {
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(getExternalFilesDir(null), "raw_$name.livox")
            receiver.recordedBytes.set(0)
            receiver.recorder = try {
                BufferedOutputStream(FileOutputStream(f), 1 shl 20)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.record_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                return
            }
            recFile = f
            Ui.setButtonColor(recBtn, Ui.RED)
        } else {
            receiver.recorder = null
            synchronized(cur) { cur.flush(); cur.close() }
            recBtn.text = getString(R.string.record)
            Ui.setButtonColor(recBtn, Ui.GREY)
            Toast.makeText(this, getString(R.string.raw_saved, recFile?.name ?: ""), Toast.LENGTH_LONG).show()
            Log.i(LidarReceiver.TAG, "record saved: ${recFile?.absolutePath}")
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Pushes the saved settings to the SLAM core and the view (called whenever we come to the front). */
    private fun applySettings() {
        slam.setBlind(Settings.blind(this))
        mapView.colorMode = Settings.colorMode(this)
    }

    override fun onStart() {
        super.onStart()
        applySettings()
        receiver.start()
        if (Settings.demoMode) receiver.startDemo()
        mapView.onResume()
        prevT = System.nanoTime()
        ui.post(refresh)
    }

    override fun onStop() {
        ui.removeCallbacks(refresh)
        if (receiver.recorder != null) toggleRecord()
        slam.status(st)
        if (mapping()) slam.stop()   // map is kept; only start() resets
        mapView.onPause()
        receiver.stopDemo()
        receiver.stop()
        slam.status(st)
        updatePhase()
        super.onStop()
    }

    override fun onDestroy() {
        receiver.packetSink = null
        slam.release()
        super.onDestroy()
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (tick++ % 5 == 0) updateStatus()
            ui.postDelayed(this, 100)
        }
    }

    // ------------------------------------------------------------------ status

    private fun updatePhase() {
        val running = mapping()
        val loc = st[SlamNative.S.LOC_STATE].toInt()
        val (label, color) = when {
            !running && st[SlamNative.S.SCANS] > 0 -> R.string.phase_stopped to Ui.ACCENT
            !running -> (if (mode == 1) R.string.mode_loc else R.string.phase_live) to Ui.GREY
            st[SlamNative.S.STATUS].toInt() != 2 -> R.string.phase_init to Ui.ORANGE
            mode == 0 -> R.string.phase_mapping to Ui.GREEN
            loc == 2 -> R.string.phase_tracking to Ui.GREEN
            loc == 3 -> R.string.phase_lost to Ui.RED
            else -> R.string.phase_search to Ui.ORANGE
        }
        phase.text = getString(label)
        phase.background = Ui.rounded(color, Ui.dp(this, 10f))
        mainBtn.text = getString(if (running) R.string.stop else if (mode == 1) R.string.start_loc else R.string.start_mapping)
        Ui.setButtonColor(mainBtn, if (running) Ui.RED else Ui.ACCENT)
        val canSave = mode == 0 && !running && st[SlamNative.S.VIS_SIZE] > 0
        saveBtn.isEnabled = canSave
        saveBtn.alpha = if (canSave) 1f else 0.4f
        Ui.setButtonColor(saveBtn, if (canSave) Ui.GREEN else Ui.GREY)
    }

    private fun updateStatus() {
        val now = System.nanoTime()
        val dt = (now - prevT) / 1e9
        val pk = receiver.pointPackets.get()
        val pt = receiver.pointCount.get()
        val im = receiver.imuPackets.get()
        val pps = (pk - prevPkts) / dt
        val ptps = (pt - prevPts) / dt
        val ips = (im - prevImu) / dt
        prevT = now; prevPkts = pk; prevPts = pt; prevImu = im
        slam.status(st)

        val ethUp = receiver.ethAddrs != "-"
        Ui.setChip(chipEth, ethUp, if (ethUp) "Ethernet" else getString(R.string.eth_none))
        val lidarName = if (receiver.demoRunning) getString(R.string.demo) else "Mid-360"
        Ui.setChip(chipLidar, pps > 100,
            if (pps > 100) getString(R.string.lidar_rate, lidarName, ptps / 1000) else getString(R.string.lidar_none))
        Ui.setChip(chipImu, ips > 50, "IMU %.0fHz".format(ips))
        updatePhase()

        val status = st[SlamNative.S.STATUS].toInt()
        val loc = st[SlamNative.S.LOC_STATE].toInt()
        headline.text = when {
            mapping() && status != 2 -> getString(R.string.hold_still)
            mode == 1 && mapping() -> when (loc) {
                2 -> getString(R.string.loc_match, st[SlamNative.S.MATCH] * 100,
                    st[SlamNative.S.PX], st[SlamNative.S.PY], Math.toDegrees(st[SlamNative.S.YAW]))
                3 -> getString(R.string.loc_lost)
                else -> getString(R.string.loc_matching)
            }
            mode == 1 -> getString(if (st[SlamNative.S.HAS_PRIOR] > 0.5) R.string.loc_ready else R.string.loc_choose)
            mapping() -> {
                val sec = ((System.currentTimeMillis() - mapStartMs) / 1000).toInt()
                getString(R.string.mapping_progress, sec / 60, sec % 60, "%,d".format(st[SlamNative.S.VIS_SIZE].toLong()))
            }
            st[SlamNative.S.SCANS] > 0 -> getString(R.string.can_save, "%,d".format(st[SlamNative.S.VIS_SIZE].toLong()))
            receiver.demoRunning -> getString(R.string.demo_press_start)
            pps <= 100 -> getString(R.string.waiting_lidar)
            else -> getString(R.string.waiting)
        }
        receiver.recorder?.let { recBtn.text = getString(R.string.record_size, receiver.recordedBytes.get() / 1e6) }

        details.text = buildString {
            appendLine("${receiver.ethState}${receiver.bindNote}")
            appendLine(receiver.ethAddrs)
            appendLine("LiDAR ${receiver.lidarInfo}  from ${receiver.lidarSrc}")
            appendLine("pkt %.0f/s  pts %.0f/s  lost %d".format(pps, ptps, receiver.lostPackets.get()))
            if (mode == 1) appendLine("NDT %.1f ms  match %.2f  state %d".format(
                st[SlamNative.S.NDT_MS], st[SlamNative.S.MATCH], st[SlamNative.S.LOC_STATE].toInt()))
            append("SLAM %.1f ms/scan (avg %.1f)  eff %d/%d\npos (%.2f, %.2f, %.2f)  yaw %.0f°\nscans %d  drop scan %d pkt %d  q %d".format(
                st[SlamNative.S.LAST_MS], st[SlamNative.S.AVG_MS],
                st[SlamNative.S.EFFECTIVE].toInt(), st[SlamNative.S.DS_POINTS].toInt(),
                st[SlamNative.S.PX], st[SlamNative.S.PY], st[SlamNative.S.PZ], Math.toDegrees(st[SlamNative.S.YAW]),
                st[SlamNative.S.SCANS].toLong(), st[SlamNative.S.DROPPED_SCANS].toLong(),
                st[SlamNative.S.DROPPED_PKTS].toLong(), st[SlamNative.S.QUEUE].toInt()))
        }
        Log.i(LidarReceiver.TAG, "${headline.text} | ${details.text.toString().replace("\n", " | ")}")
    }
}

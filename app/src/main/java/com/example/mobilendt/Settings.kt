package com.example.mobilendt

import android.content.Context
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** User settings, stored in SharedPreferences. */
object Settings {
    /** Values match the colour modes of [GlPoints.draw]. */
    const val COLOR_HEIGHT = 0
    const val COLOR_INTENSITY = 2

    const val DEFAULT_BLIND = 1.5f
    const val MAX_BLIND = 5.0f

    private const val FILE = "settings"
    private const val KEY_BLIND = "blind_m"
    private const val KEY_COLOR = "color_mode"
    private const val KEY_RECORD_FORMAT = "record_format"
    private const val KEY_PLAY_SPEED = "bag_play_speed"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Points closer than this (m) are ignored by SLAM / localisation. */
    fun blind(c: Context): Float = prefs(c).getFloat(KEY_BLIND, DEFAULT_BLIND).coerceIn(0f, MAX_BLIND)
    fun setBlind(c: Context, m: Float) = prefs(c).edit().putFloat(KEY_BLIND, m.coerceIn(0f, MAX_BLIND)).apply()

    fun colorMode(c: Context): Int =
        if (prefs(c).getInt(KEY_COLOR, COLOR_INTENSITY) == COLOR_HEIGHT) COLOR_HEIGHT else COLOR_INTENSITY
    fun setColorMode(c: Context, mode: Int) = prefs(c).edit().putInt(KEY_COLOR, mode).apply()

    /**
     * Synthetic Mid-360 data instead of the real sensor (for trying the app without hardware).
     * Kept in memory only: every launch starts with the real sensor.
     */
    @Volatile var demoMode = false

    // ------------------------------------------------------------------ recording / playback

    enum class RecordFormat { ROSBAG, RAW }

    /** Format of the "Record" button (default: ROS 2 bag). */
    fun recordFormat(c: Context): RecordFormat =
        if (prefs(c).getString(KEY_RECORD_FORMAT, "rosbag") == "raw") RecordFormat.RAW else RecordFormat.ROSBAG
    fun setRecordFormat(c: Context, f: RecordFormat) =
        prefs(c).edit().putString(KEY_RECORD_FORMAT, if (f == RecordFormat.RAW) "raw" else "rosbag").apply()

    val PLAY_SPEEDS = doubleArrayOf(0.5, 1.0, 2.0)

    fun playSpeed(c: Context): Double {
        val v = prefs(c).getFloat(KEY_PLAY_SPEED, 1f).toDouble()
        return if (PLAY_SPEEDS.any { it == v }) v else 1.0
    }
    fun setPlaySpeed(c: Context, v: Double) = prefs(c).edit().putFloat(KEY_PLAY_SPEED, v.toFloat()).apply()

    // ------------------------------------------------------------------ SLAM parameters

    /** Defaults of the SLAM core, with the app's own default for the near-range cut-off. */
    val defaultParams: FloatArray by lazy {
        SlamNative.defaultParams().also { it[SlamNative.P.BLIND] = DEFAULT_BLIND }
    }

    /** Stored as "slam.<key>"; blind keeps its old key. */
    private fun paramKey(key: String) = "slam.$key"

    fun value(c: Context, p: SlamParam): Double {
        val sp = prefs(c)
        if (!sp.contains(paramKey(p.key))) return p.default
        return sp.getFloat(paramKey(p.key), p.default.toFloat()).toDouble().coerceIn(p.min, p.max)
    }

    fun setValue(c: Context, p: SlamParam, v: Double) =
        prefs(c).edit().putFloat(paramKey(p.key), v.coerceIn(p.min, p.max).toFloat()).apply()

    /** Forgets all tuned values (blind included). */
    fun resetParams(c: Context) {
        val e = prefs(c).edit()
        for (p in SlamParam.ALL) e.remove(paramKey(p.key))
        e.putFloat(KEY_BLIND, DEFAULT_BLIND)
        e.apply()
    }

    /** Current parameters for the SLAM core ([SlamNative.setParams]). */
    fun slamParams(c: Context): FloatArray {
        val out = defaultParams.copyOf()
        for (p in SlamParam.ALL) out[p.index] = value(c, p).toFloat()
        out[SlamNative.P.BLIND] = blind(c)
        return out
    }
}

/** One tunable SLAM parameter of the advanced settings screen. */
class SlamParam(
    val key: String,
    val index: Int,
    val group: Group,
    val title: Int,
    val note: Int,
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: String,
    val digits: Int,
    /** The slider moves in log10 space (noise parameters spanning decades). */
    val log: Boolean = false,
) {
    enum class Group { SCAN, OPTIMIZER, NOISE, EXTRINSIC, LOCALIZATION }

    val default: Double get() = Settings.defaultParams[index].toDouble()

    fun format(v: Double): String {
        val s = if (log) "%.0e".format(java.util.Locale.US, v) else "%.${digits}f".format(java.util.Locale.US, v)
        return if (unit.isEmpty()) s else "$s $unit"
    }

    fun isDefault(v: Double): Boolean = abs(v - default) < maxOf(step, abs(v) * 1e-3) / 2

    /** Slider resolution. */
    val sliderSteps: Int get() = if (log) 400 else ((max - min) / step).roundToInt()

    fun toSlider(v: Double): Int {
        val f = if (log) (log10(v) - log10(min)) / (log10(max) - log10(min)) else (v - min) / (max - min)
        return (f.coerceIn(0.0, 1.0) * sliderSteps).roundToInt()
    }

    fun fromSlider(pos: Int): Double {
        val f = pos.toDouble() / sliderSteps
        if (!log) return (min + f * (max - min)).coerceIn(min, max)
        val v = 10.0.pow(log10(min) + f * (log10(max) - log10(min)))
        // one significant digit, so the label and the stored value agree
        val e = floor(log10(v))
        return ((v / 10.0.pow(e)).roundToInt() * 10.0.pow(e)).coerceIn(min, max)
    }

    companion object {
        val ALL = listOf(
            SlamParam("max_range", SlamNative.P.MAX_RANGE, Group.SCAN, R.string.p_max_range, R.string.p_max_range_note,
                10.0, 200.0, 5.0, "m", 0),
            SlamParam("scan_ds", SlamNative.P.SCAN_DS, Group.SCAN, R.string.p_scan_ds, R.string.p_scan_ds_note,
                0.05, 1.0, 0.05, "m", 2),
            SlamParam("map_res", SlamNative.P.MAP_RES, Group.SCAN, R.string.p_map_res, R.string.p_map_res_note,
                0.05, 1.0, 0.05, "m", 2),
            SlamParam("vis_res", SlamNative.P.VIS_RES, Group.SCAN, R.string.p_vis_res, R.string.p_vis_res_note,
                0.01, 0.5, 0.01, "m", 2),
            SlamParam("max_iter", SlamNative.P.MAX_ITER, Group.OPTIMIZER, R.string.p_max_iter, R.string.p_max_iter_note,
                1.0, 10.0, 1.0, "", 0),
            SlamParam("plane_thresh", SlamNative.P.PLANE_THRESH, Group.OPTIMIZER, R.string.p_plane_thresh,
                R.string.p_plane_thresh_note, 0.02, 0.5, 0.01, "m", 2),
            SlamParam("knn_max_d2", SlamNative.P.KNN_MAX_D2, Group.OPTIMIZER, R.string.p_knn_max_d2,
                R.string.p_knn_max_d2_note, 0.5, 20.0, 0.5, "m²", 1),
            SlamParam("gyr_cov", SlamNative.P.GYR_COV, Group.NOISE, R.string.p_gyr_cov, 0,
                1e-4, 10.0, 0.0, "", 0, log = true),
            SlamParam("acc_cov", SlamNative.P.ACC_COV, Group.NOISE, R.string.p_acc_cov, 0,
                1e-4, 10.0, 0.0, "", 0, log = true),
            SlamParam("laser_cov", SlamNative.P.LASER_COV, Group.NOISE, R.string.p_laser_cov, R.string.p_laser_cov_note,
                1e-5, 1e-1, 0.0, "", 0, log = true),
            SlamParam("ext_x", SlamNative.P.EXT_X, Group.EXTRINSIC, R.string.p_ext_x, 0, -0.3, 0.3, 0.001, "m", 3),
            SlamParam("ext_y", SlamNative.P.EXT_Y, Group.EXTRINSIC, R.string.p_ext_y, 0, -0.3, 0.3, 0.001, "m", 3),
            SlamParam("ext_z", SlamNative.P.EXT_Z, Group.EXTRINSIC, R.string.p_ext_z, 0, -0.3, 0.3, 0.001, "m", 3),
            SlamParam("ndt_res", SlamNative.P.NDT_RES, Group.LOCALIZATION, R.string.p_ndt_res, R.string.p_ndt_res_note,
                0.5, 3.0, 0.1, "m", 1),
            SlamParam("loc_match_ok", SlamNative.P.LOC_MATCH_OK, Group.LOCALIZATION, R.string.p_loc_match_ok,
                R.string.p_loc_match_ok_note, 0.1, 0.9, 0.05, "", 2),
            SlamParam("loc_init_radius", SlamNative.P.LOC_INIT_RADIUS, Group.LOCALIZATION, R.string.p_loc_init_radius,
                R.string.p_loc_init_radius_note, 0.0, 5.0, 0.5, "m", 1),
        )
    }
}

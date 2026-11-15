package com.example.mobilendt

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lists PCD maps saved in Download/MobileNDT and opens them in [PcdViewActivity]. */
class PcdListActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private val pick by lazy { intent.getBooleanExtra(EXTRA_PICK, false) }

    private data class Item(val uri: Uri, val name: String, val size: Long, val modified: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = this
        val pad = Ui.dpi(c, 16f)

        val back = Ui.button(c, "←", Ui.GREY, radius = 22f, textSize = 18f).apply { setOnClickListener { finish() } }
        val title = Ui.text(c, 22f, bold = true).apply { text = getString(if (pick) R.string.pick_map_title else R.string.saved_maps) }
        val open = Ui.button(c, getString(R.string.open_file), Ui.ACCENT, radius = 22f, textSize = 13f).apply {
            setPadding(Ui.dpi(c, 14f), 0, Ui.dpi(c, 14f), 0)
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQ_OPEN)
            }
        }
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(Ui.dpi(c, 44f), Ui.dpi(c, 44f)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(c, 12f) })
            addView(open, LinearLayout.LayoutParams(-2, Ui.dpi(c, 44f)))
        }
        val sub = Ui.text(c, 12f, Ui.TEXT_DIM).apply {
            text = getString(if (pick) R.string.list_sub_pick else R.string.list_sub)
        }
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        empty = Ui.text(c, 14f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.list_empty)
            setPadding(0, Ui.dpi(c, 48f), 0, 0)
        }
        val content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(header)
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(c, 8f); bottomMargin = Ui.dpi(c, 12f) })
            addView(list)
            addView(empty)
        }
        val root = ScrollView(c).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(Ui.capWidth(c, content, 700f))
            Ui.padForSystemBars(this)
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun query(): List<Item> {
        val out = mutableListOf<Item>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        contentResolver.query(coll, proj, sel, arrayOf("Download/MobileNDT%", "%.pcd"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cur ->
            while (cur.moveToNext()) {
                out += Item(ContentUris.withAppendedId(coll, cur.getLong(0)), cur.getString(1),
                    cur.getLong(2), cur.getLong(3) * 1000)
            }
        }
        return out
    }

    private fun refresh() {
        val items = try { query() } catch (e: Exception) { emptyList() }
        list.removeAllViews()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        for (it in items) list.addView(row(it, fmt), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dpi(this@PcdListActivity, 10f)
        })
    }

    private fun row(item: Item, fmt: SimpleDateFormat): View {
        val c = this
        val icon = Ui.text(c, 20f, Ui.ACCENT, bold = true).apply {
            text = "◉"
            gravity = Gravity.CENTER
            background = Ui.rounded(0x223D8BFF, Ui.dp(c, 12f))
        }
        val name = Ui.text(c, 15f, bold = true).apply {
            text = item.name
            isSingleLine = true
        }
        val meta = Ui.text(c, 12f, Ui.TEXT_DIM).apply {
            text = getString(R.string.list_meta, fmt.format(Date(item.modified)), item.size / 1e6, "%,d".format(item.size / 12))
        }
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(meta)
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.card(c)
            setPadding(Ui.dpi(c, 12f), Ui.dpi(c, 12f), Ui.dpi(c, 12f), Ui.dpi(c, 12f))
            addView(icon, LinearLayout.LayoutParams(Ui.dpi(c, 44f), Ui.dpi(c, 44f)))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dpi(c, 12f) })
            isClickable = true
            setOnClickListener { openViewer(item.uri, item.name) }
            if (!pick) setOnLongClickListener {
                confirmDelete(item)
                true
            }
        }
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(item.name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val ok = try { contentResolver.delete(item.uri, null, null) > 0 } catch (e: Exception) { false }
                Toast.makeText(this, if (ok) R.string.deleted else R.string.delete_failed, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    private fun openViewer(uri: Uri, name: String) {
        if (pick) {
            setResult(RESULT_OK, Intent().apply {
                data = uri
                putExtra(PcdViewActivity.EXTRA_NAME, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            finish()
            return
        }
        startActivity(Intent(this, PcdViewActivity::class.java).apply {
            data = uri
            putExtra(PcdViewActivity.EXTRA_NAME, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    @Deprecated("startActivityForResult is fine for this single picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val name = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "PCD"
            openViewer(uri, name)
        }
    }

    companion object {
        private const val REQ_OPEN = 1
        /** true: return the chosen map (data = Uri, extra name) instead of opening the viewer. */
        const val EXTRA_PICK = "pick"
    }
}

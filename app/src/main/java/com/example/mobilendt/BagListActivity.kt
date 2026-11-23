package com.example.mobilendt

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Lists ROS 2 bags (MCAP) to run SLAM on: recordings of the Record button and imported files.
 * Tapping one returns its path (EXTRA_PATH) to the caller.
 */
class BagListActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var foot: TextView
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val open = Ui.smallButton(c, getString(R.string.open_file)) {
            if (importing) return@smallButton
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQ_OPEN)
        }
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        empty = Ui.text(c, 14f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.bag_list_empty)
            setPadding(0, Ui.dpi(c, 48f), 0, 0)
        }
        foot = Ui.note(c, getString(R.string.bag_list_note, packageName))
        val column = Ui.column(c).apply {
            addView(Ui.topBar(c, getString(R.string.bag_pick_title), open))
            addView(list, Ui.below(c, 14f))
            addView(empty)
            addView(foot, Ui.below(c, 4f))
        }
        setContentView(Ui.screen(c, column))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = AppFiles.listBags(this)
        list.removeAllViews()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        foot.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        for (f in items) {
            val row = Ui.listRow(this, "▶", f.name,
                getString(R.string.bag_meta, fmt.format(Date(f.lastModified())), f.length() / 1e6)).apply {
                setOnClickListener { select(f) }
                setOnLongClickListener {
                    showActions(f)
                    true
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(this@BagListActivity, 10f) })
        }
    }

    private fun select(f: File) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, f.absolutePath))
        finish()
    }

    private fun showActions(f: File) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf(getString(R.string.share), getString(R.string.sftp_send), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> share(f)
                    1 -> SftpSendActivity.start(this, f)
                    else -> confirmDelete(f)
                }
            }
            .show()
    }

    private fun share(f: File) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, FileShareProvider.uriFor(this@BagListActivity, f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_bag)))
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_bag_title)
            .setMessage(f.name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                Toast.makeText(this, if (f.delete()) R.string.deleted else R.string.delete_failed, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
    }

    @Deprecated("startActivityForResult is fine for this single picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importing = true
        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
        thread(name = "bag-import") {
            val r = runCatching { AppFiles.importFile(this, uri) }
            runOnUiThread {
                importing = false
                r.onSuccess { select(it) }
                    .onFailure { Toast.makeText(this, getString(R.string.import_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }
    }

    companion object {
        private const val REQ_OPEN = 1
        /** Absolute path of the chosen bag. */
        const val EXTRA_PATH = "path"
    }
}

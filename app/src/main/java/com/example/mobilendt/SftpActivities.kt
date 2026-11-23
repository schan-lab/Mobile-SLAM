package com.example.mobilendt

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** SFTP settings: the app's key pair and the saved destinations. */
class SftpSettingsActivity : Activity() {

    private lateinit var keyCard: LinearLayout
    private lateinit var hostList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        keyCard = Ui.section(c, getString(R.string.sftp_keypair))
        hostList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val hostCard = Ui.section(c, getString(R.string.sftp_destinations)).apply {
            addView(hostList)
            addView(Ui.smallButton(c, "+  " + getString(R.string.sftp_add_host)) { SftpHostEditActivity.start(c, null) },
                LinearLayout.LayoutParams(-2, Ui.dpi(c, 40f)).apply { topMargin = Ui.dpi(c, 6f) })
            addView(Ui.note(c, getString(R.string.sftp_hosts_note)), Ui.below(c, 8f))
        }
        val column = Ui.column(c).apply {
            addView(Ui.topBar(c, "SFTP"))
            addView(keyCard, Ui.below(c, 14f))
            addView(hostCard, Ui.below(c, 12f))
        }
        setContentView(Ui.screen(c, column))
    }

    override fun onResume() {
        super.onResume()
        showKey(copied = false)
        showHosts()
    }

    private fun showKey(copied: Boolean) {
        val c = this
        while (keyCard.childCount > 1) keyCard.removeViewAt(1)
        val fp = SshKey.fingerprint(c)
        if (fp == null) {
            keyCard.addView(Ui.smallButton(c, getString(R.string.sftp_create_key)) {
                SshKey.generate(c)
                showKey(copied = false)
            }, LinearLayout.LayoutParams(-2, Ui.dpi(c, 40f)))
        } else {
            keyCard.addView(Ui.text(c, 15f, bold = true).apply { text = "Ed25519" })
            keyCard.addView(Ui.text(c, 11f, Ui.TEXT_DIM).apply {
                text = fp
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.smallButton(c, getString(if (copied) R.string.sftp_copied else R.string.sftp_copy_public_key),
                    if (copied) Ui.GREEN else Ui.ACCENT) { copyPublicKey() }, LinearLayout.LayoutParams(-2, Ui.dpi(c, 40f)))
                addView(Ui.smallButton(c, getString(R.string.sftp_regenerate), Ui.GREY) { confirmRegenerate() },
                    LinearLayout.LayoutParams(-2, Ui.dpi(c, 40f)).apply { marginStart = Ui.dpi(c, 8f) })
            }
            keyCard.addView(row, Ui.below(c, 10f))
        }
        keyCard.addView(Ui.note(c, getString(R.string.sftp_key_note)), Ui.below(c, 8f))
    }

    private fun copyPublicKey() {
        val line = SshKey.publicKeyLine(this) ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ssh public key", line))
        showKey(copied = true)
    }

    private fun confirmRegenerate() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sftp_regenerate_title)
            .setMessage(R.string.sftp_regenerate_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sftp_regenerate_ok) { _, _ ->
                SshKey.generate(this)
                showKey(copied = false)
            }
            .show()
    }

    private fun showHosts() {
        hostList.removeAllViews()
        for (h in SftpHostStore.load(this)) {
            val row = hostRow(this, h).apply {
                setOnClickListener { SftpHostEditActivity.start(this@SftpSettingsActivity, h.id) }
                setOnLongClickListener {
                    confirmDelete(h)
                    true
                }
            }
            hostList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(this@SftpSettingsActivity, 6f) })
        }
    }

    private fun confirmDelete(h: SftpHost) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sftp_delete_host_title)
            .setMessage(h.title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                SftpHostStore.delete(this, h)
                showHosts()
            }
            .show()
    }

    companion object {
        /** Title and user@host:port of a destination. */
        fun hostRow(c: Context, h: SftpHost, trailing: String? = null) = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(0x14FFFFFF, Ui.dp(c, 12f))
            setPadding(Ui.dpi(c, 12f), Ui.dpi(c, 10f), Ui.dpi(c, 12f), Ui.dpi(c, 10f))
            val texts = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(c, 15f, bold = true).apply {
                    text = h.title
                    isSingleLine = true
                })
                addView(Ui.text(c, 12f, Ui.TEXT_DIM).apply {
                    text = h.summary
                    typeface = android.graphics.Typeface.MONOSPACE
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (trailing != null) addView(Ui.text(c, 20f, Ui.ACCENT, bold = true).apply { text = trailing })
            isClickable = true
        }
    }
}

/** Adds (no EXTRA_ID) or edits one destination. */
class SftpHostEditActivity : Activity() {

    private lateinit var host: SftpHost
    private var name = ""
    private var address = ""
    private var port = "22"
    private var user = ""
    private var remoteDir = ""
    private var usePassword = false
    private var password = ""
    private var hostKey: String? = null

    private lateinit var saveBtn: TextView
    private lateinit var keyBtn: TextView
    private lateinit var pwBtn: TextView
    private lateinit var pwRow: View
    private lateinit var authNote: TextView
    private lateinit var hostKeyCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        host = intent.getStringExtra(EXTRA_ID)?.let { SftpHostStore.find(c, it) } ?: SftpHost()
        name = host.name
        address = host.host
        port = host.port.toString()
        user = host.user
        remoteDir = host.remoteDir
        usePassword = host.usePassword
        password = SftpHostStore.password(c, host) ?: ""
        hostKey = host.hostKey

        saveBtn = Ui.smallButton(c, getString(R.string.save)) { save() }
        val title = getString(if (host.host.isEmpty()) R.string.sftp_add_host else R.string.sftp_edit_host)

        val ascii = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val server = Ui.section(c, getString(R.string.sftp_server)).apply {
            addView(Ui.labeled(c, getString(R.string.sftp_name), Ui.field(c, name, getString(R.string.sftp_name_hint),
                InputType.TYPE_CLASS_TEXT) {
                name = it
                refresh()
            }))
            addView(Ui.labeled(c, getString(R.string.sftp_host), Ui.field(c, address, "192.168.1.10", ascii) {
                address = it
                refresh()
            }), Ui.below(c, 8f))
            addView(Ui.labeled(c, getString(R.string.sftp_port), Ui.field(c, port, "22", InputType.TYPE_CLASS_NUMBER) {
                port = it
                refresh()
            }), Ui.below(c, 8f))
            addView(Ui.labeled(c, getString(R.string.sftp_user), Ui.field(c, user, "ubuntu", ascii) {
                user = it
                refresh()
            }), Ui.below(c, 8f))
            addView(Ui.labeled(c, getString(R.string.sftp_path), Ui.field(c, remoteDir, "data/mobile_slam", ascii) {
                remoteDir = it
                refresh()
            }), Ui.below(c, 8f))
            addView(Ui.note(c, getString(R.string.sftp_path_note)), Ui.below(c, 8f))
        }

        keyBtn = Ui.button(c, getString(R.string.sftp_auth_key), Ui.GREY, radius = 18f, textSize = 14f).apply {
            setOnClickListener {
                usePassword = false
                refresh()
            }
        }
        pwBtn = Ui.button(c, getString(R.string.sftp_auth_password), Ui.GREY, radius = 18f, textSize = 14f).apply {
            setOnClickListener {
                usePassword = true
                refresh()
            }
        }
        val authRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(keyBtn, LinearLayout.LayoutParams(0, Ui.dpi(c, 40f), 1f))
            addView(pwBtn, LinearLayout.LayoutParams(0, Ui.dpi(c, 40f), 1f).apply { marginStart = Ui.dpi(c, 8f) })
        }
        val pwField: EditText = Ui.field(c, password, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            password = it
        }
        pwRow = Ui.labeled(c, getString(R.string.sftp_auth_password), pwField)
        authNote = Ui.note(c, "")
        val auth = Ui.section(c, getString(R.string.sftp_auth)).apply {
            addView(authRow)
            addView(pwRow, Ui.below(c, 8f))
            addView(authNote, Ui.below(c, 8f))
        }

        hostKeyCard = Ui.section(c, getString(R.string.sftp_host_key)).apply {
            addView(Ui.text(c, 11f, Ui.TEXT_DIM).apply {
                text = hostKey ?: ""
                typeface = android.graphics.Typeface.MONOSPACE
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextIsSelectable(true)
            })
            addView(Ui.smallButton(c, getString(R.string.sftp_reset_host_key), Ui.RED) {
                hostKey = null
                refresh()
            }, LinearLayout.LayoutParams(-2, Ui.dpi(c, 36f)).apply { topMargin = Ui.dpi(c, 8f) })
            addView(Ui.note(c, getString(R.string.sftp_host_key_note)), Ui.below(c, 8f))
        }

        val column = Ui.column(c).apply {
            addView(Ui.topBar(c, title, saveBtn))
            addView(server, Ui.below(c, 14f))
            addView(auth, Ui.below(c, 12f))
            addView(hostKeyCard, Ui.below(c, 12f))
        }
        setContentView(Ui.screen(c, column))
        refresh()
    }

    private fun valid(): Boolean =
        address.isNotBlank() && user.isNotBlank() && (port.trim().toIntOrNull() ?: 0) in 1..65535

    private fun refresh() {
        val ok = valid()
        saveBtn.isEnabled = ok
        saveBtn.alpha = if (ok) 1f else 0.4f
        Ui.setButtonColor(keyBtn, if (usePassword) Ui.GREY else Ui.ACCENT, 18f)
        Ui.setButtonColor(pwBtn, if (usePassword) Ui.ACCENT else Ui.GREY, 18f)
        pwRow.visibility = if (usePassword) View.VISIBLE else View.GONE
        authNote.text = getString(when {
            usePassword -> R.string.sftp_password_note
            SshKey.exists(this) -> R.string.sftp_key_registered_note
            else -> R.string.sftp_key_missing_note
        })
        hostKeyCard.visibility = if (hostKey == null) View.GONE else View.VISIBLE
    }

    private fun save() {
        if (!valid()) return
        val h = host.copy(name = name.trim(), host = address.trim(), port = port.trim().toInt(), user = user.trim(),
            remoteDir = remoteDir.trim(), usePassword = usePassword, hostKey = hostKey)
        SftpHostStore.upsert(this, h)
        SftpHostStore.setPassword(this, h, if (usePassword) password else "")
        finish()
    }

    companion object {
        private const val EXTRA_ID = "id"

        fun start(a: Activity, id: String?) {
            a.startActivity(Intent(a, SftpHostEditActivity::class.java).apply { if (id != null) putExtra(EXTRA_ID, id) })
        }
    }
}

/** Picks a destination and uploads one file (a file path, or a content Uri with name and size). */
class SftpSendActivity : Activity() {

    private lateinit var src: UploadSource
    private lateinit var hostList: LinearLayout
    private lateinit var status: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusBar: ProgressBar
    private lateinit var statusBytes: TextView
    private lateinit var emptyNote: TextView
    private lateinit var closeBtn: TextView
    @Volatile private var busy = false
    @Volatile private var cancel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val path = intent.getStringExtra(EXTRA_PATH)
        val uri = intent.data
        src = when {
            path != null -> File(path).let { f -> UploadSource(f.name, f.length()) { f.inputStream() } }
            uri != null -> UploadSource(intent.getStringExtra(EXTRA_NAME) ?: AppFiles.displayName(c, uri),
                intent.getLongExtra(EXTRA_SIZE, 0)) { contentResolver.openInputStream(uri)!! }
            else -> {
                finish()
                return
            }
        }

        closeBtn = Ui.smallButton(c, getString(R.string.close), Ui.GREY) { onBackPressed() }
        val file = Ui.listRow(c, "↑", src.name, "%.1f MB".format(src.size / 1e6)).apply { isClickable = false }

        statusText = Ui.text(c, 14f, bold = true)
        statusBar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        statusBytes = Ui.text(c, 12f, Ui.TEXT_DIM).apply { typeface = android.graphics.Typeface.MONOSPACE }
        status = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(c)
            val p = Ui.dpi(c, 14f)
            setPadding(p, p, p, p)
            addView(statusText)
            addView(statusBar, Ui.below(c, 6f))
            addView(statusBytes, Ui.below(c, 2f))
            visibility = View.GONE
        }

        hostList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        emptyNote = Ui.note(c, getString(R.string.sftp_no_hosts))
        val hosts = Ui.section(c, getString(R.string.sftp_destinations)).apply {
            addView(hostList)
            addView(Ui.smallButton(c, "+  " + getString(R.string.sftp_add_host)) {
                if (!busy) SftpHostEditActivity.start(c, null)
            }, LinearLayout.LayoutParams(-2, Ui.dpi(c, 40f)).apply { topMargin = Ui.dpi(c, 6f) })
            addView(emptyNote, Ui.below(c, 8f))
        }

        val column = Ui.column(c).apply {
            addView(Ui.topBar(c, getString(R.string.sftp_send_title), closeBtn))
            addView(file, Ui.below(c, 14f))
            addView(status, Ui.below(c, 12f))
            addView(hosts, Ui.below(c, 12f))
        }
        setContentView(Ui.screen(c, column))
    }

    override fun onResume() {
        super.onResume()
        showHosts()
    }

    private fun showHosts() {
        hostList.removeAllViews()
        val all = SftpHostStore.load(this)
        emptyNote.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        for (h in all) {
            val row = SftpSettingsActivity.hostRow(this, h, "↑").apply {
                setOnClickListener { send(h) }
                alpha = if (busy) 0.5f else 1f
            }
            hostList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(this@SftpSendActivity, 6f) })
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        closeBtn.text = getString(if (b) R.string.sftp_abort else R.string.close)
        Ui.setButtonColor(closeBtn, if (b) Ui.RED else Ui.GREY, 20f)
        for (i in 0 until hostList.childCount) hostList.getChildAt(i).alpha = if (b) 0.5f else 1f
    }

    private fun showStatus(text: String, color: Int, done: Long?, total: Long) {
        status.visibility = View.VISIBLE
        statusText.text = text
        statusText.setTextColor(color)
        statusBar.visibility = if (done != null) View.VISIBLE else View.GONE
        statusBytes.visibility = statusBar.visibility
        if (done != null) {
            statusBar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
            statusBytes.text = "%.1f / %.1f MB".format(done / 1e6, total / 1e6)
        }
    }

    private fun send(h: SftpHost) {
        if (busy) return
        cancel = false
        setBusy(true)
        showStatus(getString(R.string.sftp_sending, h.title), Ui.TEXT, 0, src.size)
        thread(name = "sftp") {
            var last = 0L
            val r = runCatching {
                SftpUploader.upload(this, src, h, { cancel }) { done, total ->
                    val now = System.nanoTime()
                    if (now - last > 100_000_000L || done >= total) {
                        last = now
                        runOnUiThread { if (busy) showStatus(getString(R.string.sftp_sending, h.title), Ui.TEXT, done, total) }
                    }
                }
            }
            runOnUiThread {
                setBusy(false)
                r.onSuccess { res ->
                    res.newHostKey?.let { k -> SftpHostStore.find(this, h.id)?.let { SftpHostStore.upsert(this, it.copy(hostKey = k)) } }
                    showStatus("✓ " + getString(R.string.sftp_sent, res.remotePath), Ui.GREEN, null, 0)
                }.onFailure { e ->
                    val msg = if (e is InterruptedException) getString(R.string.sftp_aborted)
                              else getString(R.string.sftp_failed, e.message ?: e.toString())
                    showStatus("⚠ $msg", Ui.RED, null, 0)
                }
                showHosts()
            }
        }
    }

    @Deprecated("Back aborts a running upload first")
    override fun onBackPressed() {
        if (busy) {
            cancel = true
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        cancel = true
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"

        fun start(a: Activity, f: File) {
            a.startActivity(Intent(a, SftpSendActivity::class.java).putExtra(EXTRA_PATH, f.absolutePath))
        }

        fun start(a: Activity, uri: Uri, name: String, size: Long) {
            a.startActivity(Intent(a, SftpSendActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SIZE, size)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }
}

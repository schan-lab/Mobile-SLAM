package com.example.mobilendt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ------------------------------------------------------------------ saved destinations

/** One SFTP destination. Passwords are kept encrypted in [Secrets], not here. */
data class SftpHost(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    /** Remote directory; relative paths start at the login directory. Empty = login directory. */
    val remoteDir: String = "",
    val usePassword: Boolean = false,
    /** Host key accepted on the first connection ("type base64"). A different key is refused. */
    val hostKey: String? = null,
) {
    val title: String get() = name.ifEmpty { "$user@$host" }
    val summary: String get() = "$user@$host:$port" + if (remoteDir.isEmpty()) "" else "  $remoteDir"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("user", user)
        put("remoteDir", remoteDir)
        put("auth", if (usePassword) "password" else "key")
        if (hostKey != null) put("hostKey", hostKey)
    }

    companion object {
        fun fromJson(o: JSONObject) = SftpHost(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 22),
            user = o.optString("user"),
            remoteDir = o.optString("remoteDir"),
            usePassword = o.optString("auth") == "password",
            hostKey = if (o.has("hostKey")) o.getString("hostKey") else null,
        )
    }
}

/** Saved destinations (SharedPreferences) and their passwords ([Secrets]). */
object SftpHostStore {
    private const val FILE = "sftp"
    private const val KEY = "hosts"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): List<SftpHost> {
        val s = prefs(c).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(s)
            List(a.length()) { SftpHost.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(c: Context, hosts: List<SftpHost>) {
        val a = JSONArray()
        hosts.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString(KEY, a.toString()).apply()
    }

    fun find(c: Context, id: String): SftpHost? = load(c).firstOrNull { it.id == id }

    fun upsert(c: Context, h: SftpHost) {
        val all = load(c).toMutableList()
        val i = all.indexOfFirst { it.id == h.id }
        if (i >= 0) all[i] = h else all += h
        save(c, all)
    }

    fun delete(c: Context, h: SftpHost) {
        save(c, load(c).filter { it.id != h.id })
        Secrets.remove(c, "password.${h.id}")
    }

    fun password(c: Context, h: SftpHost): String? = Secrets.get(c, "password.${h.id}")?.toString(Charsets.UTF_8)

    fun setPassword(c: Context, h: SftpHost, pw: String) {
        if (pw.isEmpty()) {
            Secrets.remove(c, "password.${h.id}")
        } else {
            Secrets.put(c, "password.${h.id}", pw.toByteArray(Charsets.UTF_8))
        }
    }
}

/**
 * Small secrets of this app (SSH private key, passwords), encrypted with an AES key that never leaves the
 * Android Keystore. The ciphertext is in app-private SharedPreferences, excluded from backups.
 */
object Secrets {
    private const val FILE = "secrets"
    private const val ALIAS = "mobileslam.secrets"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun put(c: Context, name: String, data: ByteArray): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(data)
        val blob = ByteArray(1 + iv.size + ct.size)
        blob[0] = iv.size.toByte()
        iv.copyInto(blob, 1)
        ct.copyInto(blob, 1 + iv.size)
        prefs(c).edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    fun get(c: Context, name: String): ByteArray? {
        val s = prefs(c).getString(name, null) ?: return null
        return runCatching {
            val blob = Base64.decode(s, Base64.NO_WRAP)
            val n = blob[0].toInt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 1, n))
            cipher.doFinal(blob, 1 + n, blob.size - 1 - n)
        }.getOrNull()
    }

    fun remove(c: Context, name: String) {
        prefs(c).edit().remove(name).apply()
    }
}

// ------------------------------------------------------------------ key pair

/** The app's SSH key pair (Ed25519). The private key is stored only encrypted on this device. */
object SshKey {
    private const val NAME = "ed25519"

    private fun privateKey(c: Context): Ed25519PrivateKeyParameters? =
        Secrets.get(c, NAME)?.takeIf { it.size == 32 }?.let { Ed25519PrivateKeyParameters(it, 0) }

    fun exists(c: Context): Boolean = privateKey(c) != null

    /** Creates a new key pair, replacing the old one (servers must be given the new public key). */
    fun generate(c: Context): Boolean = Secrets.put(c, NAME, Ed25519PrivateKeyParameters(SecureRandom()).encoded)

    /** Public key line for ~/.ssh/authorized_keys. */
    fun publicKeyLine(c: Context): String? {
        val blob = publicBlob(c) ?: return null
        val device = if (c.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
        return "ssh-ed25519 ${Base64.encodeToString(blob, Base64.NO_WRAP)} mobileslam@android-$device"
    }

    /** SHA256 fingerprint as shown by ssh-keygen -l. */
    fun fingerprint(c: Context): String? {
        val blob = publicBlob(c) ?: return null
        val b64 = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(blob), Base64.NO_WRAP).trimEnd('=')
        return "SHA256:$b64"
    }

    private fun publicBlob(c: Context): ByteArray? {
        val k = privateKey(c) ?: return null
        return sshBlob(k.generatePublicKey().encoded)
    }

    private fun sshBlob(pub: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply {
            sshString("ssh-ed25519".toByteArray())
            sshString(pub)
        }
        return b.toByteArray()
    }

    private fun DataOutputStream.sshString(d: ByteArray) {
        writeInt(d.size)
        write(d)
    }

    /** Unencrypted OpenSSH private key file ("openssh-key-v1"), for JSch. Kept in memory only. */
    fun openSshPrivateKey(c: Context): ByteArray? {
        val k = privateKey(c) ?: return null
        val pub = k.generatePublicKey().encoded
        val blob = sshBlob(pub)
        val check = SecureRandom().nextInt()
        val priv = ByteArrayOutputStream()
        DataOutputStream(priv).apply {
            writeInt(check)
            writeInt(check)
            sshString("ssh-ed25519".toByteArray())
            sshString(pub)
            sshString(k.encoded + pub)
            sshString("mobileslam".toByteArray())
            var pad = 1
            while (size() % 8 != 0) write(pad++)
        }
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            write("openssh-key-v1".toByteArray())
            write(0)
            sshString("none".toByteArray())
            sshString("none".toByteArray())
            sshString(ByteArray(0))
            writeInt(1)
            sshString(blob)
            sshString(priv.toByteArray())
        }
        val b64 = Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray()
    }
}

// ------------------------------------------------------------------ upload

class SftpError(message: String) : Exception(message)

/** A file to send: its name, size and a way to read it. */
class UploadSource(val name: String, val size: Long, val open: () -> InputStream)

/**
 * Accepts the pinned host key, or any key on the first connection (remembered by the caller afterwards).
 * With StrictHostKeyChecking=yes, JSch refuses the connection on [HostKeyRepository.CHANGED].
 */
private class HostKeyPin(private val pinned: String?) : HostKeyRepository {
    var seen: String? = null
        private set
    var changed = false
        private set

    override fun check(host: String?, key: ByteArray): Int {
        val s = keyString(key)
        seen = s
        if (pinned != null && normalize(pinned) != normalize(s)) {
            changed = true
            return HostKeyRepository.CHANGED
        }
        return HostKeyRepository.OK
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "mobileslam"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    companion object {
        /** "type base64" of an SSH public key blob. */
        fun keyString(blob: ByteArray): String {
            val n = ((blob[0].toInt() and 0xFF) shl 24) or ((blob[1].toInt() and 0xFF) shl 16) or
                ((blob[2].toInt() and 0xFF) shl 8) or (blob[3].toInt() and 0xFF)
            val type = if (n in 1..64 && blob.size >= 4 + n) String(blob, 4, n) else "ssh"
            return "$type ${Base64.encodeToString(blob, Base64.NO_WRAP)}"
        }

        /** "type base64" (drops a trailing comment). */
        fun normalize(k: String) = k.trim().split(Regex("\\s+")).take(2).joinToString(" ")
    }
}

object SftpUploader {
    class Result(val remotePath: String, /** Set on the first connection: the host key to remember. */ val newHostKey: String?)

    /**
     * Uploads the file to host.remoteDir (created if missing), overwriting a file of the same name.
     * A partly written file is removed when the transfer fails or is cancelled ([cancelled] is polled).
     */
    fun upload(c: Context, src: UploadSource, host: SftpHost, cancelled: () -> Boolean,
               progress: (Long, Long) -> Unit): Result {
        val jsch = JSch()
        if (host.usePassword) {
            if (SftpHostStore.password(c, host) == null) throw SftpError(c.getString(R.string.sftp_err_no_password))
        } else {
            val prv = SshKey.openSshPrivateKey(c) ?: throw SftpError(c.getString(R.string.sftp_err_no_key))
            jsch.addIdentity("mobileslam", prv, null, null)
        }
        val pin = HostKeyPin(host.hostKey)
        jsch.hostKeyRepository = pin
        val session = jsch.getSession(host.user, host.host, host.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (host.usePassword) "password,keyboard-interactive" else "publickey")
        if (host.usePassword) session.setPassword(SftpHostStore.password(c, host))
        try {
            try {
                session.connect(15_000)
            } catch (e: JSchException) {
                throw describe(c, e, pin)
            }
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15_000)
            val dir = host.remoteDir.trim()
            makeDirs(sftp, dir)
            val path = when {
                dir.isEmpty() -> src.name
                dir.endsWith("/") -> dir + src.name
                else -> "$dir/${src.name}"
            }
            var aborted = false
            val monitor = object : SftpProgressMonitor {
                var done = 0L
                override fun init(op: Int, s: String?, d: String?, max: Long) {}
                override fun count(n: Long): Boolean {
                    done += n
                    progress(done, src.size)
                    aborted = cancelled()
                    return !aborted
                }
                override fun end() {}
            }
            try {
                src.open().use { sftp.put(it, path, monitor, ChannelSftp.OVERWRITE) }
                if (aborted || cancelled()) throw InterruptedException()
            } catch (e: Exception) {
                runCatching { sftp.rm(path) }
                if (e is InterruptedException) throw e
                if (e is SftpException) throw SftpError(e.message ?: e.toString())
                throw e
            } finally {
                runCatching { sftp.disconnect() }
            }
            return Result(path, if (host.hostKey == null) pin.seen else null)
        } finally {
            session.disconnect()
        }
    }

    /** mkdir -p (errors for existing directories are ignored; a real problem shows up when writing the file). */
    private fun makeDirs(sftp: ChannelSftp, dir: String) {
        if (dir.isEmpty() || dir == "/" || dir == "~") return
        var cur = if (dir.startsWith("/")) "" else "."
        for (part in dir.split("/")) {
            if (part.isEmpty() || part == ".") continue
            cur += "/$part"
            runCatching { sftp.mkdir(cur) }
        }
    }

    private fun describe(c: Context, e: JSchException, pin: HostKeyPin): Exception {
        val msg = e.message ?: ""
        return when {
            pin.changed || e.javaClass.simpleName.contains("ChangedHostKey") ->
                SftpError(c.getString(R.string.sftp_err_host_key_changed))
            msg.contains("Auth fail", ignoreCase = true) || msg.contains("Auth cancel", ignoreCase = true) ->
                SftpError(c.getString(R.string.sftp_err_auth))
            else -> SftpError(e.cause?.message ?: msg.ifEmpty { e.toString() })
        }
    }
}

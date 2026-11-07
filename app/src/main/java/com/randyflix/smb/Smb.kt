package com.randyflix.smb

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.randyflix.Settings
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val created: Long,
    val changed: Long,
)

/**
 * One shared SMB connection to the Pi. Paths everywhere in the app look like
 * "share/sub/dir/file.ext" (forward slashes, share name first).
 */
object Smb {
    private val lock = Any()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val shares = HashMap<String, DiskShare>()

    fun reset() {
        synchronized(lock) {
            shares.values.forEach { runCatching { it.close() } }
            shares.clear()
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client?.close() }
            session = null
            connection = null
            client = null
        }
    }

    private fun ensureSession(): Session {
        val conn = connection
        val sess = session
        if (conn != null && conn.isConnected && sess != null) return sess
        reset()
        val config = SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
        val c = SMBClient(config)
        val newConn = c.connect(Settings.host)
        // Shares are open: guest first, anonymous as a fallback.
        val newSess = try {
            newConn.authenticate(AuthenticationContext.guest())
        } catch (e: Exception) {
            newConn.authenticate(AuthenticationContext.anonymous())
        }
        client = c
        connection = newConn
        session = newSess
        return newSess
    }

    private fun share(name: String): DiskShare {
        synchronized(lock) {
            val existing = shares[name]
            if (existing != null && existing.isConnected) return existing
            val s = ensureSession().connectShare(name) as? DiskShare
                ?: throw IOException("'$name' is not a disk share")
            shares[name] = s
            return s
        }
    }

    private fun split(path: String): Pair<String, String> {
        val clean = path.replace('\\', '/').trim('/')
        val shareName = clean.substringBefore('/')
        val rest = if (clean.contains('/')) clean.substringAfter('/') else ""
        return shareName to rest.replace('/', '\\')
    }

    /** Runs [block]; on a connection-level failure reconnects once and retries. */
    private fun <T> retry(block: () -> T): T = try {
        block()
    } catch (e: SMBApiException) {
        throw e // e.g. file not found / access denied: retrying won't help
    } catch (e: Exception) {
        reset()
        block()
    }

    fun list(path: String): List<SmbEntry> = retry {
        val (shareName, sub) = split(path)
        share(shareName).list(sub)
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                SmbEntry(
                    name = info.fileName,
                    isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                    size = info.endOfFile,
                    modified = info.lastWriteTime.toEpochMillis(),
                    created = info.creationTime.toEpochMillis(),
                    changed = info.changeTime.toEpochMillis(),
                )
            }
    }

    fun open(path: String): File = retry {
        val (shareName, sub) = split(path)
        share(shareName).openFile(
            sub,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java),
        )
    }

    fun readBytes(path: String): ByteArray = open(path).use { f ->
        f.inputStream.use { it.readBytes() }
    }

    /** smb://host/share/dir/file for the player. */
    fun uri(path: String): Uri {
        val b = Uri.Builder().scheme("smb").authority(Settings.host)
        path.replace('\\', '/').trim('/').split('/').forEach { b.appendPath(it) }
        return b.build()
    }

    fun pathFromUri(uri: Uri): String = uri.pathSegments.joinToString("/")
}

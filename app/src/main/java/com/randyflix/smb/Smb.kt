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
 *
 * A connection can go stale without anyone noticing (the Firestick slept, Wi-Fi dropped, the Pi
 * restarted). Requests on it then wait for the timeout and fail; the first failure drops the
 * connection and the request is retried on a fresh one. Dropping is a hard disconnect: politely
 * logging off a dead connection would wait out the timeout again for every share and session,
 * while holding the lock that every other request (posters, lists, video) is queued behind.
 */
object Smb {
    private val lock = Any()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val shares = HashMap<String, DiskShare>()

    /** Bumped on every new connection, so a failure only drops the connection it happened on. */
    @Volatile
    private var generation = 0

    /** Drops the connection (e.g. the server setting changed); the next request reconnects. */
    fun reset() {
        synchronized(lock) { disconnect() }
    }

    /** Drops the connection only if it's still the one that [gen] refers to. */
    private fun resetIf(gen: Int) {
        synchronized(lock) { if (generation == gen) disconnect() }
    }

    private fun disconnect() {
        val conn = connection
        shares.clear()
        session = null
        connection = null
        generation++
        // Hard close: no logoff round trips that would wait out the timeout on a dead link.
        if (conn != null) runCatching { conn.close(true) }
    }

    private fun ensureSession(): Session {
        val conn = connection
        val sess = session
        if (conn != null && conn.isConnected && sess != null) return sess
        disconnect()
        val c = client ?: SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withMultiProtocolNegotiate(true)
                // Long enough for the Pi's disk to spin up, short enough that a dead link is noticed.
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(25, TimeUnit.SECONDS)
                .build()
        ).also { client = it }
        val newConn = c.connect(Settings.host)
        // Shares are open: guest first, anonymous as a fallback.
        val newSess = try {
            newConn.authenticate(AuthenticationContext.guest())
        } catch (e: Exception) {
            newConn.authenticate(AuthenticationContext.anonymous())
        }
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
    private fun <T> retry(block: () -> T): T {
        val gen = generation
        return try {
            block()
        } catch (e: SMBApiException) {
            throw e // e.g. file not found / access denied: retrying won't help
        } catch (e: Exception) {
            // Another request may already have replaced the connection; don't drop its new one.
            resetIf(gen)
            block()
        }
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

package com.randyflix.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.hierynomus.smbj.share.File
import java.io.IOException

/** Lets ExoPlayer stream straight from the Samba share (smb:// URIs), with read-ahead buffering. */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private var file: File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private val buffer = ByteArray(BUFFER_SIZE)
    private var bufferStart = 0L
    private var bufferLength = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val f = try {
            Smb.open(Smb.pathFromUri(dataSpec.uri))
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = f
        val size = try {
            f.fileInformation.standardInformation.endOfFile
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        position = dataSpec.position
        if (position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        bufferLength = 0
        bufferStart = 0
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        if (position < bufferStart || position >= bufferStart + bufferLength) {
            fill()
            if (bufferLength == 0) return C.RESULT_END_OF_INPUT
        }
        val available = bufferStart + bufferLength - position
        val n = minOf(length.toLong(), available, bytesRemaining).toInt()
        System.arraycopy(buffer, (position - bufferStart).toInt(), target, offset, n)
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    private fun fill() {
        val f = file ?: throw IOException("SMB file is closed")
        bufferStart = position
        bufferLength = 0
        val want = minOf(BUFFER_SIZE.toLong(), bytesRemaining).toInt()
        try {
            while (bufferLength < want) {
                val chunk = minOf(CHUNK, want - bufferLength)
                val r = f.read(buffer, bufferStart + bufferLength, bufferLength, chunk)
                if (r <= 0) break
                bufferLength += r
            }
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } catch (_: Exception) {
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    companion object {
        private const val BUFFER_SIZE = 1 shl 20     // 1 MB read-ahead
        private const val CHUNK = 64 * 1024          // safe for every SMB2 dialect
    }
}

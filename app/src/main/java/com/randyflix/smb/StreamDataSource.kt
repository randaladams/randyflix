package com.randyflix.smb

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Sends smb:// URIs to the share and everything else (http/https live streams) to [http]. */
@UnstableApi
class StreamDataSource(private val http: DataSource, private val smb: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
        smb.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "smb") smb else http
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val http: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = StreamDataSource(http.createDataSource(), SmbDataSource())
    }
}

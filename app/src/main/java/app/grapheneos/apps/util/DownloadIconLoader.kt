// SPDX-License-Identifier: Apache-2.0
package app.grapheneos.apps.util

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import de.diamaneos.downloads.DownloadPolicy
import java.io.InputStream
import java.net.HttpURLConnection.HTTP_OK

/** Icons obey the same origin and redirect policy as the signed catalog and packages. */
internal class DownloadIconLoader : ModelLoader<GlideUrl, InputStream> {
    override fun handles(model: GlideUrl): Boolean = true

    override fun buildLoadData(model: GlideUrl, width: Int, height: Int, options: Options) =
        ModelLoader.LoadData(model, Fetcher(model.toStringUrl()))

    private class Fetcher(val url: String) : DataFetcher<InputStream> {
        @Volatile private var connection: ScopedHttpConnection? = null
        @Volatile private var cancelled = false
        private var stream: InputStream? = null

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                val path = DownloadPolicy.appsPath(url)
                if (!path.matches(Regex("packages/[A-Za-z0-9_.]+/icon\\.[A-Za-z0-9]+"))) {
                    throw java.net.ProtocolException("Unexpected icon path")
                }
                val conn = openConnection(null, url, {})
                connection = conn
                if (cancelled) { conn.close(); return }
                if (conn.v.responseCode != HTTP_OK) throwResponseCodeException(conn.v)
                // Icons are optional; oversized objects never reach the image decoder.
                val bytes = conn.v.inputStream.use {
                    de.diamaneos.downloads.DownloadTransport.readBounded(it, 1024 * 1024)
                }
                stream = bytes.inputStream()
                callback.onDataReady(stream)
            } catch (e: Exception) {
                callback.onLoadFailed(e)
            } finally {
                connection?.close()
            }
        }
        override fun cleanup() { stream?.close(); connection?.close() }
        override fun cancel() { cancelled = true; connection?.close() }
        override fun getDataClass(): Class<InputStream> = InputStream::class.java
        override fun getDataSource(): DataSource = DataSource.REMOTE
    }

    companion object {
        fun register(context: Context) {
            Glide.get(context).registry.replace(GlideUrl::class.java, InputStream::class.java,
                object : ModelLoaderFactory<GlideUrl, InputStream> {
                    override fun build(factory: MultiModelLoaderFactory) = DownloadIconLoader()
                    override fun teardown() = Unit
                })
        }
    }
}

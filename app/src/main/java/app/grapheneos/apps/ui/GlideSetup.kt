package app.grapheneos.apps.ui

import android.content.Context
import app.grapheneos.apps.util.DownloadIconLoader
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.signature.ObjectKey
import java.nio.ByteBuffer

@GlideModule
class AppGlide : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.append(VerifiedUrl::class.java, ByteBuffer::class.java, VerifiedUrlLoader.Factory())
    }
}

data class VerifiedUrl(val url: String, val size: Int, val sha256: String)

class VerifiedUrlFetcher(private val verifiedUrl: VerifiedUrl) : DataFetcher<ByteBuffer> {

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in ByteBuffer>) {
        val result: ByteBuffer
        try {
            // DiamaneOS: the checks of the catalog's icons live in DownloadIconLoader.
            result = ByteBuffer.wrap(DownloadIconLoader.load(verifiedUrl.url, verifiedUrl.size, verifiedUrl.sha256))
        } catch (e: Exception) {
            callback.onLoadFailed(e)
            return
        }
        callback.onDataReady(result)
    }

    override fun cleanup() = Unit
    override fun cancel() = Unit
    override fun getDataClass() = ByteBuffer::class.java
    override fun getDataSource() = DataSource.REMOTE
}

class VerifiedUrlLoader : ModelLoader<VerifiedUrl, ByteBuffer> {
    override fun buildLoadData(model: VerifiedUrl, width: Int, height: Int, options: Options) =
        ModelLoader.LoadData(ObjectKey(model), VerifiedUrlFetcher(model))

    override fun handles(model: VerifiedUrl) = true

    class Factory : ModelLoaderFactory<VerifiedUrl, ByteBuffer> {
        override fun build(multiFactory: MultiModelLoaderFactory) = VerifiedUrlLoader()
        override fun teardown() {}
    }
}

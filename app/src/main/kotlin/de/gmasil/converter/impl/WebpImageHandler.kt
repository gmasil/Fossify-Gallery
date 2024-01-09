package de.gmasil.converter.impl

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.webp.WebpImage
import com.bumptech.glide.integration.webp.decoder.WebpDecoder
import com.bumptech.glide.integration.webp.decoder.WebpFrameCacheStrategy
import com.bumptech.glide.integration.webp.decoder.WebpFrameLoader
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.resource.gif.GifBitmapProvider
import de.gmasil.converter.api.AnimatedImageHandler
import java.io.File
import java.nio.ByteBuffer

class WebpImageHandler(filePath: String, context: Context) : AnimatedImageHandler {

    private val webpDecoder: WebpDecoder
    init {
        val data = File(filePath).readBytes()
        val cacheStrategy: WebpFrameCacheStrategy? = Options().get(WebpFrameLoader.FRAME_CACHE_STRATEGY)
        val glide = Glide.get(context)
        val bitmapPool = glide.bitmapPool
        val arrayPool = glide.arrayPool
        val gifBitmapProvider = GifBitmapProvider(bitmapPool, arrayPool)
        val webpImage = WebpImage.create(data)
        val sampleSize = 1
        webpDecoder = WebpDecoder(gifBitmapProvider, webpImage, ByteBuffer.wrap(data), sampleSize, cacheStrategy)
    }

    override fun countFrames(): Int {
        return webpDecoder.frameCount
    }

    override fun advanceFrame() {
        webpDecoder.advance()
    }

    override fun getDelay(): Int {
        return webpDecoder.nextDelay
    }

    override fun getFrame(): Bitmap? {
        return webpDecoder.nextFrame
    }
}

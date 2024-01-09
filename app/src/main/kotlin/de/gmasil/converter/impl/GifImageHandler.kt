package de.gmasil.converter.impl

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.bumptech.glide.load.resource.gif.GifBitmapProvider
import de.gmasil.converter.api.AnimatedImageHandler
import java.io.File

class GifImageHandler(filePath: String, context: Context) : AnimatedImageHandler {

    private val gifDecoder: GifDecoder

    init {
        val data = File(filePath).readBytes()
        val glide = Glide.get(context)
        val gifBitmapProvider = GifBitmapProvider(glide.bitmapPool,  glide.arrayPool)
        gifDecoder = StandardGifDecoder(gifBitmapProvider)
        gifDecoder.read(data)
    }

    override fun countFrames(): Int {
        return gifDecoder.frameCount
    }

    override fun advanceFrame() {
        gifDecoder.advance()
    }

    override fun getDelay(): Int {
        return gifDecoder.nextDelay
    }

    override fun getFrame(): Bitmap? {
        return gifDecoder.nextFrame
    }
}

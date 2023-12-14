package org.fossify.gallery.converter.api

import android.graphics.Bitmap

interface AnimatedImageHandler {

    fun countFrames(): Int

    fun advanceFrame()

    fun getDelay(): Int

    fun getFrame(): Bitmap?
}

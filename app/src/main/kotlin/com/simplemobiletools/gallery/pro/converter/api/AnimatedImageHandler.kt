package com.simplemobiletools.gallery.pro.converter.api

import android.graphics.Bitmap

interface AnimatedImageHandler {

    fun countFrames(): Int

    fun advanceFrame()

    fun getDelay(): Int

    fun getFrame(): Bitmap?
}

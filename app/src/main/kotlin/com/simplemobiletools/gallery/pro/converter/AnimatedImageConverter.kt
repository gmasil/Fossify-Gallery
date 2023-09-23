package com.simplemobiletools.gallery.pro.converter

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.gallery.pro.converter.api.AnimatedImageHandler
import com.simplemobiletools.gallery.pro.converter.impl.GifImageHandler
import com.simplemobiletools.gallery.pro.converter.impl.WebpImageHandler
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths

class AnimatedImageConverter(val applicationContext: Context) {

    companion object {
        const val FILE_PADDING = 6
        const val NAME = "AnimatedImageConverter"
    }

    fun convertAnimatedImage(filePath: String): File {
        // select image type
        var imageHandler: AnimatedImageHandler
        if (filePath.lowercase().endsWith(".webp")) {
            imageHandler = WebpImageHandler(filePath, applicationContext)
        } else if(filePath.lowercase().endsWith(".gif")) {
            imageHandler = GifImageHandler(filePath, applicationContext)
        } else {
            throw IllegalArgumentException("Given file '$filePath' is not a supported animated image type")
        }
        val targetFolder = "/storage/emulated/0/tmp"
        val frameCount = imageHandler.countFrames()
        if (frameCount > 1) {
            // animated
            applicationContext.toast("Converting to video...")
            Log.i(NAME, "Extracting frames from '$filePath'...")
            val totalDelay = extractImages(imageHandler, targetFolder)
            Log.i(NAME, "Total delay: $totalDelay, frames: $frameCount")
            val targetFile = "${targetFolder}/output.mp4"
            if (createVideo(targetFolder, targetFile, totalDelay, frameCount)) {
                Log.i(NAME, "ffmpeg finished successfully")
                return File(targetFile)
            } else {
                throw IllegalStateException("Error while converting to video")
            }
        } else if (frameCount == 1) {
            // not animated
            extractImages(imageHandler, targetFolder)
            // return the only extracted image as png
            return File("$targetFolder/${"0".padStart(FILE_PADDING, '0')}.png")
        } else {
            throw IllegalStateException("File is corrupt")
        }
    }

    private fun extractImages(imageHandler: AnimatedImageHandler, targetFolder: String): Int {
        // prepare folder structure
        File(targetFolder).deleteRecursively();
        File(targetFolder).mkdirs()
        // extract images
        val frameCount = imageHandler.countFrames()
        var totalDelay = 0
        imageHandler.advanceFrame()
        for (i in 0 until frameCount) {
            val delay = imageHandler.getDelay()
            totalDelay += delay
            val bitmap = imageHandler.getFrame()
            // save bitmap
            if (bitmap != null) {
                saveImage(bitmap, i, targetFolder)
            }else{
                Log.w(NAME, "bitmap #${i} is null")
            }
            // select next bitmap
            imageHandler.advanceFrame()
        }
        return totalDelay
    }

    private fun saveImage(bitmap: Bitmap, i: Int, path: String) {
        val file = File(path,"${i.toString().padStart(FILE_PADDING, '0')}.png")
        val stream: OutputStream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        stream.flush()
        stream.close()
    }

    private fun createVideo(folder: String, targetFile: String, totalDelay: Int, frameCount: Int): Boolean {
        // calculate framerate
        val inputFramerate: Float  = frameCount / totalDelay.toFloat() * 1000
        // force 30 FPS output for Telegram animations
        var outputFramerate = 30
        Log.i(NAME, "Input framerate: $inputFramerate, output framerate: $outputFramerate")
        // create inventory file
        val inventory = StringBuilder()
        File(folder).listFiles().forEach { inventory.append("file '${it.absolutePath}'\n") }
        val inventoryFile = File(folder, "input.txt")
        inventoryFile.writeText(inventory.toString())
        // convert video
        val ffmpegCommand = "-r $inputFramerate -f concat -safe 0 -i ${inventoryFile.absolutePath} -r $outputFramerate -vcodec libx264 -crf 24 -preset slow -vf \"pad=ceil(iw/2)*2:ceil(ih/2)*2\" -movflags +faststart $targetFile"
        Log.i(NAME, "ffmpeg $ffmpegCommand")
        val session: FFmpegSession = FFmpegKit.execute(ffmpegCommand)
        return ReturnCode.isSuccess(session.returnCode)
    }
}

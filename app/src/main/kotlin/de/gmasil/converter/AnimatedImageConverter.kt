package de.gmasil.converter

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import org.fossify.commons.extensions.toast
import de.gmasil.converter.api.AnimatedImageHandler
import de.gmasil.converter.impl.WebpImageHandler
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class AnimatedImageConverter(val applicationContext: Context) {

    companion object {
        const val FILE_PADDING = 6
        const val NAME = "AnimatedImageConverter"
    }

    fun convertAnimatedImage(filePath: String): File {
        if (filePath.lowercase().endsWith(".webp")) {
            return convertWebpToGif(filePath)
        } else if(filePath.lowercase().endsWith(".mp4")) {
            applicationContext.toast("Converting to gif...")
            return convertVideoToGif(filePath)
        } else if(filePath.lowercase().endsWith(".webm")) {
            applicationContext.toast("Converting to gif...")
            return convertVideoToGif(filePath)
        } else {
            return File(filePath)
        }
    }

    private fun convertWebpToGif(filePath: String): File {
        val imageHandler: AnimatedImageHandler = WebpImageHandler(filePath, applicationContext)
        val targetFolder = applicationContext.cacheDir.resolve("converter").absolutePath
        val frameCount = imageHandler.countFrames()
        if (frameCount > 1) {
            // animated
            applicationContext.toast("Converting to gif...")
            Log.i(NAME, "Extracting frames from '$filePath'...")
            val totalDelay = extractImages(imageHandler, targetFolder)
            Log.i(NAME, "Total delay: $totalDelay, frames: $frameCount")
            val videoTargetFile = "${targetFolder}/output.mp4"
            val gifTargetFile = "${targetFolder}/output.gif"
            if (createVideoFromImagesInFolder(targetFolder, videoTargetFile, totalDelay, frameCount)) {
                if (convertVideoToGif(videoTargetFile, gifTargetFile)) {
                    return File(gifTargetFile)
                } else {
                    throw IllegalStateException("Error while converting to gif")
                }
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
            } else {
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

    private fun createVideoFromImagesInFolder(folder: String, targetFile: String, totalDelay: Int, frameCount: Int): Boolean {
        // calculate framerate
        val inputFramerate: Float  = frameCount / totalDelay.toFloat() * 1000
        // force 30 FPS output for Telegram animations
        val outputFramerate = 30
        Log.i(NAME, "Input framerate: $inputFramerate, output framerate: $outputFramerate")
        // create inventory file
        val inventory = StringBuilder()
        File(folder).listFiles()?.forEach { inventory.append("file '${it.absolutePath}'\n") }
        val inventoryFile = File(folder, "input.txt")
        inventoryFile.writeText(inventory.toString())
        // convert video
        val ffmpegCommand = "-r $inputFramerate -f concat -safe 0 -i ${inventoryFile.absolutePath} -r $outputFramerate -vcodec libx264 -crf 24 -preset slow -vf \"fps=${outputFramerate},pad=ceil(iw/2)*2:ceil(ih/2)*2\" -movflags +faststart $targetFile"
        Log.i(NAME, "ffmpeg $ffmpegCommand")
        val session: FFmpegSession = FFmpegKit.execute(ffmpegCommand)
        return ReturnCode.isSuccess(session.returnCode)
    }

    private fun convertVideoToGif(filePath: String, targetFile: String): Boolean {
        val ffmpegCommand = "-i '$filePath' '$targetFile'"
        Log.i(NAME, "ffmpeg $ffmpegCommand")
        val sessionGif: FFmpegSession = FFmpegKit.execute(ffmpegCommand)
        return ReturnCode.isSuccess(sessionGif.returnCode)
    }

    private fun convertVideoToGif(filePath: String): File {
        val targetFolder = applicationContext.cacheDir.resolve("converter").absolutePath
        // prepare folder structure
        File(targetFolder).deleteRecursively();
        File(targetFolder).mkdirs()
        // convert to gif
        val targetFile = "${targetFolder}/output.gif"
        if (convertVideoToGif(filePath, targetFile)) {
            return File(targetFile)
        } else {
            throw IllegalStateException("Error while converting to gif")
        }
    }
}

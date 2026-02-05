package de.gmasil.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.util.Consumer
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import de.gmasil.converter.api.AnimatedImageHandler
import de.gmasil.converter.impl.GifImageHandler
import de.gmasil.converter.impl.WebpImageHandler
import org.fossify.commons.extensions.toast
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture


class AnimatedImageConverter(private val applicationContext: Context) {

    companion object {
        const val FILE_PADDING = 6
        const val NAME = "AnimatedImageConverter"
    }

    fun handleMediaForSharing(filePath: String): File {
        if (filePath.lowercase().endsWith(".webp")) {
            return convertWebpToGif(filePath)
        } else if(filePath.lowercase().endsWith(".mp4")) {
            applicationContext.toast("Converting to gif...")
            return convertVideoToGif(filePath)
        } else if(filePath.lowercase().endsWith(".webm")) {
            applicationContext.toast("Converting to gif...")
            return convertVideoToGif(filePath)
        } else if(isNormalImageFile(filePath)) {
            return ensureSharableFileSize(filePath)
        } else {
            return File(filePath)
        }
    }

    private fun ensureSharableFileSize(filePath: String): File {
        val targetFolder = applicationContext.cacheDir.resolve("converter")
        val targetFile = targetFolder.resolve("resized.jpg")
        // prepare folder structure
        targetFolder.deleteRecursively();
        targetFolder.mkdirs()
        // load image
        val bmOptions = BitmapFactory.Options()
        bmOptions.inJustDecodeBounds = false
        val image = BitmapFactory.decodeFile(filePath, bmOptions)
        // save file, re-encode as JPG
        FileOutputStream(targetFile).use { out ->
            image.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return targetFile
    }

    fun reduceFileSize(filePath: String, replace: Boolean, converterProgress: Consumer<Float>?): Boolean {
        if(filePath.lowercase().endsWith(".webp")) {
            // check if it is a single image webp
            if(isAnimatedWebp(filePath)){
                return false
            }
            return reduceStillImageSize(filePath, replace)
        } else if(filePath.lowercase().endsWith(".gif")) {
            // check if it is a single image gif
            if(isAnimatedGif(filePath)){
                return false
            }
            return reduceStillImageSize(filePath, replace)
        } else if (isNormalImageFile(filePath)) {
            return reduceStillImageSize(filePath, replace)
        } else if(listOf(".mp4", ".webm").any { filePath.lowercase().endsWith(it) }) {
            return reduceVideoSize(filePath, replace, converterProgress)
        } else {
            val fileType = filePath.substring(filePath.lastIndexOf("."), filePath.length)
            applicationContext.toast("Unsupported file type: $fileType")
            return false
        }
    }

    fun reduceVideoSize(filePath: String, replace: Boolean, converterProgress: Consumer<Float>?): Boolean {
        val targetFolder = applicationContext.cacheDir.resolve("converter")
        targetFolder.deleteRecursively()
        targetFolder.mkdirs()
        val targetFile = File(filePath.take(filePath.lastIndexOf(".")) + if(replace) ".mp4" else "_reduced.mp4")
        if(!replace && targetFile.exists()) {
            return false
        }
        val tmpFile = File(targetFolder, "output.mp4")
        val ffmpegCommand = "-i \"$filePath\" -movflags +faststart -vcodec libx265 -crf 28 -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" -map_metadata 0 -map_metadata:s:v 0:s:v \"${tmpFile.absolutePath}\""
        Log.i(NAME, "ffmpeg $ffmpegCommand")

        val totalFrames = getTotalFrames(filePath)

        if(!execFfmpegWithProgress(ffmpegCommand, totalFrames, converterProgress)) {
            return false
        }
        // check if new file is smaller
        Log.i(NAME, "Old size ${File(filePath).length()}, new size ${tmpFile.length()}")
        if(tmpFile.length() >= File(filePath).length()) {
            tmpFile.delete()
            return false
        }
        // target file might be input filePath, so store lastModified first
        val lastModified = File(filePath).lastModified()
        targetFile.delete()
        tmpFile.copyTo(targetFile)
        tmpFile.delete()
        targetFile.setLastModified(lastModified)
        return true
    }

    fun execFfmpegWithProgress(ffmpegCommand: String, totalFrames: Int, converterProgress: Consumer<Float>?): Boolean {
        if(converterProgress == null) {
            val session = FFmpegKit.execute(ffmpegCommand)
            return ReturnCode.isSuccess(session.returnCode)
        } else {
            val ffmpegCompletable = CompletableFuture<Boolean>()
            val completeCallback = FFmpegSessionCompleteCallback{ session ->
                converterProgress.accept(100.0f)
                ffmpegCompletable.complete(ReturnCode.isSuccess(session.returnCode))
            }
            val statisticsCallback = StatisticsCallback { statistics ->
                converterProgress.accept(statistics.videoFrameNumber / totalFrames.toFloat() * 100.0f)
            }
            FFmpegKit.executeAsync(ffmpegCommand, completeCallback, null, statisticsCallback)
            return ffmpegCompletable.get()
        }
    }
    fun getTotalFrames(filePath: String): Int {
        var session: FFprobeSession = FFprobeKit.execute("-v error -select_streams v:0 -count_packets -show_entries stream=nb_read_packets -of csv=p=0 \"$filePath\"")
        if(ReturnCode.isSuccess(session.returnCode)) {
            if(session.logsAsString?.length != 0) {
                try {
                    return session.logsAsString.toInt()
                } catch (e: NumberFormatException) {
                    return -1
                }
            }
        }
        session = FFprobeKit.execute("-v error -select_streams v:0 -count_packets -show_entries stream=nb_read_packets -of csv=p=0 \"$filePath\"")
        try {
            return session.logsAsString.toInt()
        } catch (e: NumberFormatException) {
            return -1
        }
    }

    fun reduceStillImageSize(filePath: String, replace: Boolean): Boolean {
        val targetFile = filePath.take(filePath.lastIndexOf(".")) + "_reduced.jpg"
        // load image
        val bmOptions = BitmapFactory.Options()
        bmOptions.inJustDecodeBounds = false
        val image = BitmapFactory.decodeFile(filePath, bmOptions)
        // save file, re-encode as JPG
        FileOutputStream(targetFile).use { out ->
            image.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        val lastModified = File(filePath).lastModified()
        val isNewFileSmaller = File(targetFile).length() < File(filePath).length()
        if (!isNewFileSmaller) {
            File(targetFile).delete()
            return false
        } else if(replace) {
            val replaceFile = filePath.take(filePath.lastIndexOf(".")) + ".jpg"
            File(filePath).delete()
            File(targetFile).copyTo(File(replaceFile))
            File(targetFile).delete()
            File(replaceFile).setLastModified(lastModified)
        } else {
            File(targetFile).setLastModified(lastModified)
        }
        return true
    }

    private fun isNormalImageFile(filePath: String): Boolean {
        return listOf(".png", ".jpg", ".jpeg", ".bmp").any { filePath.lowercase().endsWith(it) }
    }

    fun isAnimatedWebp(filePath: String): Boolean {
        val imageHandler: AnimatedImageHandler = WebpImageHandler(filePath, applicationContext)
        return imageHandler.countFrames() != 1
    }

    fun isAnimatedGif(filePath: String): Boolean {
        val imageHandler: AnimatedImageHandler = GifImageHandler(filePath, applicationContext)
        return imageHandler.countFrames() != 1
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
        val ffmpegCommand = "-r $inputFramerate -f concat -safe 0 -i ${inventoryFile.absolutePath} -r $outputFramerate -vcodec libx264 -pix_fmt yuv420p -crf 24 -preset slow -vf \"fps=${outputFramerate},pad=ceil(iw/2)*2:ceil(ih/2)*2\" -movflags +faststart $targetFile"
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

    fun convertVideoToGifInSameFolder(filePath: String): Boolean {
        val targetFile = filePath.substring(0, filePath.lastIndexOf(".")) + ".mp4"
        if (File(targetFile).exists()) {
            return true;
        }
        return convertVideoToGif(filePath, targetFile)
    }

    fun convertAnimatedImageToGifInSameFolder(filePath: String): Boolean {
        val targetFile = filePath.substring(0, filePath.lastIndexOf(".")) + ".gif"
        val tmpFile = convertAnimatedImageToVideo(filePath)
        return convertVideoToGif(tmpFile.path, targetFile)
    }

    fun convertAnimatedImageToVideoInSameFolder(filePath: String): Boolean {
        val targetFile = filePath.substring(0, filePath.lastIndexOf(".")) + ".mp4"
        if (File(targetFile).exists()) {
            return true;
        }
        val tmpFile = convertAnimatedImageToVideo(filePath)
        tmpFile.let { sourceFile ->
            sourceFile.copyTo(File(targetFile))
            sourceFile.delete()
        }
        return true
    }

    fun convertAnimatedImageToVideo(filePath: String): File {
        // select image type
        var imageHandler: AnimatedImageHandler
        if (filePath.lowercase().endsWith(".webp")) {
            imageHandler = WebpImageHandler(filePath, applicationContext)
        } else if(filePath.lowercase().endsWith(".gif")) {
            imageHandler = GifImageHandler(filePath, applicationContext)
        } else {
            throw IllegalArgumentException("Given file '$filePath' is not a supported animated image type")
        }
        val targetFolder = applicationContext.cacheDir.resolve("converter").absolutePath
        val frameCount = imageHandler.countFrames()
        if (frameCount > 1) {
            // animated
            applicationContext.toast("Converting to video...")
            Log.i(NAME, "Extracting frames from '$filePath'...")
            val totalDelay = extractImages(imageHandler, targetFolder)
            Log.i(NAME, "Total delay: $totalDelay, frames: $frameCount")
            val targetFile = "${targetFolder}/output.mp4"
            if (createVideoFromImagesInFolder(targetFolder, targetFile, totalDelay, frameCount)) {
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

    fun convertToVideoInSameFolder(filePath: String): Boolean {
        val targetFile = filePath.take(filePath.lastIndexOf(".")) + ".mp4"
        if (File(targetFile).exists()) {
            return true;
        }
        val ffmpegCommand = "-i \"$filePath\" -movflags +faststart -vcodec libx264 -pix_fmt yuv420p -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" \"$targetFile\""
        Log.i(NAME, "ffmpeg $ffmpegCommand")
        val session: FFmpegSession = FFmpegKit.execute(ffmpegCommand)
        if(ReturnCode.isSuccess(session.returnCode)) {
            return true
        } else {
            if (File(targetFile).exists()) {
                File(targetFile).delete()
            }
            return false
        }
    }

    fun convertToWebpInSameFolder(filePath: String): Boolean {
        val targetFile = filePath.take(filePath.lastIndexOf(".")) + ".webp"
        if (File(targetFile).exists()) {
            return true
        }
        val ffmpegCommand = "-i \"$filePath\" -vcodec webp -loop 0 -pix_fmt yuva420p -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" \"$targetFile\""
        Log.i(NAME, "ffmpeg $ffmpegCommand")
        val session: FFmpegSession = FFmpegKit.execute(ffmpegCommand)
        if(ReturnCode.isSuccess(session.returnCode)) {
            return true;
        } else {
            if (File(targetFile).exists()) {
                File(targetFile).delete()
            }
            return false
        }
    }
}

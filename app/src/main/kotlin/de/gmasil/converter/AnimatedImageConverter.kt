package de.gmasil.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import de.gmasil.converter.api.AnimatedImageHandler
import de.gmasil.converter.api.ConverterProgress
import de.gmasil.converter.impl.GifImageHandler
import de.gmasil.converter.impl.WebpImageHandler
import org.fossify.commons.extensions.toast
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

class AnimatedImageConverter(private val applicationContext: Context) {

    companion object {
        const val FILE_PADDING = 6
        const val NAME = "AnimatedImageConverter"
        private const val STILL_IMAGE_VIDEO_DURATION_SECONDS = 3
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_OUTPUT_HEIGHT = 720
        private const val VIDEO_OUTPUT_BITRATE = 1_500_000L
        private const val AUDIO_OUTPUT_BITRATE = 128_000L
        // Account for container overhead and encoder bitrate variance.
        private const val VIDEO_OUTPUT_SIZE_MARGIN = 1.05
    }

    private val converterCacheDir: File
        get() = applicationContext.cacheDir.resolve("converter")

    fun handleMediaForSharing(filePath: String, converterProgress: ConverterProgress?): File {
        val extension = filePath.fileExtension()
        return when {
            extension == "webp" -> convertWebpToGif(filePath, converterProgress)
            extension == "mp4" || extension == "webm" -> {
                applicationContext.toast("Converting to gif...")
                convertVideoToGif(filePath, converterProgress)
            }
            isNormalImageFile(filePath) -> ensureSharableFileSize(filePath)
            else -> File(filePath)
        }
    }

    private fun ensureSharableFileSize(filePath: String): File = withWorkspace { workspace ->
        val outputFile = workspace.resolve("resized.jpg")
        val image = BitmapFactory.decodeFile(filePath)
            ?: throw IllegalArgumentException("Could not decode image '$filePath'")
        FileOutputStream(outputFile).use { output ->
            check(image.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                "Could not encode image '$filePath'"
            }
        }
        cacheResult(outputFile)
    }

    fun reduceFileSize(filePath: String, replace: Boolean, converterProgress: ConverterProgress?): Boolean {
        return when (filePath.fileExtension()) {
            "webp" -> if (isAnimatedWebp(filePath)) false else reduceStillImageSize(filePath, replace)
            "gif" -> if (isAnimatedGif(filePath)) false else reduceStillImageSize(filePath, replace)
            "png", "jpg", "jpeg", "bmp" -> reduceStillImageSize(filePath, replace)
            "mp4", "webm" -> reduceVideoSize(filePath, replace, converterProgress)
            else -> {
                val fileType = filePath.substringAfterLast('.', filePath)
                applicationContext.toast("Unsupported file type: .$fileType")
                false
            }
        }
    }

    fun reduceVideoSize(filePath: String, replace: Boolean, converterProgress: ConverterProgress?): Boolean {
        val sourceFile = File(filePath)
        val targetFile = File(
            filePath.substringBeforeLast('.') + if (replace) ".mp4" else "_reduced.mp4"
        )
        if (!replace && targetFile.exists()) {
            return false
        }

        val lastModified = sourceFile.lastModified()
        val sourceSize = sourceFile.length()
        val videoInfo = getVideoStreamInfo(filePath)
        if (videoInfo != null) {
            val estimatedOutputSize = estimateReducedVideoSize(videoInfo)
            Log.i(
                NAME,
                "Video preflight: ${videoInfo.width}x${videoInfo.height}, " +
                    "bitrate=${videoInfo.bitrate ?: "unknown"}, duration=${videoInfo.durationSeconds}s, " +
                    "source=$sourceSize, estimated output=$estimatedOutputSize"
            )
            if (sourceSize <= estimatedOutputSize) {
                Log.i(NAME, "Skipping video reduction because estimated output is not smaller")
                return false
            }
        }

        val reduced = withWorkspace { workspace ->
            val outputFile = workspace.resolve("output.mp4")
            val scaleFilter = "scale=iw:'min($VIDEO_OUTPUT_HEIGHT,ih)':" +
                "force_original_aspect_ratio=decrease:force_divisible_by=2"
            val command = "-i ${quote(filePath)} -vf \"$scaleFilter\" " +
                "-c:v h264_mediacodec -b:v ${VIDEO_OUTPUT_BITRATE / 1000}k " +
                "-c:a aac -b:a ${AUDIO_OUTPUT_BITRATE / 1000}k ${quote(outputFile.absolutePath)}"
            Log.i(NAME, "ffmpeg $command")
            val totalFrames = if (converterProgress == null) 0 else getTotalFrames(filePath)
            if (!runFfmpeg(command, totalFrames, converterProgress, "Converting", outputFile)) {
                return@withWorkspace false
            }
            Log.i(NAME, "Old size $sourceSize, new size ${outputFile.length()}")
            if (outputFile.length() >= sourceSize) {
                return@withWorkspace false
            }
            publishCompletedFile(outputFile, targetFile, replace, lastModified)
        }

        return reduced && (!replace || deleteOriginalAfterReplacement(sourceFile, targetFile))
    }

    private data class VideoStreamInfo(
        val width: Long,
        val height: Long,
        val durationSeconds: Double,
        val bitrate: Long?,
        val hasAudio: Boolean
    )

    private fun getVideoStreamInfo(filePath: String): VideoStreamInfo? {
        return try {
            val session = FFprobeKit.getMediaInformation(filePath)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                return null
            }

            val mediaInformation = session.mediaInformation ?: return null
            val streams = mediaInformation.streams
            val videoStream = streams
                .filter { it.type == "video" }
                .maxByOrNull { (it.width ?: 0L) * (it.height ?: 0L) }
                ?: return null
            val durationSeconds = sequenceOf(
                mediaInformation.duration,
                *streams.map { it.getStringProperty("duration") }.toTypedArray()
            )
                .mapNotNull { it?.toDoubleOrNull() }
                .filter { it.isFinite() && it > 0.0 }
                .maxOrNull()
                ?: return null

            VideoStreamInfo(
                width = videoStream.width ?: 0L,
                height = videoStream.height ?: 0L,
                durationSeconds = durationSeconds,
                bitrate = videoStream.bitrate?.toLongOrNull(),
                hasAudio = streams.any { it.type == "audio" }
            )
        } catch (exception: Exception) {
            Log.w(NAME, "Could not probe video stream for size estimation", exception)
            null
        }
    }

    private fun estimateReducedVideoSize(videoInfo: VideoStreamInfo): Long {
        val outputBitrate = VIDEO_OUTPUT_BITRATE + if (videoInfo.hasAudio) AUDIO_OUTPUT_BITRATE else 0L
        val estimatedBytes = videoInfo.durationSeconds * outputBitrate / 8.0 * VIDEO_OUTPUT_SIZE_MARGIN
        return estimatedBytes.toLong()
    }

    fun reduceStillImageSize(filePath: String, replace: Boolean): Boolean {
        val sourceFile = File(filePath)
        val targetFile = File(
            filePath.substringBeforeLast('.') + if (replace) ".jpg" else "_reduced.jpg"
        )
        if (!replace && targetFile.exists()) {
            return false
        }

        val lastModified = sourceFile.lastModified()
        val sourceSize = sourceFile.length()
        val reduced = withWorkspace { workspace ->
            val outputFile = workspace.resolve("output.jpg")
            val image = BitmapFactory.decodeFile(filePath) ?: return@withWorkspace false
            FileOutputStream(outputFile).use { output ->
                if (!image.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    return@withWorkspace false
                }
            }
            if (outputFile.length() >= sourceSize) {
                return@withWorkspace false
            }
            publishCompletedFile(outputFile, targetFile, replace, lastModified)
        }

        return reduced && (!replace || deleteOriginalAfterReplacement(sourceFile, targetFile))
    }

    private fun isNormalImageFile(filePath: String): Boolean =
        filePath.fileExtension() in setOf("png", "jpg", "jpeg", "bmp")

    fun isAnimatedWebp(filePath: String): Boolean {
        val imageHandler: AnimatedImageHandler = WebpImageHandler(filePath, applicationContext)
        return imageHandler.countFrames() != 1
    }

    fun isAnimatedGif(filePath: String): Boolean {
        val imageHandler: AnimatedImageHandler = GifImageHandler(filePath, applicationContext)
        return imageHandler.countFrames() != 1
    }

    private fun convertWebpToGif(filePath: String, converterProgress: ConverterProgress?): File =
        withWorkspace { workspace ->
            val outputFile = workspace.resolve("output.gif")
            if (!createAnimatedImageGif(filePath, outputFile, workspace, converterProgress)) {
                throw IllegalStateException("Error while converting to gif")
            }
            cacheResult(outputFile)
        }

    private fun createAnimatedImageGif(
        filePath: String,
        outputFile: File,
        workspace: File,
        converterProgress: ConverterProgress?
    ): Boolean {
        val imageHandler = imageHandlerFor(filePath)
        val extraction = extractImages(imageHandler, workspace.resolve("frames"), converterProgress)
        if (extraction.files.size == 1) {
            val command = "-i ${quote(extraction.files.single().absolutePath)} ${quote(outputFile.absolutePath)}"
            Log.i(NAME, "ffmpeg $command")
            return runFfmpeg(command, 1, converterProgress, "Converting", outputFile)
        }

        val videoFile = workspace.resolve("animation.mp4")
        return createVideoFromImages(extraction, workspace, videoFile, converterProgress) &&
            convertVideoToGif(videoFile.absolutePath, outputFile.absolutePath, converterProgress)
    }

    private fun imageHandlerFor(filePath: String): AnimatedImageHandler = when (filePath.fileExtension()) {
        "webp" -> WebpImageHandler(filePath, applicationContext)
        "gif" -> GifImageHandler(filePath, applicationContext)
        else -> throw IllegalArgumentException("Given file '$filePath' is not a supported animated image type")
    }

    private data class ExtractedFrames(val files: List<File>, val delaysMillis: List<Int>)

    private fun extractImages(
        imageHandler: AnimatedImageHandler,
        targetFolder: File,
        converterProgress: ConverterProgress?
    ): ExtractedFrames {
        check(targetFolder.mkdirs() || targetFolder.isDirectory) {
            "Could not create frame directory '${targetFolder.absolutePath}'"
        }
        val frameCount = imageHandler.countFrames()
        if (frameCount <= 0) {
            throw IllegalStateException("File is corrupt")
        }

        val files = mutableListOf<File>()
        val delays = mutableListOf<Int>()
        imageHandler.advanceFrame()
        for (index in 0 until frameCount) {
            val frameFile = targetFolder.resolve("${index.toString().padStart(FILE_PADDING, '0')}.png")
            val bitmap = imageHandler.getFrame()
                ?: throw IllegalStateException("Could not decode frame #$index")
            saveImage(bitmap, frameFile)
            files.add(frameFile)
            delays.add(imageHandler.getDelay().coerceAtLeast(1))
            imageHandler.advanceFrame()
            converterProgress?.accept(index.toFloat() / frameCount * 100.0f, "Extracting")
        }
        return ExtractedFrames(files, delays)
    }

    private fun saveImage(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { stream: OutputStream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Could not write frame '${file.absolutePath}'"
            }
        }
    }

    private fun createVideoFromImages(
        extraction: ExtractedFrames,
        workspace: File,
        outputFile: File,
        converterProgress: ConverterProgress?
    ): Boolean {
        val manifest = workspace.resolve("frames.txt")
        val contents = buildString {
            extraction.files.forEachIndexed { index, file ->
                append("file '").append(escapeConcatPath(file.absolutePath)).append("'\n")
                append("duration ")
                    .append(String.format(Locale.US, "%.3f", extraction.delaysMillis[index] / 1000.0))
                    .append('\n')
            }
            // Concat demuxer uses the next file timestamp to determine the last frame duration.
            append("file '").append(escapeConcatPath(extraction.files.last().absolutePath)).append("'\n")
        }
        manifest.writeText(contents)

        val filter = "fps=$VIDEO_FRAME_RATE,pad=ceil(iw/2)*2:ceil(ih/2)*2"
        val command = "-f concat -safe 0 -i ${quote(manifest.absolutePath)} -vf \"$filter\" " +
            "-r $VIDEO_FRAME_RATE -an -c:v h264_mediacodec -pix_fmt yuv420p -movflags +faststart ${quote(outputFile.absolutePath)}"
        Log.i(NAME, "ffmpeg $command")
        return runFfmpeg(command, extraction.files.size, converterProgress, "Converting", outputFile)
    }

    private fun createStillImageVideo(
        imageFile: File,
        outputFile: File,
        converterProgress: ConverterProgress?
    ): Boolean {
        val filter = "scale=trunc(iw/2)*2:trunc(ih/2)*2"
        val command = "-loop 1 -i ${quote(imageFile.absolutePath)} -t $STILL_IMAGE_VIDEO_DURATION_SECONDS " +
            "-vf \"$filter\" -r $VIDEO_FRAME_RATE -an -c:v h264_mediacodec -pix_fmt yuv420p " +
            "-movflags +faststart ${quote(outputFile.absolutePath)}"
        Log.i(NAME, "ffmpeg $command")
        return runFfmpeg(command, 0, converterProgress, "Converting", outputFile)
    }

    private fun convertVideoToGif(
        filePath: String,
        targetFile: String,
        converterProgress: ConverterProgress?
    ): Boolean {
        val command = "-i ${quote(filePath)} ${quote(targetFile)}"
        Log.i(NAME, "ffmpeg $command")
        val totalFrames = if (converterProgress == null) 0 else getTotalFrames(filePath)
        return execFfmpegWithProgress(command, totalFrames, converterProgress, "Converting") &&
            File(targetFile).isValidOutput()
    }

    private fun convertVideoToGif(filePath: String, converterProgress: ConverterProgress?): File =
        withWorkspace { workspace ->
            val outputFile = workspace.resolve("output.gif")
            if (!convertVideoToGif(filePath, outputFile.absolutePath, converterProgress)) {
                throw IllegalStateException("Error while converting to gif")
            }
            cacheResult(outputFile)
        }

    fun convertVideoToGifInSameFolder(filePath: String, converterProgress: ConverterProgress?): Boolean {
        val targetFile = File(filePath.substringBeforeLast('.') + ".gif")
        if (targetFile.exists()) {
            return true
        }
        val lastModified = File(filePath).lastModified()
        return withWorkspace { workspace ->
            val outputFile = workspace.resolve("output.gif")
            if (!convertVideoToGif(filePath, outputFile.absolutePath, converterProgress)) {
                return@withWorkspace false
            }
            publishCompletedFile(outputFile, targetFile, replace = false, lastModified = lastModified)
        }
    }

    fun convertAnimatedImageToGifInSameFolder(
        filePath: String,
        converterProgress: ConverterProgress?
    ): Boolean {
        val targetFile = File(filePath.substringBeforeLast('.') + ".gif")
        if (targetFile.exists()) {
            return true
        }
        val lastModified = File(filePath).lastModified()
        return withWorkspace { workspace ->
            val outputFile = workspace.resolve("output.gif")
            if (!createAnimatedImageGif(filePath, outputFile, workspace, converterProgress)) {
                return@withWorkspace false
            }
            publishCompletedFile(outputFile, targetFile, replace = false, lastModified = lastModified)
        }
    }

    fun convertAnimatedImageToVideoInSameFolder(
        filePath: String,
        converterProgress: ConverterProgress?
    ): Boolean {
        val targetFile = File(filePath.substringBeforeLast('.') + ".mp4")
        if (targetFile.exists()) {
            return true
        }
        val lastModified = File(filePath).lastModified()
        return withWorkspace { workspace ->
            val outputFile = workspace.resolve("output.mp4")
            if (!createAnimatedImageVideo(filePath, outputFile, workspace, converterProgress)) {
                return@withWorkspace false
            }
            publishCompletedFile(outputFile, targetFile, replace = false, lastModified = lastModified)
        }
    }

    fun convertAnimatedImageToVideo(filePath: String, converterProgress: ConverterProgress?): File =
        withWorkspace { workspace ->
            val outputFile = workspace.resolve("output.mp4")
            if (!createAnimatedImageVideo(filePath, outputFile, workspace, converterProgress)) {
                throw IllegalStateException("Error while converting to video")
            }
            cacheResult(outputFile)
        }

    private fun createAnimatedImageVideo(
        filePath: String,
        outputFile: File,
        workspace: File,
        converterProgress: ConverterProgress?
    ): Boolean {
        applicationContext.toast("Converting to video...")
        Log.i(NAME, "Extracting frames from '$filePath'...")
        val extraction = extractImages(imageHandlerFor(filePath), workspace.resolve("frames"), converterProgress)
        return if (extraction.files.size == 1) {
            createStillImageVideo(extraction.files.single(), outputFile, converterProgress)
        } else {
            createVideoFromImages(extraction, workspace, outputFile, converterProgress)
        }
    }

    fun convertToVideoInSameFolder(filePath: String, converterProgress: ConverterProgress?): Boolean {
        val targetFile = File(filePath.substringBeforeLast('.') + ".mp4")
        if (targetFile.exists()) {
            return true
        }
        return convertToTarget(filePath, targetFile, "mp4", converterProgress) { outputFile ->
            "-i ${quote(filePath)} -movflags +faststart -vcodec h264_mediacodec " +
                "-pix_fmt yuv420p -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" ${quote(outputFile.absolutePath)}"
        }
    }

    fun convertToWebpInSameFolder(filePath: String, converterProgress: ConverterProgress?): Boolean {
        val targetFile = File(filePath.substringBeforeLast('.') + ".webp")
        if (targetFile.exists()) {
            return true
        }
        return convertToTarget(filePath, targetFile, "webp", converterProgress) { outputFile ->
            "-i ${quote(filePath)} -vcodec webp -loop 0 -pix_fmt yuva420p " +
                "-vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" ${quote(outputFile.absolutePath)}"
        }
    }

    private fun convertToTarget(
        sourcePath: String,
        targetFile: File,
        outputExtension: String,
        converterProgress: ConverterProgress?,
        commandBuilder: (File) -> String
    ): Boolean = withWorkspace { workspace ->
        val outputFile = workspace.resolve("output.$outputExtension")
        val command = commandBuilder(outputFile)
        Log.i(NAME, "ffmpeg $command")
        val totalFrames = if (converterProgress == null) 0 else getTotalFrames(sourcePath)
        if (!runFfmpeg(command, totalFrames, converterProgress, "Converting", outputFile)) {
            return@withWorkspace false
        }
        publishCompletedFile(outputFile, targetFile, replace = false, lastModified = File(sourcePath).lastModified())
    }

    fun execFfmpegWithProgress(
        ffmpegCommand: String,
        totalFrames: Int,
        converterProgress: ConverterProgress?,
        status: String
    ): Boolean {
        if (converterProgress == null) {
            val session = FFmpegKit.execute(ffmpegCommand)
            return ReturnCode.isSuccess(session.returnCode)
        }

        val ffmpegCompletable = CompletableFuture<Boolean>()
        val completeCallback = FFmpegSessionCompleteCallback { session ->
            val succeeded = ReturnCode.isSuccess(session.returnCode)
            if (succeeded) {
                converterProgress.accept(100.0f, status)
            }
            ffmpegCompletable.complete(succeeded)
        }
        val statisticsCallback = StatisticsCallback { statistics ->
            if (totalFrames > 0) {
                val progress = statistics.videoFrameNumber / totalFrames.toFloat() * 100.0f
                converterProgress.accept(progress.coerceIn(0.0f, 99.0f), status)
            }
        }
        FFmpegKit.executeAsync(ffmpegCommand, completeCallback, null, statisticsCallback)
        return ffmpegCompletable.get()
    }

    fun getTotalFrames(filePath: String): Int {
        val command = "-v error -select_streams v:0 -count_packets -show_entries stream=nb_read_packets -of csv=p=0 ${quote(filePath)}"
        val session = FFprobeKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            return 0
        }
        return session.logsAsString?.trim()?.toIntOrNull() ?: 0
    }

    private fun runFfmpeg(
        command: String,
        totalFrames: Int,
        converterProgress: ConverterProgress?,
        status: String,
        outputFile: File
    ): Boolean = execFfmpegWithProgress(command, totalFrames, converterProgress, status) && outputFile.isValidOutput()

    private inline fun <T> withWorkspace(block: (File) -> T): T {
        check(converterCacheDir.mkdirs() || converterCacheDir.isDirectory) {
            "Could not create converter cache directory"
        }
        val workspace = converterCacheDir.resolve("conversion-${UUID.randomUUID()}")
        check(workspace.mkdirs()) { "Could not create conversion workspace" }
        return try {
            block(workspace)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun cacheResult(source: File): File {
        val extension = source.extension
        val target = converterCacheDir.resolve("shared-${UUID.randomUUID()}.$extension")
        check(publishCompletedFile(source, target, replace = false, lastModified = source.lastModified())) {
            "Could not save converted file for sharing"
        }
        return target
    }

    /** Stage beside the destination, then atomically replace it only after the copy is complete. */
    private fun publishCompletedFile(
        source: File,
        target: File,
        replace: Boolean,
        lastModified: Long
    ): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.mkdirs() && !parent.isDirectory) {
            return false
        }
        if (!replace && target.exists()) {
            return false
        }

        val stagingFile = parent.resolve(".${target.name}.pending-${UUID.randomUUID()}")
        try {
            source.copyTo(stagingFile, overwrite = false)
            stagingFile.setLastModified(lastModified)
            if (!replace && target.exists()) {
                return false
            }
            moveCompletedFile(stagingFile, target, replace)
            return true
        } catch (_: FileAlreadyExistsException) {
            return false
        } finally {
            stagingFile.delete()
        }
    }

    private fun moveCompletedFile(source: File, target: File, replace: Boolean) {
        try {
            if (replace) {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } else {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (_: AtomicMoveNotSupportedException) {
            if (replace) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(source.toPath(), target.toPath())
            }
        }
    }

    private fun quote(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun escapeConcatPath(value: String): String =
        value.replace("\\", "\\\\").replace("'", "'\\''")

    private fun String.fileExtension(): String = substringAfterLast('.', "").lowercase()

    private fun File.isValidOutput(): Boolean = isFile && length() > 0L

    private fun File.sameFileAs(other: File): Boolean =
        absoluteFile.toPath().normalize() == other.absoluteFile.toPath().normalize()

    private fun deleteOriginalAfterReplacement(source: File, target: File): Boolean {
        if (source.sameFileAs(target) || !source.exists() || source.delete()) {
            return true
        }
        Log.e(NAME, "Could not delete original file '${source.absolutePath}' after replacement")
        return false
    }
}

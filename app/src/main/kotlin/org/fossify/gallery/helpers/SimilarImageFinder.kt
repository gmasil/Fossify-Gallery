package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.helpers.FAVORITES
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.getFavoritePaths
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.max

class SimilarImageFinder(private val context: Context) {
    fun findSimilarImages(
        folders: Collection<String>,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
        onSimilarFilesFound: (List<File>) -> Unit = {},
        shouldStop: () -> Boolean = { false }
    ): List<List<File>> {
        val fetcher = MediaFetcher(context)
        fetcher.shouldStopCallback = shouldStop
        val favoritePaths = context.getFavoritePaths()
        val filePaths = LinkedHashSet<String>()

        folders.filter { it != FAVORITES && it != RECYCLE_BIN && !context.config.isFolderProtected(it) }
            .forEach { folder ->
                if (shouldStop()) return@forEach
                try {
                    fetcher.getFilesFrom(
                        curPath = folder,
                        isPickImage = true,
                        isPickVideo = false,
                        getProperDateTaken = false,
                        getProperLastModified = false,
                        getProperFileSize = false,
                        favoritePaths = favoritePaths,
                        getVideoDurations = false,
                        lastModifieds = HashMap(),
                        dateTakens = HashMap(),
                        android11Files = null
                    ).filter { it.type == TYPE_IMAGES && it.path.isImageFast() }
                        .mapTo(filePaths) { it.path }
                } catch (_: Exception) {
                }
            }

        if (shouldStop()) return emptyList()

        val candidates = filePaths.mapNotNull { path ->
            try {
                File(path).takeIf { it.isFile && it.canRead() }
            } catch (_: SecurityException) {
                null
            }
        }
        val totalFiles = filePaths.size
        var checkedFiles = totalFiles - candidates.size
        var lastReportedCount = -1
        val progressInterval = (totalFiles / MAX_PROGRESS_UPDATES).coerceAtLeast(1)

        fun reportProgress(force: Boolean = false) {
            if (force || checkedFiles - lastReportedCount >= progressInterval || checkedFiles >= totalFiles) {
                onProgress(checkedFiles, totalFiles)
                lastReportedCount = checkedFiles
            }
        }

        reportProgress(force = true)

        val fingerprints = ArrayList<ImageFingerprint>()
        val hashIndex = PerceptualHashIndex()
        val exactCandidates = HashMap<ExactKey, MutableList<Int>>()
        val parents = ArrayList<Int>()
        val reportedSimilarIndices = HashSet<Int>()

        fun rootOf(index: Int): Int {
            var root = index
            while (parents[root] != root) root = parents[root]
            var current = index
            while (parents[current] != current) {
                val next = parents[current]
                parents[current] = root
                current = next
            }
            return root
        }

        fun join(first: Int, second: Int) {
            val firstRoot = rootOf(first)
            val secondRoot = rootOf(second)
            if (firstRoot != secondRoot) parents[secondRoot] = firstRoot
        }

        candidates.forEach candidateLoop@{ file ->
            if (shouldStop()) return emptyList()
            try {
                val fingerprint = try {
                    file.createFingerprint()
                } catch (_: Exception) {
                    null
                } ?: return@candidateLoop

                // Exact copies belong in the existing duplicate scan, not this similarity result.
                val exactKey = ExactKey(fingerprint.hash, file.length())
                val existingExactCandidates = exactCandidates[exactKey].orEmpty()
                val isExactCopy = existingExactCandidates.any { index ->
                    if (shouldStop()) return emptyList()
                    try {
                        file.contentsMatch(fingerprints[index].file, shouldStop)
                    } catch (_: Exception) {
                        false
                    }
                }
                if (isExactCopy) return@candidateLoop

                val index = fingerprints.size
                parents.add(index)
                fingerprints.add(fingerprint)
                val newlySimilarIndices = LinkedHashSet<Int>()
                var foundSimilarMatch = false
                val nearbyCandidates = hashIndex.findWithin(fingerprint.hash, MAX_PHASH_DISTANCE)
                nearbyCandidates.forEach nearbyLoop@{ nearbyIndex ->
                    if (shouldStop()) return emptyList()
                    val other = fingerprints[nearbyIndex]
                    if (!aspectRatiosMatch(fingerprint.aspectRatio, other.aspectRatio)) return@nearbyLoop
                    if (structuralSimilarity(fingerprint.pixels, other.pixels) >= MIN_SSIM) {
                        val nearbyRoot = rootOf(nearbyIndex)
                        if (nearbyRoot !in reportedSimilarIndices) {
                            newlySimilarIndices.add(nearbyRoot)
                        }
                        join(index, nearbyIndex)
                        foundSimilarMatch = true
                    }
                }

                hashIndex.add(fingerprint.hash, index)
                exactCandidates.getOrPut(exactKey) { ArrayList() }.add(index)
                if (foundSimilarMatch) {
                    newlySimilarIndices.add(index)
                    reportedSimilarIndices.addAll(newlySimilarIndices)
                    onSimilarFilesFound(newlySimilarIndices.map { fingerprints[it].file })
                }
            } finally {
                checkedFiles++
                reportProgress()
            }
        }

        if (shouldStop()) return emptyList()

        val groups = LinkedHashMap<Int, MutableList<File>>()
        fingerprints.indices.forEach { index ->
            val root = rootOf(index)
            groups.getOrPut(root) { ArrayList() }.add(fingerprints[index].file)
        }
        return groups.values.filter { it.size > 1 }
    }

    private fun File.createFingerprint(): ImageFingerprint? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_SIDE) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(absolutePath, options) ?: return null
        val bitmaps = arrayListOf(decoded)

        return try {
            val exif = try {
                ExifInterface(absolutePath)
            } catch (_: Exception) {
                null
            }
            val rotation = exif?.rotationDegrees ?: 0
            val flipped = exif?.isFlipped ?: false
            val oriented = if (rotation != 0 || flipped) {
                val matrix = Matrix().apply {
                    setRotate(rotation.toFloat())
                    if (flipped) postScale(-1f, 1f)
                }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    if (it !== decoded) bitmaps.add(it)
                }
            } else {
                decoded
            }

            val thumbnail = oriented.scale(HASH_SIZE, HASH_SIZE, filter = true).also {
                if (it !== decoded && it !== oriented) bitmaps.add(it)
            }
            val pixels = IntArray(HASH_SIZE * HASH_SIZE)
            thumbnail.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
            val normalizedPixels = IntArray(pixels.size) { compositeOnWhite(pixels[it]) }
            val luminance = DoubleArray(normalizedPixels.size) { luminance(normalizedPixels[it]) }
            val mean = luminance.average()
            val variance = luminance.sumOf { (it - mean) * (it - mean) } / luminance.size
            if (variance < MIN_LUMINANCE_VARIANCE) return null

            ImageFingerprint(
                file = this,
                hash = PerceptualHash.calculate(luminance),
                pixels = createSsimPixels(normalizedPixels),
                aspectRatio = oriented.width.toFloat() / oriented.height
            )
        } finally {
            bitmaps.distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun File.contentsMatch(other: File, shouldStop: () -> Boolean): Boolean {
        if (length() != other.length()) return false
        BufferedInputStream(FileInputStream(this)).use { first ->
            BufferedInputStream(FileInputStream(other)).use { second ->
                val firstBuffer = ByteArray(BUFFER_SIZE)
                val secondBuffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (shouldStop()) return false
                    val firstCount = first.read(firstBuffer)
                    val secondCount = second.read(secondBuffer)
                    if (firstCount != secondCount) return false
                    if (firstCount < 0) return true
                    for (index in 0 until firstCount) {
                        if (firstBuffer[index] != secondBuffer[index]) return false
                    }
                }
            }
        }
    }

    private fun compositeOnWhite(pixel: Int): Int {
        val alpha = Color.alpha(pixel)
        val red = (Color.red(pixel) * alpha + 255 * (255 - alpha)) / 255
        val green = (Color.green(pixel) * alpha + 255 * (255 - alpha)) / 255
        val blue = (Color.blue(pixel) * alpha + 255 * (255 - alpha)) / 255
        return Color.rgb(red, green, blue)
    }

    private fun luminance(pixel: Int): Double =
        0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)

    private fun aspectRatiosMatch(first: Float, second: Float): Boolean =
        abs(first - second) / max(first, second) <= MAX_ASPECT_RATIO_DIFFERENCE

    private fun createSsimPixels(pixels: IntArray): ByteArray {
        val pixelsPerSide = HASH_SIZE / SSIM_SIZE
        val samplesPerPixel = pixelsPerSide * pixelsPerSide
        val output = ByteArray(SSIM_SIZE * SSIM_SIZE * RGB_CHANNEL_COUNT)
        for (y in 0 until SSIM_SIZE) {
            for (x in 0 until SSIM_SIZE) {
                var red = 0
                var green = 0
                var blue = 0
                for (offsetY in 0 until pixelsPerSide) {
                    for (offsetX in 0 until pixelsPerSide) {
                        val pixel = pixels[(y * pixelsPerSide + offsetY) * HASH_SIZE + x * pixelsPerSide + offsetX]
                        red += Color.red(pixel)
                        green += Color.green(pixel)
                        blue += Color.blue(pixel)
                    }
                }

                val index = (y * SSIM_SIZE + x) * RGB_CHANNEL_COUNT
                output[index] = (red / samplesPerPixel).toByte()
                output[index + 1] = (green / samplesPerPixel).toByte()
                output[index + 2] = (blue / samplesPerPixel).toByte()
            }
        }
        return output
    }

    private fun structuralSimilarity(first: ByteArray, second: ByteArray): Double {
        var totalScore = 0.0
        var scoreCount = 0
        for (top in 0 until SSIM_SIZE step SSIM_WINDOW_SIZE) {
            for (left in 0 until SSIM_SIZE step SSIM_WINDOW_SIZE) {
                for (channel in 0 until RGB_CHANNEL_COUNT) {
                    var firstSum = 0.0
                    var secondSum = 0.0
                    var firstSquaredSum = 0.0
                    var secondSquaredSum = 0.0
                    var productSum = 0.0

                    for (y in top until top + SSIM_WINDOW_SIZE) {
                        for (x in left until left + SSIM_WINDOW_SIZE) {
                            val index = (y * SSIM_SIZE + x) * RGB_CHANNEL_COUNT + channel
                            val firstValue = (first[index].toInt() and 0xff).toDouble()
                            val secondValue = (second[index].toInt() and 0xff).toDouble()
                            firstSum += firstValue
                            secondSum += secondValue
                            firstSquaredSum += firstValue * firstValue
                            secondSquaredSum += secondValue * secondValue
                            productSum += firstValue * secondValue
                        }
                    }

                    val count = (SSIM_WINDOW_SIZE * SSIM_WINDOW_SIZE).toDouble()
                    val firstMean = firstSum / count
                    val secondMean = secondSum / count
                    val firstVariance = max(0.0, firstSquaredSum / count - firstMean * firstMean)
                    val secondVariance = max(0.0, secondSquaredSum / count - secondMean * secondMean)
                    val covariance = productSum / count - firstMean * secondMean
                    val numerator = (2 * firstMean * secondMean + SSIM_C1) * (2 * covariance + SSIM_C2)
                    val denominator = (firstMean * firstMean + secondMean * secondMean + SSIM_C1) *
                        (firstVariance + secondVariance + SSIM_C2)
                    totalScore += numerator / denominator
                    scoreCount++
                }
            }
        }
        return totalScore / scoreCount
    }

    private data class ImageFingerprint(
        val file: File,
        val hash: Long,
        val pixels: ByteArray,
        val aspectRatio: Float
    )

    private data class ExactKey(val hash: Long, val size: Long)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val HASH_SIZE = 32
        private const val SSIM_SIZE = 16
        private const val MAX_DECODE_SIDE = 256
        private const val MAX_PHASH_DISTANCE = 6
        private const val MAX_ASPECT_RATIO_DIFFERENCE = 0.03f
        private const val MIN_SSIM = 0.94
        private const val SSIM_WINDOW_SIZE = 4
        private const val RGB_CHANNEL_COUNT = 3
        private const val SSIM_C1 = 6.5025
        private const val SSIM_C2 = 58.5225
        private const val MIN_LUMINANCE_VARIANCE = 16.0
        private const val MAX_PROGRESS_UPDATES = 100
    }
}

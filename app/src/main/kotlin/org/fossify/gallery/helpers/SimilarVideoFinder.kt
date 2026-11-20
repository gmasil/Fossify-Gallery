package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.graphics.scale
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.helpers.FAVORITES
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.getFavoritePaths
import org.fossify.gallery.models.SimilarityFingerprint
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

class SimilarVideoFinder(private val context: Context) {
    fun findSimilarVideos(
        folders: Collection<String>,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
        onSimilarVideosFound: (List<File>) -> Unit = {},
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
                        isPickImage = false,
                        isPickVideo = true,
                        getProperDateTaken = false,
                        getProperLastModified = false,
                        getProperFileSize = false,
                        favoritePaths = favoritePaths,
                        getVideoDurations = false,
                        lastModifieds = HashMap(),
                        dateTakens = HashMap(),
                        android11Files = null
                    ).filter { it.type == TYPE_VIDEOS && it.path.isVideoFast() }
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

        val fingerprintCache = SimilarityFingerprintCache(context)
        val fingerprints = ArrayList<VideoFingerprint>()
        val hashIndexes = Array(ANCHOR_FRAME_INDICES.size) { PerceptualHashIndex() }
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

        candidates.forEach candidateFileLoop@{ file ->
            if (shouldStop()) return emptyList()
            try {
                val initialLength = file.length()
                val initialLastModified = file.lastModified()
                val contentHash = try {
                    file.contentSha256(shouldStop)
                } catch (_: Exception) {
                    null
                } ?: return@candidateFileLoop
                if (file.length() != initialLength || file.lastModified() != initialLastModified) return@candidateFileLoop

                val cachedEntry = fingerprintCache.get(contentHash, SimilarityFingerprintCache.VIDEO_MEDIA_TYPE)
                val cachedFingerprint = cachedEntry?.toVideoFingerprint(file)
                if (cachedEntry != null && cachedFingerprint == null) {
                    fingerprintCache.delete(contentHash, SimilarityFingerprintCache.VIDEO_MEDIA_TYPE)
                }
                val fingerprint = try {
                    cachedFingerprint ?: file.createFingerprint(shouldStop)?.also { created ->
                        if (file.length() == initialLength && file.lastModified() == initialLastModified) {
                            fingerprintCache.put(
                                SimilarityFingerprint(
                                    contentHash = contentHash,
                                    mediaType = SimilarityFingerprintCache.VIDEO_MEDIA_TYPE,
                                    fingerprintVersion = SimilarityFingerprintCache.FINGERPRINT_VERSION,
                                    perceptualHash = 0L,
                                    structuralPixels = null,
                                    aspectRatio = created.aspectRatio,
                                    durationMs = created.durationMs,
                                    frameHashes = created.frameHashes.encodeFrameHashes()
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                    null
                } ?: return@candidateFileLoop
                if (file.length() != initialLength || file.lastModified() != initialLastModified) return@candidateFileLoop

                val index = fingerprints.size
                parents.add(index)
                val candidateVotes = HashMap<Int, Int>()
                val newlySimilarIndices = LinkedHashSet<Int>()
                var foundSimilarMatch = false
                ANCHOR_FRAME_INDICES.forEachIndexed { anchorIndex, frameIndex ->
                    val hash = fingerprint.frameHashes[frameIndex] ?: return@forEachIndexed
                    hashIndexes[anchorIndex].findWithin(hash, MAX_CANDIDATE_HASH_DISTANCE).forEach { nearbyIndex ->
                        candidateVotes[nearbyIndex] = (candidateVotes[nearbyIndex] ?: 0) + 1
                    }
                }

                candidateVotes.forEach nearbyLoop@{ (nearbyIndex, matchingAnchors) ->
                    if (shouldStop()) return emptyList()
                    if (matchingAnchors < MIN_CANDIDATE_ANCHOR_MATCHES) return@nearbyLoop

                    val other = fingerprints[nearbyIndex]
                    if (!durationsMatch(fingerprint.durationMs, other.durationMs)) return@nearbyLoop
                    if (!aspectRatiosMatch(fingerprint.aspectRatio, other.aspectRatio)) return@nearbyLoop
                    if (!frameSequencesMatch(fingerprint.frameHashes, other.frameHashes)) return@nearbyLoop

                    val exactCopy = fingerprint.file.length() == other.file.length() && try {
                        fingerprint.file.contentsMatch(other.file, shouldStop)
                    } catch (_: Exception) {
                        false
                    }
                    if (!exactCopy) {
                        val nearbyRoot = rootOf(nearbyIndex)
                        if (nearbyRoot !in reportedSimilarIndices) {
                            newlySimilarIndices.add(nearbyRoot)
                        }
                        join(index, nearbyIndex)
                        foundSimilarMatch = true
                    }
                }

                fingerprints.add(fingerprint)
                ANCHOR_FRAME_INDICES.forEachIndexed { anchorIndex, frameIndex ->
                    fingerprint.frameHashes[frameIndex]?.let { hash -> hashIndexes[anchorIndex].add(hash, index) }
                }
                if (foundSimilarMatch) {
                    newlySimilarIndices.add(index)
                    reportedSimilarIndices.addAll(newlySimilarIndices)
                    onSimilarVideosFound(newlySimilarIndices.map { fingerprints[it].file })
                }
            } finally {
                checkedFiles++
                reportProgress()
            }
        }

        fingerprintCache.flush()
        if (shouldStop()) return emptyList()

        val groups = LinkedHashMap<Int, MutableList<File>>()
        fingerprints.indices.forEach { index ->
            val root = rootOf(index)
            groups.getOrPut(root) { ArrayList() }.add(fingerprints[index].file)
        }
        return groups.values.filter { it.size > 1 }
    }

    private fun File.createFingerprint(shouldStop: () -> Boolean): VideoFingerprint? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return null
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?.let { ((it % 360) + 360) % 360 }
                ?: 0
            val aspectRatio = if (rotation == 90 || rotation == 270) {
                height.toFloat() / width
            } else {
                width.toFloat() / height
            }

            val frameHashes = SAMPLE_POSITIONS.map { position ->
                if (shouldStop()) return null
                val timeUs = (durationMs * 1000.0 * position).roundToLong()
                retriever.getScaledFrame(timeUs, rotation)?.let(::frameHash)
            }
            if (frameHashes.count { it != null } < MIN_VALID_FRAME_COUNT) return null

            return VideoFingerprint(this, durationMs, aspectRatio, frameHashes)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun MediaMetadataRetriever.getScaledFrame(timeUs: Long, rotation: Int): Bitmap? {
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, MAX_FRAME_SIDE, MAX_FRAME_SIDE)
        } else {
            getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: return null

        if (rotation == 0) return frame
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return try {
            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true).also {
                if (it !== frame && !frame.isRecycled) frame.recycle()
            }
        } catch (_: Exception) {
            if (!frame.isRecycled) frame.recycle()
            null
        }
    }

    private fun frameHash(frame: Bitmap): Long? {
        val bitmaps = arrayListOf(frame)
        return try {
            val thumbnail = frame.scale(PerceptualHash.SIZE, PerceptualHash.SIZE, filter = true).also {
                if (it !== frame) bitmaps.add(it)
            }
            val pixels = IntArray(PerceptualHash.SIZE * PerceptualHash.SIZE)
            thumbnail.getPixels(pixels, 0, PerceptualHash.SIZE, 0, 0, PerceptualHash.SIZE, PerceptualHash.SIZE)
            val luminance = DoubleArray(pixels.size) { pixelIndex ->
                val pixel = pixels[pixelIndex]
                0.299 * ((pixel shr 16) and 0xff) +
                    0.587 * ((pixel shr 8) and 0xff) +
                    0.114 * (pixel and 0xff)
            }
            val mean = luminance.average()
            val variance = luminance.sumOf { (it - mean) * (it - mean) } / luminance.size
            if (variance < MIN_LUMINANCE_VARIANCE) null else PerceptualHash.calculate(luminance)
        } finally {
            bitmaps.distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun durationsMatch(first: Long, second: Long): Boolean {
        val tolerance = (max(first, second) / 1000L).coerceIn(MIN_DURATION_TOLERANCE_MS, MAX_DURATION_TOLERANCE_MS)
        return abs(first - second) <= tolerance
    }

    private fun aspectRatiosMatch(first: Float, second: Float): Boolean =
        abs(first - second) / max(first, second) <= MAX_ASPECT_RATIO_DIFFERENCE

    private fun frameSequencesMatch(first: List<Long?>, second: List<Long?>): Boolean {
        var comparableFrames = 0
        var matchingFrames = 0
        val matchedSections = BooleanArray(SECTION_COUNT)

        first.indices.forEach { index ->
            val firstHash = first[index] ?: return@forEach
            val secondHash = second[index] ?: return@forEach
            comparableFrames++
            if (PerceptualHash.distance(firstHash, secondHash) <= MAX_FRAME_HASH_DISTANCE) {
                matchingFrames++
                val section = index * SECTION_COUNT / SAMPLE_POSITIONS.size
                matchedSections[section] = true
            }
        }

        return comparableFrames >= MIN_VALID_FRAME_COUNT &&
            matchingFrames >= MIN_MATCHING_FRAME_COUNT &&
            matchingFrames.toFloat() / comparableFrames >= MIN_MATCHING_FRAME_RATIO &&
            matchedSections.all { it }
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

    private data class VideoFingerprint(
        val file: File,
        val durationMs: Long,
        val aspectRatio: Float,
        val frameHashes: List<Long?>
    )

    private fun SimilarityFingerprint.toVideoFingerprint(file: File): VideoFingerprint? {
        val hashes = frameHashes?.decodeFrameHashes() ?: return null
        if (durationMs <= 0L || !aspectRatio.isFinite() || aspectRatio <= 0f) return null
        if (hashes.count { it != null } < MIN_VALID_FRAME_COUNT) return null
        return VideoFingerprint(file, durationMs, aspectRatio, hashes)
    }

    private fun List<Long?>.encodeFrameHashes(): ByteArray = ByteBuffer.allocate(SAMPLE_COUNT * FRAME_HASH_BYTE_COUNT)
        .apply { forEach { putLong(it ?: INVALID_FRAME_HASH) } }
        .array()

    private fun ByteArray.decodeFrameHashes(): List<Long?>? {
        if (size != SAMPLE_COUNT * FRAME_HASH_BYTE_COUNT) return null
        val buffer = ByteBuffer.wrap(this)
        return List(SAMPLE_COUNT) {
            buffer.long.takeUnless { hash -> hash == INVALID_FRAME_HASH }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val SAMPLE_COUNT = 10
        private const val MIN_VALID_FRAME_COUNT = 6
        private const val MIN_MATCHING_FRAME_COUNT = 6
        private const val MIN_MATCHING_FRAME_RATIO = 0.8f
        private const val MAX_FRAME_SIDE = 256
        private const val MIN_LUMINANCE_VARIANCE = 16.0
        private const val MAX_FRAME_HASH_DISTANCE = 8
        private const val MAX_CANDIDATE_HASH_DISTANCE = 12
        private const val MIN_CANDIDATE_ANCHOR_MATCHES = 2
        private const val MIN_DURATION_TOLERANCE_MS = 50L
        private const val MAX_DURATION_TOLERANCE_MS = 1_000L
        private const val MAX_ASPECT_RATIO_DIFFERENCE = 0.03f
        private const val SECTION_COUNT = 3
        private const val MAX_PROGRESS_UPDATES = 100
        private const val FRAME_HASH_BYTE_COUNT = 8
        private const val INVALID_FRAME_HASH = Long.MIN_VALUE

        private val SAMPLE_POSITIONS = List(SAMPLE_COUNT) { index -> (index + 0.5) / SAMPLE_COUNT }
        private val ANCHOR_FRAME_INDICES = listOf(1, 4, 7, 9)
    }
}

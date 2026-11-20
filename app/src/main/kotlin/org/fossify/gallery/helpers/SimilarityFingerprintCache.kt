package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.interfaces.SimilarityFingerprintDao
import org.fossify.gallery.models.SimilarityFingerprint
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal class SimilarityFingerprintCache(context: Context) {
    private val dao: SimilarityFingerprintDao? = try {
        GalleryDatabase.getInstance(context).SimilarityFingerprintDao()
    } catch (_: Exception) {
        null
    }
    private val pendingWrites = LinkedHashMap<CacheKey, SimilarityFingerprint>()

    fun get(contentHash: String, mediaType: Int): SimilarityFingerprint? {
        val key = CacheKey(contentHash, mediaType)
        pendingWrites[key]?.let { return it }
        val fingerprintDao = dao ?: return null

        val cached = try {
            fingerprintDao.getFingerprint(contentHash, mediaType)
        } catch (_: Exception) {
            null
        } ?: return null
        if (cached.fingerprintVersion == FINGERPRINT_VERSION) return cached

        runCatching { fingerprintDao.deleteFingerprint(contentHash, mediaType) }
        return null
    }

    fun put(fingerprint: SimilarityFingerprint) {
        pendingWrites[CacheKey(fingerprint.contentHash, fingerprint.mediaType)] = fingerprint
        if (pendingWrites.size >= MAX_PENDING_WRITES) flush()
    }

    fun delete(contentHash: String, mediaType: Int) {
        pendingWrites.remove(CacheKey(contentHash, mediaType))
        dao?.let { runCatching { it.deleteFingerprint(contentHash, mediaType) } }
    }

    fun flush() {
        if (pendingWrites.isEmpty()) return
        val fingerprintDao = dao ?: run {
            pendingWrites.clear()
            return
        }
        try {
            fingerprintDao.insertFingerprints(pendingWrites.values.toList())
        } catch (_: Exception) {
            // Fingerprint caching is an optimization; cache failures must not abort similarity scans.
        } finally {
            pendingWrites.clear()
        }
    }

    private data class CacheKey(val contentHash: String, val mediaType: Int)

    companion object {
        const val IMAGE_MEDIA_TYPE = 1
        const val VIDEO_MEDIA_TYPE = 2
        // Increment when either media fingerprint representation or its generation algorithm changes.
        const val FINGERPRINT_VERSION = 1

        private const val MAX_PENDING_WRITES = 128
    }
}

internal fun File.contentSha256(shouldStop: () -> Boolean): String? {
    val initialLength = length()
    val initialLastModified = lastModified()
    val digest = MessageDigest.getInstance("SHA-256")
    BufferedInputStream(FileInputStream(this)).use { input ->
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        while (true) {
            if (shouldStop()) return null
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }

    if (length() != initialLength || lastModified() != initialLastModified) return null
    val hashBytes = digest.digest()
    val hexadecimal = CharArray(hashBytes.size * 2)
    hashBytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        hexadecimal[index * 2] = HEX_DIGITS[value ushr 4]
        hexadecimal[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(hexadecimal)
}

private const val HASH_BUFFER_SIZE = 128 * 1024
private const val HEX_DIGITS = "0123456789abcdef"

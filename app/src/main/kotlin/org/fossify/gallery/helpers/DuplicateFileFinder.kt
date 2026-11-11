package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.commons.helpers.FAVORITES
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.getFavoritePaths
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class DuplicateFileFinder(private val context: Context) {
    fun findDuplicates(
        folders: Collection<String>,
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
                        isPickVideo = false,
                        getProperDateTaken = false,
                        getProperLastModified = false,
                        getProperFileSize = false,
                        favoritePaths = favoritePaths,
                        getVideoDurations = false,
                        lastModifieds = HashMap(),
                        dateTakens = HashMap(),
                        android11Files = null
                    ).mapTo(filePaths) { it.path }
                } catch (_: Exception) {
                }
            }

        if (shouldStop()) return emptyList()

        val sameSizeCandidates = filePaths.mapNotNull { path ->
            try {
                File(path).takeIf { it.isFile && it.canRead() }
            } catch (_: SecurityException) {
                null
            }
        }.groupBy { it.length() }.values.filter { it.size > 1 }

        val duplicates = ArrayList<List<File>>()
        sameSizeCandidates.forEach { candidates ->
            if (shouldStop()) return duplicates

            val byHash = HashMap<String, MutableList<File>>()
            candidates.forEach { file ->
                if (shouldStop()) return duplicates
                try {
                    val hash = file.sha256(shouldStop) ?: return duplicates
                    byHash.getOrPut(hash) { ArrayList() }.add(file)
                } catch (_: Exception) {
                }
            }

            byHash.values.filter { it.size > 1 }.forEach { hashMatches ->
                if (shouldStop()) return duplicates
                val identicalGroups = ArrayList<MutableList<File>>()
                hashMatches.forEach { file ->
                    if (shouldStop()) return duplicates
                    val matchingGroup = try {
                        identicalGroups.firstOrNull { group ->
                            file.contentsMatch(group.first(), shouldStop)
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (matchingGroup == null) {
                        identicalGroups.add(arrayListOf(file))
                    } else {
                        matchingGroup.add(file)
                    }
                }
                duplicates.addAll(identicalGroups.filter { it.size > 1 })
            }
        }

        return duplicates
    }

    private fun File.sha256(shouldStop: () -> Boolean): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(this)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (shouldStop()) return null
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hashBytes = digest.digest()
        val hex = CharArray(hashBytes.size * 2)
        hashBytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(hex)
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

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}

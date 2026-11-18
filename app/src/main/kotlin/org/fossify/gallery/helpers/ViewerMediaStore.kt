package org.fossify.gallery.helpers

import org.fossify.gallery.models.Medium
import java.util.LinkedHashMap

object ViewerMediaStore {
    private const val MAX_PENDING_LISTS = 8
    private const val PENDING_LIST_TTL_MS = 10 * 60 * 1000L

    private data class PendingMedia(val items: ArrayList<Medium>, val createdAt: Long)

    private val pendingMedia = LinkedHashMap<String, PendingMedia>()

    @Synchronized
    fun store(path: String, media: ArrayList<Medium>) {
        removeExpiredLists()
        pendingMedia[path] = PendingMedia(ArrayList(media), System.currentTimeMillis())
        while (pendingMedia.size > MAX_PENDING_LISTS) {
            pendingMedia.remove(pendingMedia.keys.first())
        }
    }

    @Synchronized
    fun get(path: String): ArrayList<Medium>? {
        removeExpiredLists()
        return pendingMedia[path]?.items?.let(::ArrayList)
    }

    @Synchronized
    fun remove(path: String) {
        pendingMedia.remove(path)
    }

    private fun removeExpiredLists() {
        val expirationTime = System.currentTimeMillis() - PENDING_LIST_TTL_MS
        pendingMedia.entries.removeAll { it.value.createdAt < expirationTime }
    }
}

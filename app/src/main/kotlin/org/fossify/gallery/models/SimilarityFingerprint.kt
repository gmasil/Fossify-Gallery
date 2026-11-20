package org.fossify.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "similarity_fingerprints",
    primaryKeys = ["content_hash", "media_type"]
)
data class SimilarityFingerprint(
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "media_type") val mediaType: Int,
    @ColumnInfo(name = "fingerprint_version") val fingerprintVersion: Int,
    @ColumnInfo(name = "perceptual_hash") val perceptualHash: Long,
    @ColumnInfo(name = "structural_pixels") val structuralPixels: ByteArray?,
    @ColumnInfo(name = "aspect_ratio") val aspectRatio: Float,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "frame_hashes") val frameHashes: ByteArray?
)

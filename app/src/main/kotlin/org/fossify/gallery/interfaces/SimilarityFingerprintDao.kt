package org.fossify.gallery.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.gallery.models.SimilarityFingerprint

@Dao
interface SimilarityFingerprintDao {
    @Query(
        "SELECT * FROM similarity_fingerprints " +
            "WHERE content_hash = :contentHash AND media_type = :mediaType LIMIT 1"
    )
    fun getFingerprint(contentHash: String, mediaType: Int): SimilarityFingerprint?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFingerprints(fingerprints: List<SimilarityFingerprint>)

    @Query("DELETE FROM similarity_fingerprints WHERE content_hash = :contentHash AND media_type = :mediaType")
    fun deleteFingerprint(contentHash: String, mediaType: Int)
}

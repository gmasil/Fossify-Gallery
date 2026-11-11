package org.fossify.gallery.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DuplicateFileItemBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.loadImage
import org.fossify.gallery.helpers.ROUNDED_CORNERS_BIG
import org.fossify.gallery.helpers.ROUNDED_CORNERS_NONE
import org.fossify.gallery.models.Medium
import java.io.File

class DuplicateFilesAdapter(
    private val activity: BaseSimpleActivity,
    private val media: List<Medium>,
    private val showFolderNames: Boolean,
    private val onClick: (Medium) -> Unit
) : RecyclerView.Adapter<DuplicateFilesAdapter.ViewHolder>() {
    private val roundedCorners = if (activity.config.fileRoundedCorners) ROUNDED_CORNERS_BIG else ROUNDED_CORNERS_NONE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = DuplicateFileItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val medium = media.getOrNull(position) ?: return
        holder.bind(medium)
    }

    override fun getItemCount() = media.size

    inner class ViewHolder(private val binding: DuplicateFileItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(medium: Medium) {
            val folderName = File(medium.parentPath).name.ifEmpty { medium.parentPath }
            binding.duplicateFileFolder.text = folderName
            binding.duplicateFileFolder.beVisibleIf(showFolderNames)
            binding.duplicateFileThumbnail.setBackgroundResource(
                if (roundedCorners == ROUNDED_CORNERS_BIG) R.drawable.placeholder_rounded_big else R.drawable.placeholder_square
            )
            activity.loadImage(
                type = medium.type,
                path = medium.path,
                target = binding.duplicateFileThumbnail,
                horizontalScroll = false,
                animateGifs = activity.config.animateGifs,
                cropThumbnails = activity.config.cropThumbnails,
                roundCorners = roundedCorners,
                signature = medium.getKey()
            )
            binding.root.setOnClickListener { onClick(medium) }
        }
    }
}

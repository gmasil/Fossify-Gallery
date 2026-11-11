package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.gallery.extensions.launchVideoPlayer
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.adapters.DuplicateFilesAdapter
import org.fossify.gallery.databinding.ActivityDuplicateFilesBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.DUPLICATE_SCAN_FOLDERS
import org.fossify.gallery.helpers.DUPLICATE_SHOW_FOLDER_NAMES
import org.fossify.gallery.helpers.DuplicateFileFinder
import org.fossify.gallery.helpers.GridSpacingItemDecoration
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_RAWS
import org.fossify.gallery.helpers.TYPE_SVGS
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class DuplicateFilesActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDuplicateFilesBinding::inflate)
    private val shouldStop = AtomicBoolean(false)
    private var scanStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.duplicateFilesToolbar.title = getString(R.string.duplicate_files)
        binding.duplicateFilesPlaceholder.setTextColor(getProperTextColor())
        binding.duplicateFilesProgress.setIndicatorColor(getProperPrimaryColor())
        setupEdgeToEdge(
            padTopSystem = listOf(binding.duplicateFilesAppbar),
            padBottomSystem = listOf(binding.duplicateFilesGrid)
        )
        startScan(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.duplicateFilesAppbar, NavigationIcon.Arrow)
    }

    override fun onDestroy() {
        shouldStop.set(true)
        super.onDestroy()
    }

    private fun startScan(savedInstanceState: Bundle?) {
        if (scanStarted) return
        scanStarted = true
        val folders = savedInstanceState?.getStringArrayList(DUPLICATE_SCAN_FOLDERS)
            ?: intent.getStringArrayListExtra(DUPLICATE_SCAN_FOLDERS).orEmpty()

        ensureBackgroundThread {
            val duplicateFiles = DuplicateFileFinder(applicationContext)
                .findDuplicates(folders) { shouldStop.get() }
            val media = duplicateFiles.flatten().mapNotNull(::fileToMedium) as ArrayList<Medium>

            runOnUiThread {
                if (isFinishing || isDestroyed || shouldStop.get()) return@runOnUiThread
                binding.duplicateFilesProgress.beGone()
                binding.duplicateFilesScanningText.beGone()
                binding.duplicateFilesPlaceholder.apply {
                    text = if (media.isEmpty()) {
                        getString(R.string.no_duplicate_files_found)
                    } else {
                        getString(R.string.duplicate_files_count, media.size)
                    }
                    beVisibleIf(media.isEmpty())
                }
                if (media.isNotEmpty()) {
                    showDuplicates(media)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(
            DUPLICATE_SCAN_FOLDERS,
            intent.getStringArrayListExtra(DUPLICATE_SCAN_FOLDERS)
        )
        super.onSaveInstanceState(outState)
    }

    private fun showDuplicates(media: ArrayList<Medium>) {
        val columns = config.mediaColumnCnt.coerceAtLeast(1)
        binding.duplicateFilesGrid.layoutManager = GridLayoutManager(this, columns)
        val thumbnailItems = ArrayList<ThumbnailItem>(media)
        if (binding.duplicateFilesGrid.itemDecorationCount == 0) {
            binding.duplicateFilesGrid.addItemDecoration(
                GridSpacingItemDecoration(
                    spanCount = columns,
                    spacing = config.thumbnailSpacing,
                    isScrollingHorizontally = false,
                    addSideSpacing = config.fileRoundedCorners,
                    items = thumbnailItems,
                    useGridPosition = false
                )
            )
        }
        binding.duplicateFilesGrid.adapter = DuplicateFilesAdapter(
            activity = this,
            media = media,
            showFolderNames = intent.getBooleanExtra(DUPLICATE_SHOW_FOLDER_NAMES, false)
        ) { medium ->
            if (medium.isVideo() && config.openVideosOnSeparateScreen) {
                launchVideoPlayer(medium.path)
            } else {
                Intent(this, ViewPagerActivity::class.java).apply {
                    putExtra(PATH, medium.path)
                    putExtra(SHOW_ALL, false)
                    startActivity(this)
                }
            }
        }
        binding.duplicateFilesGrid.beVisible()
    }

    private fun fileToMedium(file: File): Medium? {
        if (!file.exists() || !file.isFile) return null
        val path = file.absolutePath
        val type = when {
            path.isVideoFast() -> TYPE_VIDEOS
            path.isGif() -> TYPE_GIFS
            path.isSvg() -> TYPE_SVGS
            path.isRawFast() -> TYPE_RAWS
            else -> org.fossify.gallery.helpers.TYPE_IMAGES
        }
        return Medium(
            id = null,
            name = file.name,
            path = path,
            parentPath = file.parent.orEmpty(),
            modified = file.lastModified(),
            taken = 0L,
            size = file.length(),
            type = type,
            videoDuration = 0,
            isFavorite = false,
            deletedTS = 0L,
            mediaStoreId = 0L
        )
    }
}

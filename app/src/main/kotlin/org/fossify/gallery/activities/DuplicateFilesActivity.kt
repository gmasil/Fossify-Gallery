package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isMediaFile
import org.fossify.commons.extensions.isRawFast
import org.fossify.commons.extensions.isSvg
import org.fossify.commons.extensions.isVideoFast
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.IS_FROM_GALLERY
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.gallery.R
import org.fossify.gallery.adapters.MediaAdapter
import org.fossify.gallery.databinding.ActivityDuplicateFilesBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.launchGesturePlayer
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.openPathWithViewerMedia
import org.fossify.gallery.helpers.DUPLICATE_SCAN_FOLDERS
import org.fossify.gallery.helpers.DUPLICATE_SHOW_FOLDER_NAMES
import org.fossify.gallery.helpers.DuplicateFileFinder
import org.fossify.gallery.helpers.FIND_SIMILAR_FILES
import org.fossify.gallery.helpers.GridSpacingItemDecoration
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SimilarImageFinder
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.TYPE_GIFS
import org.fossify.gallery.helpers.TYPE_RAWS
import org.fossify.gallery.helpers.TYPE_SVGS
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.helpers.VIDEO_PLAYER_APP
import org.fossify.gallery.helpers.VIDEO_PLAYER_SYSTEM
import org.fossify.gallery.helpers.VIEWER_MEDIA
import org.fossify.gallery.interfaces.MediaOperationsListener
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val PROGRESS_TRACK_ALPHA = 64

class DuplicateFilesActivity : SimpleActivity(), MediaOperationsListener {
    private val binding by viewBinding(ActivityDuplicateFilesBinding::inflate)
    private val shouldStop = AtomicBoolean(false)
    private var scanStarted = false
    private val isSimilarFiles get() = intent.getBooleanExtra(FIND_SIMILAR_FILES, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val title = if (isSimilarFiles) R.string.similar_files else R.string.duplicate_files
        setTitle(title)
        binding.duplicateFilesToolbar.title = getString(title)
        binding.duplicateFilesPlaceholder.setTextColor(getProperTextColor())
        val primaryColor = getProperPrimaryColor()
        val trackColor = ColorUtils.setAlphaComponent(getProperTextColor(), PROGRESS_TRACK_ALPHA)
        binding.duplicateFilesProgress.apply {
            setIndicatorColor(primaryColor)
            setTrackColor(trackColor)
        }
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
        val isSimilar = isSimilarFiles
        binding.duplicateFilesScanningText.setText(
            if (isSimilar) R.string.scanning_for_similar_images else R.string.scanning_for_duplicates
        )

        ensureBackgroundThread {
            val matchedFiles = if (isSimilar) {
                SimilarImageFinder(applicationContext).findSimilarImages(
                    folders,
                    onProgress = ::updateScanProgress
                ) { shouldStop.get() }
            } else {
                DuplicateFileFinder(applicationContext).findDuplicates(
                    folders,
                    onProgress = ::updateScanProgress
                ) { shouldStop.get() }
            }
            val media = matchedFiles.flatten().mapNotNull(::fileToMedium) as ArrayList<Medium>
            val thumbnailItems = ArrayList<ThumbnailItem>(media)
            val showFolderNames = intent.getBooleanExtra(DUPLICATE_SHOW_FOLDER_NAMES, false)

            runOnUiThread {
                if (isFinishing || isDestroyed || shouldStop.get()) return@runOnUiThread
                binding.duplicateFilesProgress.beGone()
                binding.duplicateFilesScanningText.beGone()
                binding.duplicateFilesPlaceholder.apply {
                    text = if (media.isEmpty()) {
                        getString(if (isSimilar) R.string.no_similar_files_found else R.string.no_duplicate_files_found)
                    } else {
                        getString(
                            if (isSimilar) R.string.similar_files_count else R.string.duplicate_files_count,
                            media.size
                        )
                    }
                    beVisibleIf(media.isEmpty())
                }
                if (media.isNotEmpty()) {
                    showDuplicates(thumbnailItems, showFolderNames)
                }
            }
        }
    }

    private fun updateScanProgress(checked: Int, total: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed || shouldStop.get()) return@runOnUiThread
            val progressMax = total.coerceAtLeast(1)
            binding.duplicateFilesProgress.apply {
                if (isIndeterminate) {
                    isIndeterminate = false
                }
                max = progressMax
                setProgressCompat(checked.coerceIn(0, progressMax), false)
            }
            binding.duplicateFilesScanningText.text = getString(R.string.scan_files_progress, checked, total)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(
            DUPLICATE_SCAN_FOLDERS,
            intent.getStringArrayListExtra(DUPLICATE_SCAN_FOLDERS)
        )
        super.onSaveInstanceState(outState)
    }

    private fun showDuplicates(media: ArrayList<ThumbnailItem>, showFolderNames: Boolean) {
        val columns = config.mediaColumnCnt.coerceAtLeast(1)
        binding.duplicateFilesGrid.layoutManager = GridLayoutManager(this, columns)
        if (binding.duplicateFilesGrid.itemDecorationCount == 0) {
            binding.duplicateFilesGrid.addItemDecoration(
                GridSpacingItemDecoration(
                    spanCount = columns,
                    spacing = config.thumbnailSpacing,
                    isScrollingHorizontally = false,
                    addSideSpacing = config.fileRoundedCorners,
                    items = media,
                    useGridPosition = false
                )
            )
        }
        binding.duplicateFilesGrid.adapter = MediaAdapter(
            activity = this,
            media = media,
            listener = this,
            isAGetIntent = false,
            allowMultiplePicks = false,
            path = "",
            recyclerView = binding.duplicateFilesGrid,
            isDuplicateSelectionMode = true,
            mediumLabel = if (showFolderNames) {
                { medium -> File(medium.parentPath).name.ifEmpty { medium.parentPath } }
            } else {
                null
            }
        ) { item ->
            val medium = item as? Medium ?: return@MediaAdapter
            if (medium.isVideo()) {
                when (config.videoPlayerType) {
                    VIDEO_PLAYER_SYSTEM -> {
                        val visibleMedia = ArrayList(
                            (binding.duplicateFilesGrid.adapter as? MediaAdapter)?.media?.filterIsInstance<Medium>().orEmpty()
                        )
                        openPathWithViewerMedia(
                            path = medium.path,
                            forceChooser = false,
                            media = visibleMedia,
                            extras = hashMapOf(IS_FROM_GALLERY to true)
                        )
                    }
                    VIDEO_PLAYER_APP -> if (config.gestureVideoPlayer) {
                        launchGesturePlayer(medium.path)
                    } else {
                        openInViewPager(medium.path)
                    }
                    else -> openInViewPager(medium.path)
                }
            } else {
                openInViewPager(medium.path)
            }
        }
        binding.duplicateFilesGrid.beVisible()
    }

    override fun refreshItems() {
        runOnUiThread {
            val isEmpty = (binding.duplicateFilesGrid.adapter?.itemCount ?: 0) == 0
            if (isEmpty) {
                binding.duplicateFilesGrid.beGone()
                binding.duplicateFilesPlaceholder.setText(
                    if (isSimilarFiles) R.string.no_similar_files_found else R.string.no_duplicate_files_found
                )
                binding.duplicateFilesPlaceholder.beVisible()
            }
        }
    }

    override fun selectedPaths(paths: ArrayList<String>) {}

    override fun updateMediaGridDecoration(media: ArrayList<ThumbnailItem>) {
        if (binding.duplicateFilesGrid.itemDecorationCount > 0) {
            (binding.duplicateFilesGrid.getItemDecorationAt(0) as? GridSpacingItemDecoration)?.items = media
        }
    }

    override fun tryDeleteFiles(fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean) {
        val filtered = fileDirItems.filter { File(it.path).isFile && it.path.isMediaFile() } as ArrayList
        if (filtered.isEmpty()) return

        val shouldMoveToRecycleBin = config.useRecycleBin && !skipRecycleBin && !filtered.first().path.startsWith(recycleBinPath)
        if (shouldMoveToRecycleBin) {
            toast(resources.getQuantityString(org.fossify.commons.R.plurals.moving_items_into_bin, filtered.size, filtered.size))
            movePathsInRecycleBin(filtered.map { it.path } as ArrayList<String>) { success ->
                if (success) {
                    deleteFilteredFiles(filtered)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            toast(resources.getQuantityString(org.fossify.commons.R.plurals.deleting_items, filtered.size, filtered.size))
            deleteFilteredFiles(filtered)
        }
    }

    private fun deleteFilteredFiles(filtered: ArrayList<FileDirItem>) {
        deleteFiles(filtered) { success ->
            if (!success) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                return@deleteFiles
            }

            ensureBackgroundThread {
                val useRecycleBin = config.useRecycleBin
                filtered.filter { it.path.startsWith(recycleBinPath) || !useRecycleBin }
                    .forEach { deleteDBPath(it.path) }
                refreshItems()
            }
        }
    }

    private fun openInViewPager(path: String) {
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SHOW_ALL, false)
            val visibleMedia = ArrayList(
                (binding.duplicateFilesGrid.adapter as? MediaAdapter)?.media?.filterIsInstance<Medium>().orEmpty()
            )
            putExtra(VIEWER_MEDIA, visibleMedia)
            startActivity(this)
        }
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

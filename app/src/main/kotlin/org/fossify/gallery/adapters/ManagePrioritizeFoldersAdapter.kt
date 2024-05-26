package org.fossify.gallery.adapters

import android.view.*
import android.widget.PopupMenu
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.databinding.ItemManageFolderBinding
import org.fossify.gallery.extensions.config

class ManagePrioritizeFoldersAdapter(
    activity: BaseSimpleActivity, var entries: ArrayList<String>, val listener: RefreshRecyclerViewListener?,
    recyclerView: MyRecyclerView, itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    private val config = activity.config

    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = org.fossify.commons.R.menu.cab_remove_only

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        when (id) {
            org.fossify.commons.R.id.cab_remove -> removeSelection()
        }
    }

    override fun getSelectableItemCount() = entries.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = entries.getOrNull(position)?.hashCode()

    override fun getItemKeyPosition(key: Int) = entries.indexOfFirst { it.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(ItemManageFolderBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.bindView(entry, true, true) { itemView, adapterPosition ->
            setupView(itemView, entry)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = entries.size

    private fun getSelectedItems() = entries.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<String>

    private fun setupView(view: View, folder: String) {
        ItemManageFolderBinding.bind(view).apply {
            root.setupViewBackground(activity)
            manageFolderHolder.isSelected = selectedKeys.contains(folder.hashCode())
            manageFolderTitle.apply {
                text = folder
                setTextColor(context.getProperTextColor())
            }

            overflowMenuIcon.drawable.apply {
                mutate()
                setTint(activity.getProperTextColor())
            }

            overflowMenuIcon.setOnClickListener {
                showPopupMenu(overflowMenuAnchor, folder)
            }
        }
    }

    private fun showPopupMenu(view: View, folder: String) {
        finishActMode()
        val theme = activity.getPopupMenuTheme()
        val contextTheme = ContextThemeWrapper(activity, theme)

        PopupMenu(contextTheme, view, Gravity.END).apply {
            inflate(getActionMenuId())
            setOnMenuItemClickListener { item ->
                val eventTypeId = folder.hashCode()
                when (item.itemId) {
                    org.fossify.commons.R.id.cab_remove -> {
                        executeItemMenuOperation(eventTypeId) {
                            removeSelection()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun executeItemMenuOperation(eventTypeId: Int, callback: () -> Unit) {
        selectedKeys.clear()
        selectedKeys.add(eventTypeId)
        callback()
    }

    private fun removeSelection() {
        val removeFolders = ArrayList<String>(selectedKeys.size)
        val positions = getSelectedItemPositions()

        getSelectedItems().forEach {
            removeFolders.add(it)
            config.removePrioritizedFolder(it)
        }

        entries.removeAll(removeFolders)
        removeSelectedItems(positions)
        if (entries.isEmpty()) {
            listener?.refreshItems()
        }
    }
}

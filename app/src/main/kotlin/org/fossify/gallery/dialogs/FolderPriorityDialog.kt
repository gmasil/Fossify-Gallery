package org.fossify.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.*
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogFolderPriorityBinding

class FolderPriorityDialog(val activity: BaseSimpleActivity, val callback: (contains: String, priority: Int) -> Unit) {
    init {
        val binding = DialogFolderPriorityBinding.inflate(activity.layoutInflater).apply {
            priorityValue.setText("5")
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel) { dialog, which -> }
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.prioritized_folders) { alertDialog ->
                    alertDialog.showKeyboard(binding.patternValue)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val pattern = binding.patternValue.text.toString()
                        val priorityString = binding.priorityValue.text.toString()

                        if (pattern.isEmpty()) {
                            activity.toast(R.string.folder_name_contains_empty)
                            return@setOnClickListener
                        }

                        if (priorityString.isEmpty()) {
                            activity.toast(R.string.priority_empty)
                            return@setOnClickListener
                        }

                        val priority = try {
                            priorityString.toInt()
                        } catch (nfe: NumberFormatException) {
                            activity.toast(R.string.priority_int)
                            return@setOnClickListener
                        }

                        if (priority < 1 || priority > 10) {
                            activity.toast(R.string.priority_range)
                            return@setOnClickListener
                        }

                        callback.invoke(pattern, priority)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}

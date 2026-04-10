package de.felixnuesse.extract.notifications.implementations

import android.content.Context
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.notifications.prototypes.WorkerNotification
import ca.pkay.rcloneexplorer.notifications.support.StatusObject

class ArchiveWorkerNotification(var context: Context) : WorkerNotification(context) {

    override val CHANNEL_ID = "ca.pkay.rcloneexplorer.archive_service"

    override val initialTitle = string(R.string.worker_archive_initialtitle)
    override val serviceOngoingTitle = initialTitle
    override val serviceSuccess = mContext.getString(R.string.worker_archive_success)
    override val serviceFailed = mContext.getString(R.string.worker_archive_failed)
    override val serviceCancelled = mContext.resources.getText(R.string.worker_archive_cancelled).toString()

    override val CHANNEL_SUCCESS_ID = CHANNEL_ID
    override val CHANNEL_FAIL_ID = CHANNEL_ID

    override val channel_ongoing_title = string(R.string.archive_service_notification_title)
    override val channel_ongoing_description = string(R.string.archive_service_notification_description)
    override val channel_success_title = channel_ongoing_title
    override val channel_success_description = channel_ongoing_description
    override val channel_failed_title = channel_ongoing_title
    override val channel_failed_description = channel_ongoing_description

    override val PERSISTENT_NOTIFICATION_ID = 50
    override val SUMMARY_ID = 51

    override fun generateSuccessMessage(statusObject: StatusObject, fileItem: FileItem): String {
        return mContext.resources.getQuantityString(
            R.plurals.worker_archive_success_message,
            statusObject.manualSuccessCount,
            fileItem.name
        )
    }
}

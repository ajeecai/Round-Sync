package ca.pkay.rcloneexplorer.archive

import android.os.Parcel
import androidx.work.Data
import androidx.work.ListenableWorker
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import de.felixnuesse.extract.notifications.implementations.ArchiveWorkerNotification
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.workmanager.EphemeralWorker
import ca.pkay.rcloneexplorer.workmanager.Type
import de.felixnuesse.extract.extensions.tag
import java.io.File

class ArchiveTaskRunner(private val worker: EphemeralWorker) {

    companion object {
        fun getArchiveFileItem(type: Type, inputData: Data): FileItem {
            return when (type) {
                Type.REMOTE_ARCHIVE -> {
                    FileItem(RemoteItem("", ""), "", "Archive items", 0L, "2020-01-01T00:00:00Z", "mimeType", false, false)
                }
                Type.UPLOAD_ARCHIVE -> {
                    val uploadPaths = inputData.getStringArray("UPLOAD_PATHS")
                    val name = if (uploadPaths != null && uploadPaths.isNotEmpty()) File(uploadPaths[0]).name else "Archive items"
                    FileItem(RemoteItem("", ""), "", name, 0L, "2020-01-01T00:00:00Z", "mimeType", false, false)
                }
                else -> FileItem(RemoteItem("", ""), "", "Error", 0L, "", "", false, false)
            }
        }
    }

    fun run(type: Type, remoteItem: RemoteItem): ListenableWorker.Result {
        return when (type) {
            Type.REMOTE_ARCHIVE -> handleArchive(worker.inputData, remoteItem)
            Type.UPLOAD_ARCHIVE -> handleUploadArchive(worker.inputData, remoteItem)
            else -> ListenableWorker.Result.failure()
        }
    }

    private fun handleArchive(inputData: Data, remoteItem: RemoteItem): ListenableWorker.Result {
        val context = worker.mContext
        val granularityStr = inputData.getString("ARCHIVE_GRANULARITY") ?: "MONTH"
        val granularity = ArchiveConfig.Granularity.valueOf(granularityStr)
        val itemsFile = inputData.getString("ARCHIVE_ITEMS_FILE")
        if (itemsFile == null) {
            return ListenableWorker.Result.failure()
        }

        val items = mutableListOf<FileItem>()
        val file = File(itemsFile)
        if (file.exists()) {
            val bytes = file.readBytes()
            val parcel = Parcel.obtain()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val count = parcel.readInt()
            for (i in 0 until count) {
                items.add(FileItem.CREATOR.createFromParcel(parcel))
            }
            parcel.recycle()
            file.delete() // Clean up temp file
        }

        val archiveManager = RemoteArchiveManager(context, remoteItem.name)
        val sourcePath = inputData.getString("ARCHIVE_SOURCE_PATH") ?: ""
        archiveManager.setListener(object : RemoteArchiveManager.ArchiveProgressListener {
            override fun onProgress(current: Int, total: Int, currentFile: String) {
                val percent = (current * 100) / total
                val content = context.getString(R.string.archive_progress, current, total, currentFile)
                worker.updateNotificationInternal(ArchiveWorkerNotification(context).updateNotification(
                    worker.mTitle,
                    content,
                    ArrayList(),
                    percent,
                    worker.ongoingNotificationID
                ))

                // Internal progress for UI
                val progressData = Data.Builder()
                    .putInt("PROGRESS_CURRENT", current)
                    .putInt("PROGRESS_TOTAL", total)
                    .putString("PROGRESS_FILE", currentFile)
                    .putString("ARCHIVE_TITLE", inputData.getString("ARCHIVE_TITLE"))
                    .build()
                worker.setProgressInternal(progressData)
            }

            override fun onNewTargetDirectory(targetDir: String) {
                var path = targetDir
                while (path.contains("/")) {
                    path = path.substringBeforeLast("/")
                    worker.sendProgressBroadcast(remoteItem.name, path)
                }
                worker.sendProgressBroadcast(remoteItem.name, "//" + remoteItem.name)
            }

            override fun onFileArchived(targetDir: String) {
                // Single file archive is an intermidiate step so use Progress broadcast type
                // When all files are archived, onComplete will trigger a Finished broadcast
                worker.sendProgressBroadcast(remoteItem.name, targetDir)
            }

            override fun onComplete(archived: Int, skipped: Int, failed: Int) {
                worker.statusObject.manualSuccessCount = archived
                worker.statusObject.manualSkippedCount = skipped
            }
        })

        return try {
            archiveManager.archiveItems(remoteItem, items, granularity)
            FLog.d(tag(), "handleArchive done, sending broadcast: remote='${remoteItem.name}' path='$sourcePath'")
            worker.sendActionFinishedBroadcast(remoteItem.name, sourcePath)
            ListenableWorker.Result.success()
        } catch (e: RemoteArchiveManager.ArchiveLimitExceededException) {
            worker.failureReason = EphemeralWorker.FAILURE_REASON.TOO_MANY_FILES
            ListenableWorker.Result.failure()
        }
    }

    private fun handleUploadArchive(inputData: Data, remoteItem: RemoteItem): ListenableWorker.Result {
        val context = worker.mContext
        val granularityStr = inputData.getString("ARCHIVE_GRANULARITY") ?: "MONTH"
        val granularity = ArchiveConfig.Granularity.valueOf(granularityStr)
        val uploadPaths = inputData.getStringArray("UPLOAD_PATHS")?.toList() ?: emptyList()

        val uploadArchiveManager = UploadArchiveManager(context, remoteItem.name)
        uploadArchiveManager.setListener(object : UploadArchiveManager.UploadArchiveProgressListener {
            override fun onProgress(current: Int, total: Int, currentFile: String) {
                val percent = (current * 100) / total
                val content = context.getString(R.string.archive_progress, current, total, currentFile)
                worker.updateNotificationInternal(ArchiveWorkerNotification(context).updateNotification(
                    worker.mTitle,
                    content,
                    ArrayList(),
                    percent,
                    worker.ongoingNotificationID
                ))

                // Internal progress for UI
                val progressData = Data.Builder()
                    .putInt("PROGRESS_CURRENT", current)
                    .putInt("PROGRESS_TOTAL", total)
                    .putString("PROGRESS_FILE", currentFile)
                    .putString("ARCHIVE_TITLE", inputData.getString("ARCHIVE_TITLE"))
                    .build()
                worker.setProgressInternal(progressData)
            }

            override fun onComplete(uploaded: Int, skipped: Int, failed: Int) {
                worker.statusObject.manualSuccessCount = uploaded
                worker.statusObject.manualSkippedCount = skipped
            }
        })

        return try {
            uploadArchiveManager.uploadAndArchive(remoteItem, uploadPaths, granularity)
            val targetPath = inputData.getString("ARCHIVE_TARGET_PATH") ?: ""
            worker.sendActionFinishedBroadcast(remoteItem.name, targetPath)
            ListenableWorker.Result.success()
        } catch (e: RemoteArchiveManager.ArchiveLimitExceededException) {
            worker.failureReason = EphemeralWorker.FAILURE_REASON.TOO_MANY_FILES
            ListenableWorker.Result.failure()
        }
    }
}

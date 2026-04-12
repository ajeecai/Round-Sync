package ca.pkay.rcloneexplorer.archive

import android.content.Context
import android.os.Parcel
import androidx.work.Data
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.workmanager.EphemeralTaskManager
import ca.pkay.rcloneexplorer.workmanager.EphemeralWorker
import ca.pkay.rcloneexplorer.workmanager.Type
import de.felixnuesse.extract.notifications.implementations.ArchiveWorkerNotification
import java.io.File

object ArchiveTaskLauncher {
    
    const val ARCHIVE_TASK_TAG = "ARCHIVE_TASK"

    @JvmStatic
    fun queueRemoteArchive(
        context: Context,
        remote: RemoteItem,
        items: List<FileItem>,
        granularity: ArchiveConfig.Granularity,
        sourcePath: String = ""
    ) {
        ArchiveWorkerNotification(context).generateChannels()

        val data = Data.Builder()
        data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.REMOTE_ARCHIVE.name)
        EphemeralTaskManager.addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)
        data.putString("ARCHIVE_GRANULARITY", granularity.name)
        data.putString("ARCHIVE_SOURCE_PATH", sourcePath)
        
        val title = if (items.isNotEmpty()) items[0].name else "Archive collection"
        data.putString("ARCHIVE_TITLE", title)
        data.putInt("MAX_FILES", ArchiveConfig.MAX_ARCHIVE_FILES)
        
        // Add placeholder FileItem for notifications
        val placeholder = FileItem(RemoteItem("", ""), "", title, 0L, "2020-01-01T00:00:00Z", "mimeType", false, false)
        EphemeralTaskManager.addFileItemToData(EphemeralWorker.EXTRA_FILE, placeholder, data)

        // Save items to a temp file to avoid 10KB limit
        val tempFile = File.createTempFile("archive_items", ".bin", context.cacheDir)
        tempFile.outputStream().use { out ->
            val parcel = Parcel.obtain()
            parcel.writeInt(items.size)
            for (item in items) {
                item.writeToParcel(parcel, 0)
            }
            out.write(parcel.marshall())
            parcel.recycle()
        }
        data.putString("ARCHIVE_ITEMS_FILE", tempFile.absolutePath)

        EphemeralTaskManager(context).work(data.build(), ARCHIVE_TASK_TAG)
    }

    @JvmStatic
    fun queueUploadArchive(
        context: Context,
        remote: RemoteItem,
        localPaths: List<String>,
        granularity: ArchiveConfig.Granularity,
        targetPath: String = ""
    ) {
        ArchiveWorkerNotification(context).generateChannels()

        val data = Data.Builder()
        data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.UPLOAD_ARCHIVE.name)
        EphemeralTaskManager.addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)
        data.putString("ARCHIVE_GRANULARITY", granularity.name)
        data.putStringArray("UPLOAD_PATHS", localPaths.toTypedArray())
        data.putString("ARCHIVE_TARGET_PATH", targetPath)

        val title = if (localPaths.isNotEmpty()) File(localPaths[0]).name else "Multiple uploads"
        data.putString("ARCHIVE_TITLE", title)
        data.putInt("MAX_FILES", ArchiveConfig.MAX_ARCHIVE_FILES)

        // Add placeholder FileItem for notifications
        val placeholder = FileItem(RemoteItem("", ""), "", title, 0L, "2020-01-01T00:00:00Z", "mimeType", false, false)
        EphemeralTaskManager.addFileItemToData(EphemeralWorker.EXTRA_FILE, placeholder, data)

        EphemeralTaskManager(context).work(data.build(), ARCHIVE_TASK_TAG)
    }
}

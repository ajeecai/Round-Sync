package ca.pkay.rcloneexplorer.workmanager

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Parcel
import android.util.Log
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.archive.ArchiveConfig
import ca.pkay.rcloneexplorer.archive.ArchiveTaskRunner
import ca.pkay.rcloneexplorer.notifications.prototypes.WorkerNotification
import ca.pkay.rcloneexplorer.notifications.support.StatusObject
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import de.felixnuesse.extract.extensions.tag
import de.felixnuesse.extract.notifications.implementations.ArchiveWorkerNotification
import de.felixnuesse.extract.notifications.implementations.DeleteWorkerNotification
import de.felixnuesse.extract.notifications.implementations.DownloadWorkerNotification
import de.felixnuesse.extract.notifications.implementations.MoveWorkerNotification
import de.felixnuesse.extract.notifications.implementations.UploadWorkerNotification
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import kotlin.random.Random

class EphemeralWorker (internal var mContext: Context, workerParams: WorkerParameters): Worker(mContext, workerParams) {


    companion object {
        const val EPHEMERAL_TYPE = "TASK_EPHEMERAL_TYPE"
        const val REMOTE = "REMOTE"

        const val DOWNLOAD_TARGETPATH = "DOWNLOAD_TARGETPATH"
        const val DOWNLOAD_SOURCE = "DOWNLOAD_SOURCE"

        const val UPLOAD_FILE = "UPLOAD_FILE"
        const val UPLOAD_TARGETPATH = "UPLOAD_TARGETPATH"

        const val MOVE_FILE = "MOVE_FILE"
        const val MOVE_TARGETPATH = "MOVE_TARGETPATH"

        const val DELETE_FILE = "DELETE_FILE"
        const val EXTRA_FILE = "EXTRA_FILE"

        // Use to broadcast the directory where the action happened:
        // source dir for MOVE, current dir for DELETE.
        // UPLOAD uses UPLOAD_TARGETPATH directly instead.
        const val BROADCAST_PATH = "BROADCAST_PATH"

        const val BROADCAST_TYPE = "BROADCAST_TYPE"
        const val BROADCAST_TYPE_FINISHED = "FINISHED"
        const val BROADCAST_TYPE_PROGRESS = "PROGRESS"
    }

    internal enum class FAILURE_REASON {
        NO_FAILURE, NO_UNMETERED, NO_CONNECTION, RCLONE_ERROR, CONNECTIVITY_CHANGED, CANCELLED, NO_TASK, TOO_MANY_FILES
    }

    // Objects
    internal var mNotificationManager: WorkerNotification? = null
    private val mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext)

    // States
    private val sIsLoggingEnabled = mPreferences.getBoolean(getString(R.string.pref_key_logs), false)
    private var sConnectivityChanged = false

    private var sRcloneProcess: Process? = null
    internal val statusObject = StatusObject(mContext)
    internal var failureReason = FAILURE_REASON.NO_FAILURE
    private var endNotificationAlreadyPosted = false
    private var silentRun = false
    internal val ongoingNotificationID = Random.nextInt()


    internal val mTitle: String by lazy {
        inputData.getString("ARCHIVE_TITLE") ?: mNotificationManager?.initialTitle ?: ""
    }

    internal val inputData = workerParams.inputData

    override fun doWork(): Result {

        registerBroadcastReceivers()

        updateForegroundNotification(mNotificationManager?.updateNotification(
            mTitle,
            mTitle,
            ArrayList(),
            0,
            ongoingNotificationID
        ))

        if (inputData.keyValueMap.containsKey(EPHEMERAL_TYPE)){
            val type = Type.valueOf(inputData.getString(EPHEMERAL_TYPE) ?: "")
            mNotificationManager = prepareNotificationManager(type)

            val remoteItem = getRemoteitemFromParcel(REMOTE)
            if(remoteItem == null){
                log("$REMOTE: No valid remote was passed!")
                return Result.failure()
            }

            mNotificationManager?.setCancelId(id)
            var broadcastPath: String? = null
            var broadcastPath2: String? = null
            if(preconditionsMet()) {
                // do not instantiate rclone when you dont want it to run.
                // It will immediately run!
                when(type){
                    Type.DOWNLOAD -> {
                        val target = inputData.getString(DOWNLOAD_TARGETPATH)
                        val fileItem = getFileitemFromParcel(DOWNLOAD_SOURCE)

                        if(fileItem == null){
                            log("$DOWNLOAD_SOURCE: No valid target was passed!")
                            return Result.failure()
                        }

                        sRcloneProcess = Rclone(mContext).downloadFile(
                            remoteItem,
                            fileItem,
                            target
                        )
                    }
                    Type.UPLOAD -> {
                        val target = inputData.getString(UPLOAD_TARGETPATH)
                        val file = inputData.getString(UPLOAD_FILE)

                        sRcloneProcess = Rclone(mContext).uploadFile(
                            remoteItem,
                            target,
                            file
                        )
                        broadcastPath = target
                    }
                    Type.MOVE -> {
                        val target = inputData.getString(MOVE_TARGETPATH)
                        val fileItem = getFileitemFromParcel(MOVE_FILE)

                        if(fileItem == null){
                            log("$MOVE_FILE: No valid target was passed!")
                            return Result.failure()
                        }

                        sRcloneProcess = Rclone(mContext).moveTo(
                            remoteItem,
                            fileItem,
                            target
                        )
                        broadcastPath = inputData.getString(BROADCAST_PATH)
                        broadcastPath2 = target
                    }
                    Type.DELETE -> {
                        val fileItem = getFileitemFromParcel(DELETE_FILE)

                        if(fileItem == null){
                            log("$DELETE_FILE: No valid target was passed!")
                            return Result.failure()
                        }

                        sRcloneProcess = Rclone(mContext).deleteItems(
                            remoteItem,
                            fileItem
                        )
                        broadcastPath = inputData.getString(BROADCAST_PATH)
                    }
                    Type.REMOTE_ARCHIVE, Type.UPLOAD_ARCHIVE -> {
                        return ArchiveTaskRunner(this).run(type, remoteItem)
                    }
                }
                handleSync(mTitle)
            } else {
                log("Preconditions are not met!")
                postSync()
                return Result.failure()
            }

            postSync()
            sendActionFinishedBroadcast(remoteItem.name, broadcastPath, broadcastPath2)
            // Indicate whether the work finished successfully with the Result
            return Result.success()
        }
        log("Critical: No valid ephemeral type passed!")
        return Result.failure()
    }

    override fun onStopped() {
        super.onStopped()
        SyncLog.info(mContext, mTitle, mContext.getString(R.string.operation_sync_cancelled))
        SyncLog.info(mContext, mTitle, statusObject.toString())
        failureReason = FAILURE_REASON.CANCELLED
        finishWork()
    }

    private fun finishWork() {
        sRcloneProcess?.destroy()
        mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
        postSync()
    }

    fun prepareNotificationManager(type: Type): WorkerNotification? {
        return when(type){
            Type.DOWNLOAD -> DownloadWorkerNotification(mContext)
            Type.UPLOAD -> UploadWorkerNotification(mContext)
            Type.DELETE -> DeleteWorkerNotification(mContext)
            Type.MOVE -> MoveWorkerNotification(mContext)
            Type.REMOTE_ARCHIVE, Type.UPLOAD_ARCHIVE -> ArchiveWorkerNotification(mContext)
        }
    }

    private fun handleSync(title: String) {
        if (sRcloneProcess != null) {
            val localProcessReference = sRcloneProcess!!
            try {
                val reader = BufferedReader(InputStreamReader(localProcessReference.errorStream))
                val iterator = reader.lineSequence().iterator()
                while(iterator.hasNext()) {
                    val line = iterator.next()
                    try {
                        val logline = JSONObject(line)
                        //todo: migrate this to StatusObject, so that we can handle everything properly.
                        if (logline.getString("level") == "error") {
                            if (sIsLoggingEnabled) {
                                FLog.e(tag(), "Rclone error: %s", line)
                            }
                            statusObject.parseLoglineToStatusObject(logline)
                        } else if (logline.getString("level") == "warning") {
                            statusObject.parseLoglineToStatusObject(logline)
                        }

                        updateForegroundNotification(mNotificationManager?.updateNotification(
                            title,
                            statusObject.notificationContent,
                            statusObject.notificationBigText,
                            statusObject.notificationPercent,
                            ongoingNotificationID
                        ))
                    } catch (e: JSONException) {
                        Log.e(tag(), "Error: the offending line: $line")
                    }
                }
            } catch (e: InterruptedIOException) {
                FLog.e(tag(), "onHandleIntent: I/O interrupted, stream closed", e)
            } catch (e: IOException) {
                FLog.e(tag(), "onHandleIntent: error reading stdout", e)
            }
            try {
                localProcessReference.waitFor()
            } catch (e: InterruptedException) {
                FLog.e(tag(), "onHandleIntent: error waiting for process", e)
            }
        } else {
            log("Sync: No Rclone Process!")
        }
        mNotificationManager?.cancelSyncNotification(ongoingNotificationID)
    }

    private fun postSync() {
        if (endNotificationAlreadyPosted) {
            return
        }
        if (silentRun) {
            return
        }

        val notificationId = System.currentTimeMillis().toInt()

        var content = mContext.getString(R.string.operation_failed_unknown, mTitle)
        when (failureReason) {
            FAILURE_REASON.NO_FAILURE -> {
                showSuccessNotification(notificationId)
                return
            }
            FAILURE_REASON.CANCELLED -> {
                showCancelledNotification(notificationId)
                endNotificationAlreadyPosted = true
                return
            }
            FAILURE_REASON.NO_TASK -> {
                content = getString(R.string.operation_failed_notask)
            }
            FAILURE_REASON.CONNECTIVITY_CHANGED -> {
                content = mContext.getString(R.string.operation_failed_data_change, mTitle)
            }
            FAILURE_REASON.NO_UNMETERED -> {
                content = mContext.getString(R.string.operation_failed_no_unmetered, mTitle)
            }
            FAILURE_REASON.NO_CONNECTION -> {
                content = mContext.getString(R.string.operation_failed_no_connection, mTitle)
            }
            FAILURE_REASON.TOO_MANY_FILES -> {
                content = mContext.getString(R.string.archive_too_many_files, ArchiveConfig.MAX_ARCHIVE_FILES)
            }
            FAILURE_REASON.RCLONE_ERROR -> {
                content = mContext.getString(R.string.operation_failed_unknown_rclone_error, mTitle)
            }
        }
        showFailNotification(notificationId, content)
        endNotificationAlreadyPosted = true
        finishWork()
    }

    private fun showCancelledNotification(notificationId: Int) {
        val content = mContext.getString(R.string.operation_failed_cancelled)
        SyncLog.info(mContext, mTitle, content)
        mNotificationManager?.showCancelledNotification(
            mTitle,
            content,
            notificationId,
            0
        )
    }

    private fun showSuccessNotification(notificationId: Int) {
        var message = mNotificationManager?.generateSuccessMessage(statusObject, getCurrentFile())?: "error"

        mNotificationManager?.showSuccessNotification(
            mTitle,
            message,
            notificationId
        )

        message += """
                        
        Est. Speed: ${statusObject.getEstimatedAverageSpeed()}
        Avg. Speed: ${statusObject.getLastItemAverageSpeed()}
                        """.trimIndent()
        //SyncLog.info(mContext, mContext.getString(R.string.operation_success, mTitle), message)
    }

    internal fun updateNotificationInternal(notification: Notification?) {
        updateForegroundNotification(notification)
    }

    internal fun setProgressInternal(data: androidx.work.Data) {
        setProgressAsync(data)
    }

    private fun showFailNotification(notificationId: Int, content: String, wasCancelled: Boolean = false) {
        var text = content
        statusObject.printErrors()
        val errors = statusObject.getAllErrorMessages()
        if (errors.isNotEmpty()) {
            text += """
                        
                        
                        
                        ${statusObject.getAllErrorMessages()}
                        """.trimIndent()
        }

        var notifyTitle = mContext.getString(R.string.operation_failed)
        if (wasCancelled) {
            notifyTitle = mContext.getString(R.string.operation_failed_cancelled)
        }
        SyncLog.error(mContext, notifyTitle, "$mTitle: $text")
        mNotificationManager?.showFailedNotification(
            mTitle,
            text,
            notificationId,
           0
        )
    }

    private fun preconditionsMet(): Boolean {
        val wifiOnly = mPreferences.getBoolean(mContext.getString(R.string.pref_key_wifi_only_transfers), false)
        val connection = WifiConnectivitiyUtil.dataConnection(this.applicationContext)
        if (wifiOnly && connection === WifiConnectivitiyUtil.Connection.METERED) {
            failureReason = FAILURE_REASON.NO_UNMETERED
            return false
        } else if (connection === WifiConnectivitiyUtil.Connection.DISCONNECTED || connection === WifiConnectivitiyUtil.Connection.NOT_AVAILABLE) {
            failureReason = FAILURE_REASON.NO_CONNECTION
            return false
        }

        return true
    }

    internal fun sendActionFinishedBroadcast(remote: String, path: String?, path2: String? = null) {
        val intent = Intent()
        intent.action = getString(R.string.background_service_broadcast)
        intent.putExtra(getString(R.string.background_service_broadcast_data_remote), remote)
        intent.putExtra(getString(R.string.background_service_broadcast_data_path), path)
        intent.putExtra(BROADCAST_TYPE, BROADCAST_TYPE_FINISHED)
        if (path2 != null) {
            intent.putExtra(getString(R.string.background_service_broadcast_data_path2), path2)
        }
        FLog.d(tag(), "sendActionFinishedBroadcast: remote='$remote' path='$path' path2='$path2'")
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent)
    }

    internal fun sendProgressBroadcast(remote: String, path: String) {
        val intent = Intent()
        intent.action = getString(R.string.background_service_broadcast)
        intent.putExtra(getString(R.string.background_service_broadcast_data_remote), remote)
        intent.putExtra(getString(R.string.background_service_broadcast_data_path), path)
        intent.putExtra(BROADCAST_TYPE, BROADCAST_TYPE_PROGRESS)
        FLog.d(tag(), "sendProgressBroadcast: remote='$remote' path='$path'")
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent)
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun updateForegroundNotification(notification: Notification?) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            notification?.let {
                setForegroundAsync(ForegroundInfo(ongoingNotificationID, it, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            }
        }
    }


    private fun log(message: String) {
        FLog.e(tag(), "EphemeralWorker: $message")
    }

    private fun getString(@StringRes resId: Int?): String {
        return if (resId == null) {
            "Error"
        } else {
            mContext.getString(resId)
        }
    }

    private fun registerBroadcastReceivers() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)
        mContext.registerReceiver(connectivityChangeBroadcastReceiver, intentFilter)
    }

    private val connectivityChangeBroadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if(endNotificationAlreadyPosted){
                    return
                }
                sConnectivityChanged = true
                failureReason = FAILURE_REASON.CONNECTIVITY_CHANGED
            }
        }

    private fun getFileitemFromParcel(key: String): FileItem {

        val sourceParcelByteArray = inputData.getByteArray(key)
        if(sourceParcelByteArray == null){
            log("No valid target was passed!")
            throw NullPointerException("The passed FileItem was null. We cannot continue!")
        }

        val parcel = Parcel.obtain()
        parcel.unmarshall(sourceParcelByteArray, 0, sourceParcelByteArray.size)
        parcel.setDataPosition(0)
        return FileItem.CREATOR.createFromParcel(parcel)
    }

    private fun getRemoteitemFromParcel(key: String): RemoteItem? {

        val sourceParcelByteArray = inputData.getByteArray(key)
        if(sourceParcelByteArray == null){
            log("No valid target was passed!")
            return null
        }

        val parcel = Parcel.obtain()
        parcel.unmarshall(sourceParcelByteArray, 0, sourceParcelByteArray.size)
        parcel.setDataPosition(0)
        return RemoteItem.CREATOR.createFromParcel(parcel)
    }

    private fun getCurrentFile(): FileItem {
        if (inputData.keyValueMap.containsKey(EXTRA_FILE)) {
            return getFileitemFromParcel(EXTRA_FILE)
        }
        val type = Type.valueOf(inputData.getString(EPHEMERAL_TYPE)?:Type.DOWNLOAD.name)
        return when(type){
            Type.DOWNLOAD -> {
                getFileitemFromParcel(DOWNLOAD_SOURCE)
            }
            Type.UPLOAD -> {
                val pathAndName = inputData.getString(UPLOAD_FILE) ?: ""
                val name = pathAndName.substring(pathAndName.lastIndexOf("/")+1, pathAndName.length)
                val path = pathAndName.substring(0, pathAndName.lastIndexOf("/")+1)
                // TODO: Make this work properly! All the params are guessed!
                FileItem(
                        RemoteItem("", ""),
                        path,
                        name,
                        0L,
                        "modTime",
                        "mimeType",
                        false,
                        false)
            }
            Type.MOVE -> {
                getFileitemFromParcel(MOVE_FILE)
            }
            Type.DELETE -> {
                getFileitemFromParcel(DELETE_FILE)
            }
            Type.REMOTE_ARCHIVE, Type.UPLOAD_ARCHIVE -> {
                ArchiveTaskRunner.getArchiveFileItem(type, inputData)
            }
        }
    }
}

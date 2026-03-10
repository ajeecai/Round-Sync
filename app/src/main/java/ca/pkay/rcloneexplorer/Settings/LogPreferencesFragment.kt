package ca.pkay.rcloneexplorer.Settings

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.FLog
import de.felixnuesse.extract.extensions.tag
import de.felixnuesse.extract.settings.preferences.ButtonPreference
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.regex.Pattern


class LogPreferencesFragment : PreferenceFragmentCompat() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_logging_preferences, rootKey)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        requireActivity().title = getString(R.string.logging_settings_header)

        val sigkill = findPreference<Preference>("TempKeySigquit") as ButtonPreference
        sigkill.setButtonText(getString(R.string.pref_send_sigquit_button))
        sigkill.setButtonOnClick {
            sigquitAll()
        }

        val exportLogs = findPreference<Preference>("pref_key_export_logs") as ButtonPreference
        exportLogs.setButtonText(getString(R.string.export_logs))
        exportLogs.setButtonOnClick { exportLogsToFile() }

        val clearLogs = findPreference<Preference>("pref_key_clear_logs") as ButtonPreference
        clearLogs.setButtonText(getString(R.string.clear_logs))
        clearLogs.setButtonOnClick { confirmClearLogs() }

        // Set up password field to hide actual value in summary and input
        val passwordPref = findPreference<EditTextPreference>(getString(R.string.pref_key_log_upload_password))
        passwordPref?.apply {
            // Hide password in summary
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                val password = preference.text
                if (password.isNullOrEmpty()) {
                    getString(R.string.not_set)
                } else {
                    "••••••••"
                }
            }
            // Hide password in input dialog
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

    }


    private fun sigquitAll() {
        Toast.makeText(context, "Round Sync: Stopping everything", Toast.LENGTH_LONG).show()
        try {
            val runtime = Runtime.getRuntime()
            val process = runtime.exec("ps")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            val output = StringBuilder()
            while ((reader.readLine().also { line = it }) != null) {
                output.append('\n')
                output.append(line)
            }

            process.waitFor()

            val regex = "\\s+(\\d+)\\s+\\d+\\s+\\d+\\s+.+librclone.+$"
            val pattern = Pattern.compile(regex, Pattern.MULTILINE)
            val matcher = pattern.matcher(output.toString())

            while (matcher.find()) {
                for (i in 1..matcher.groupCount()) {
                    val pidMatch = matcher.group(i) ?: continue
                    val pid = pidMatch.toInt()
                    FLog.i(tag(), "SIGQUIT to process pid=%s", pid)
                    Process.sendSignal(pid, Process.SIGNAL_QUIT)
                }
            }
            Process.killProcess(Process.myPid())
        } catch (e: IOException) {
            FLog.e(tag(), "Error executing shell commands", e)
        } catch (e: InterruptedException) {
            FLog.e(tag(), "Error executing shell commands", e)
        }
    }
    private fun confirmClearLogs() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_logs)
            .setMessage(R.string.confirm_clear_logs_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> clearLogFiles() }
            .show()
    }

    private fun clearLogFiles() {
        try {
            val dir = requireContext().getExternalFilesDir("logs")
            if (dir != null && dir.exists()) {
                dir.listFiles()?.forEach { f ->
                    try { if (f.isFile) f.delete() } catch (_: Exception) {}
                }
            }
            Toast.makeText(context, getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FLog.e(tag(), "clearLogFiles: error", e)
            Toast.makeText(context, getString(R.string.log_upload_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportLogsToFile() {
        Toast.makeText(context, "Exporting logs...", Toast.LENGTH_SHORT).show()
        
        Thread {
            try {
                // Get Downloads directory
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val androidId = android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                val fileName = "rclone-logs-${androidId}-${System.currentTimeMillis()}.txt"
                val outputFile = java.io.File(downloadsDir, fileName)
                
                val writer = java.io.BufferedWriter(java.io.FileWriter(outputFile))
                
                // Write rclone log files
                val logsDir = requireContext().getExternalFilesDir("logs")
                if (logsDir != null && logsDir.exists()) {
                    logsDir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            writer.write("===== ${f.name} =====\n")
                            f.bufferedReader().use { reader ->
                                reader.lineSequence().forEach { line ->
                                    writer.write(line)
                                    writer.write("\n")
                                }
                            }
                            writer.write("\n\n")
                        }
                    }
                }
                
                // Write Android logcat - filtered using centralized filter
                writer.write("===== android_logcat.txt =====\n")
                try {
                    val logcatProcess = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
                    val reader = BufferedReader(InputStreamReader(logcatProcess.inputStream))
                    val packageName = requireContext().packageName
                    
                    reader.lineSequence().forEach { line ->
                        // Use centralized filter to ensure consistency with sendLogs
                        if (ca.pkay.rcloneexplorer.util.LogFilterUtil.shouldIncludeLogLine(line, packageName)) {
                            writer.write(line)
                            writer.write("\n")
                        }
                    }
                    reader.close()
                    logcatProcess.destroy()
                } catch (e: Exception) {
                    writer.write("Failed to collect logcat: ${e.message}\n")
                }
                
                writer.close()
                
                // Show Snackbar with View button on main thread
                requireActivity().runOnUiThread {
                    val message = getString(R.string.export_logs_success, outputFile.name)
                    val snackbar = com.google.android.material.snackbar.Snackbar.make(
                        requireView(),
                        message,
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    )
                    snackbar.setAction(getString(R.string.view)) {
                        openDownloadsFolder(outputFile)
                    }
                    snackbar.show()
                }
            } catch (e: Exception) {
                FLog.e(tag(), "exportLogsToFile: error", e)
                requireActivity().runOnUiThread {
                    Toast.makeText(context, getString(R.string.export_logs_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    
    private fun openDownloadsFolder(file: java.io.File) {
        try {
            // Try to open Downloads folder in file manager
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            val downloadsUri = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
            intent.setDataAndType(downloadsUri, "vnd.android.document/directory")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: try generic file manager intent
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                intent.setDataAndType(android.net.Uri.fromFile(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)), "*/*")
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
                startActivity(android.content.Intent.createChooser(intent, getString(R.string.open_with)))
            } catch (e2: Exception) {
                // Last resort: just show the full path
                Toast.makeText(context, file.absolutePath, Toast.LENGTH_LONG).show()
            }
        }
    }
}

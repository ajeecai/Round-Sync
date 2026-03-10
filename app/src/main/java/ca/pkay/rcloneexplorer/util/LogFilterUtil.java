package ca.pkay.rcloneexplorer.util;

/**
 * Utility class for filtering logcat lines.
 * Centralized filter to ensure consistency between exportLogsToFile and sendLogs.
 */
public class LogFilterUtil {
    
    /**
     * Determines if a logcat line should be included based on filter criteria.
     * 
     * @param line The logcat line to check
     * @param packageName The app package name
     * @return true if the line should be included, false otherwise
     */
    public static boolean shouldIncludeLogLine(String line, String packageName) {
        return line.contains(packageName) ||
               line.contains("ThumbnailsLoadingSvc") ||
               line.contains("FileExplorerRecyclerViewAdapter") ||
               line.contains("FileExplorerRVA") ||
               line.contains("FileExplorerFragment") ||
               line.contains("StreamTask") ||
               line.contains("RcloneServerManager") ||
               line.contains("MediaViewerActivity") ||
               line.contains("ThumbnailGlideModule") ||
               line.contains("Glide") ||
               line.contains("rclone");
    }
}

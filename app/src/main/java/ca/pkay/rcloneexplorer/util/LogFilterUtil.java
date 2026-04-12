package ca.pkay.rcloneexplorer.util;

import android.util.Log;

/**
 * Utility class for filtering logcat lines.
 * Centralized filter to ensure consistency between exportLogsToFile and sendLogs.
 * Supports both whitelist (by package name and tags) and log level filtering.
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
               line.contains("ArchiveTaskRunner") ||
               line.contains("EphemeralWorker") ||
               line.contains("StreamTask") ||
               line.contains("RcloneServerManager") ||
               line.contains("MediaViewerActivity") ||
               line.contains("ThumbnailGlideModule") ||
               line.contains("Glide") ||
               line.contains("VideoPrefetchManager") ||
               line.contains("VideoFrameExtractor") ||
               line.contains("VideoThumbnailLoader") ||
               line.contains("rclone");
    }

    /**
     * Determines if a logcat line should be included based on filter criteria and log level.
     *
     * @param line The logcat line to check
     * @param packageName The app package name
     * @param minLogLevel Minimum log level (Log.ERROR, Log.WARN, Log.INFO, Log.DEBUG). Pass Log.VERBOSE to include all.
     * @return true if the line should be included, false otherwise
     */
    public static boolean shouldIncludeLogLine(String line, String packageName, int minLogLevel) {
        // First check whitelist
        if (!shouldIncludeLogLine(line, packageName)) {
            return false;
        }

        // Then check log level
        return meetsMinimumLogLevel(line, minLogLevel);
    }

    /**
     * Check if a log line's level meets the minimum required level.
     * Android logcat format: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message"
     *
     * @param line The log line to check
     * @param minLogLevel Minimum log level (Log.ERROR, Log.WARN, Log.INFO, Log.DEBUG)
     * @return true if log level >= minLogLevel
     */
    private static boolean meetsMinimumLogLevel(String line, int minLogLevel) {
        if (minLogLevel <= Log.VERBOSE) {
            return true; // Include all if minimum is VERBOSE or lower
        }

        // Parse Android logcat format: "03-20 15:16:31.532 24200 24200 I Quality : ..."
        // The level character is at a fixed position after timestamp + PID + TID
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 6) {
            // Malformed line, include it to be safe
            return true;
        }

        // parts[4] should be the log level: V, D, I, W, E, F
        String levelStr = parts[4];
        int logLevel = parseLogLevel(levelStr);

        // Higher level value = more important (ERROR=6, WARN=5, INFO=4, DEBUG=3)
        return logLevel >= minLogLevel;
    }

    /**
     * Parse log level from string to Android Log constant.
     * @param levelStr Single character: V, D, I, W, E, F
     * @return Android Log level constant
     */
    private static int parseLogLevel(String levelStr) {
        if (levelStr == null || levelStr.isEmpty()) {
            return Log.VERBOSE;
        }

        switch (levelStr.charAt(0)) {
            case 'V':
                return Log.VERBOSE;  // 2
            case 'D':
                return Log.DEBUG;    // 3
            case 'I':
                return Log.INFO;     // 4
            case 'W':
                return Log.WARN;     // 5
            case 'E':
            case 'F':  // FATAL treated as ERROR
                return Log.ERROR;    // 6
            default:
                return Log.VERBOSE;
        }
    }

    /**
     * Filter rclone log lines by level.
     * Rclone log format: "2026/03/19 07:34:30 DEBUG : message"
     *
     * @param line The rclone log line
     * @param minLogLevel Minimum log level
     * @return true if should be included
     */
    public static boolean shouldIncludeRcloneLogLine(String line, int minLogLevel) {
        if (minLogLevel <= Log.VERBOSE) {
            return true;
        }

        // Parse rclone format: "2026/03/19 07:34:30 DEBUG : message"
        // Look for log level after timestamp
        int logLevel = Log.INFO;  // Default to INFO if no level found

        if (line.contains(" ERROR ") || line.contains(" FATAL ")) {
            logLevel = Log.ERROR;
        } else if (line.contains(" NOTICE") || line.contains(" WARNING ")) {  // NOTICE/WARNING are like WARN
            logLevel = Log.WARN;
        } else if (line.contains(" INFO ")) {
            logLevel = Log.INFO;
        } else if (line.contains(" DEBUG ")) {
            logLevel = Log.DEBUG;
        }

        return logLevel >= minLogLevel;
    }
}

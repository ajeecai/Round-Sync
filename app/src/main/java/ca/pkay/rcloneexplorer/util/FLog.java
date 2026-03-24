package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import java.util.IllegalFormatException;

import ca.pkay.rcloneexplorer.BuildConfig;

/**
 * A simple wrapper around {@link Log} to enable easier debugging without
 * slowing down all app functions.
 *
 * All logging is controlled by the user preference setting "Enable Logging"
 * and Log.isLoggable() checks. Logs work in both debug and release builds
 * when enabled by the user.
 */
public abstract class FLog {

    private static final String PATTERN_PATH = "/";
    private static final String REPLACE_PATH = "***anonymized_path***";
    private static final String PATTERN_URI = "content://";
    private static final String REPLACE_URI = "***anonymized_uri***";

    /**
     * Use this log tag to set a lower than default (<=INFO) log tag for all
     * app log messages.
     * <br><br><b>Example</b><br>
     *     <code>adb shell setprop log.tag.APP_MIN VERBOSE</code>
     */
    public static final String LOGGING_MIN_LEVEL_TAG = "APP_MIN";

    private static final String PREF_KEY_LOGS = "pref_key_logs";
    private static final String PREF_KEY_LOG_LEVEL = "log_level";

    // Log level constants (matching Android Log levels)
    private static final int LEVEL_ERROR = Log.ERROR;  // 6
    private static final int LEVEL_WARN = Log.WARN;    // 5
    private static final int LEVEL_INFO = Log.INFO;    // 4
    private static final int LEVEL_DEBUG = Log.DEBUG;  // 3

    private static Context sContext;

    /**
     * Initialize FLog with application context.
     * Must be called once during app startup (e.g., in Application.onCreate()).
     */
    public static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    /**
     * Check if user has enabled logging in settings.
     */
    private static boolean isUserLoggingEnabled() {
        if (sContext == null) {
            // If not initialized, default to disabled for safety
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(sContext);
        return prefs.getBoolean(PREF_KEY_LOGS, false);
    }

    /**
     * Get the configured minimum log level from user settings.
     * @return Android Log level constant (ERROR, WARN, INFO, or DEBUG)
     */
    private static int getUserLogLevel() {
        if (sContext == null) {
            return LEVEL_ERROR; // Default to ERROR
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(sContext);
        String levelStr = prefs.getString(PREF_KEY_LOG_LEVEL, "error");

        switch (levelStr) {
            case "error":
                return LEVEL_ERROR;
            case "warn":
                return LEVEL_WARN;
            case "debug":
                return LEVEL_DEBUG;
            case "info":
            default:
                return LEVEL_INFO;
        }
    }

    /**
     * Check if a log at the given level should be output based on user's configured minimum level.
     * @param level The log level to check (Log.ERROR, Log.WARN, Log.INFO, Log.DEBUG)
     * @return true if this log level should be output
     */
    private static boolean isLevelEnabled(int level) {
        return level >= getUserLogLevel();
    }

    public static void v(String tag, String message, Object... args) {
        if (!isUserLoggingEnabled()) {
            return;
        }
        if (isLoggable(tag, Log.VERBOSE)) {
            Log.v(tag, applyFormatting(message, args));
        }
    }

    public static void d(String tag, String message, Object... args) {
        if (!isUserLoggingEnabled() || !isLevelEnabled(Log.DEBUG)) {
            return;
        }
        if (isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, applyFormatting(message, args));
        }
    }

    public static void i(String tag, String message, Object... args) {
        if (!isUserLoggingEnabled() || !isLevelEnabled(Log.INFO)) {
            return;
        }
        if (isLoggable(tag, Log.INFO)) {
            Log.i(tag, applyFormatting(message, args));
        }
    }

    public static void w(String tag, String message, Object... args) {
        if (!isUserLoggingEnabled() || !isLevelEnabled(Log.WARN)) {
            return;
        }
        if (isLoggable(tag, Log.WARN)) {
            Log.w(tag, applyFormatting(message, args));
        }
    }

    public static void w(String tag, String message, Exception e, Object... args) {
        if (!isUserLoggingEnabled() || !isLevelEnabled(Log.WARN)) {
            return;
        }
        if (isLoggable(tag, Log.WARN)) {
            Log.w(tag, applyFormatting(message, args), e);
        }
    }

    private static final class LoggedError extends Throwable {

        public LoggedError() {
            super();
            removeLogTrace();
        }

        private void removeLogTrace() {
            StackTraceElement[] traces = getStackTrace();
            // The constuructor is called indirectly, i.e. rewind by 2
            int trim = 1;
            if (traces.length > trim) {
                StackTraceElement[] trimmed = new StackTraceElement[traces.length - trim];
                for (int i = trim; i < traces.length; i++) {
                    trimmed[i - trim] = traces[i];
                }
                setStackTrace(trimmed);
            }
        }
    }

    // Callers must ensure that any potentially tainted in formatting args
    // is filtered by anonymizeArgument()
    public static void e(String tag, String message, Object... args) {
        if (!isUserLoggingEnabled() || !isLevelEnabled(Log.ERROR)) {
            return;
        }
        if (isLoggable(tag, Log.ERROR)) {
            Log.e(tag, applyFormatting(message, args));
        }
    }

    // Callers must ensure that any potentially tainted in formatting args
    // is filtered by anonymizeArgument()
    public static void e(String tag, String message, Throwable e, Object... args) {
        if (!isUserLoggingEnabled() || !isLevelEnabled(Log.ERROR)) {
            return;
        }
        String formatted = applyFormatting(message, args);
        if (isLoggable(tag, Log.ERROR)) {
            Log.e(tag, formatted, e);
        }
    }

    private static String applyFormatting(String message, Object... args) {
        if (args.length == 0) {
            return message;
        } else {
            try {
                return String.format(message, args);
            } catch (IllegalFormatException e) {
                // We really shouldn't crash here even if there is a format
                // error since this is usally used without in error logging
                // itself.
                return message;
            }
        }
    }

    private static String applyAnonimizedFormatting(String message, Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                args[i] = anonymizeArgument((String) args[i]);
            }
        }
        return applyFormatting(message, args);
    }

    // Ensure regulatory compliance by removing any potentially tainted data.
    private static String anonymizeArgument (String arg) {
        // Anonymize file paths (may contain private data in file names)
        if (arg.startsWith(PATTERN_PATH)) {
            return REPLACE_PATH;
        // Anonymize content uris (may contain private data in uri)
        } else if (arg.startsWith(PATTERN_URI)) {
            return REPLACE_URI;
        }
        return arg;
    }
    
    private static final boolean isLoggable(String tag, int level){
        return Log.isLoggable(tag, level) ||
               (BuildConfig.DEBUG && level != Log.INFO && Log.isLoggable(LOGGING_MIN_LEVEL_TAG, level));
    }
}

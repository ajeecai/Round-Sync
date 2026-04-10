package ca.pkay.rcloneexplorer.archive;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import ca.pkay.rcloneexplorer.R;

public class ArchiveConfig {

    public static final int MAX_ARCHIVE_FILES = 100000;

    public enum Granularity {
        YEAR, MONTH
    }

    public enum DuplicateDetection {
        FILENAME, MD5
    }

    public enum DuplicateAction {
        SKIP, OVERWRITE, RENAME
    }

    public enum SourceAction {
        KEEP, DELETE
    }

    public String archiveRoot;
    public Granularity granularity;
    public DuplicateDetection duplicateDetection;
    public DuplicateAction duplicateAction;
    public SourceAction sourceAction;

    public static ArchiveConfig load(Context context, String remoteName) {
        ArchiveConfig config = new ArchiveConfig();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Per-remote archive root
        String rootKey = "pref_key_archive_root_" + remoteName;
        config.archiveRoot = sharedPreferences.getString(rootKey, "/");
        if (config.archiveRoot.isEmpty()) {
            config.archiveRoot = "/";
        }

        // Default to MONTH for granularity (can be overridden by dialog later)
        config.granularity = Granularity.MONTH;

        // Duplicate detection
        String detectionStr = sharedPreferences.getString(context.getString(R.string.pref_key_archive_duplicate_detection), "FILENAME");
        config.duplicateDetection = DuplicateDetection.valueOf(detectionStr);

        // Duplicate action
        String actionStr = sharedPreferences.getString(context.getString(R.string.pref_key_archive_duplicate_action), "SKIP");
        config.duplicateAction = DuplicateAction.valueOf(actionStr);

        // Source action
        String sourceStr = sharedPreferences.getString(context.getString(R.string.pref_key_archive_source_action), "DELETE");
        config.sourceAction = SourceAction.valueOf(sourceStr);

        return config;
    }
}

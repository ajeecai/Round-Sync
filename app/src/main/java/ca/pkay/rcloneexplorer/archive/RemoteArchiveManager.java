package ca.pkay.rcloneexplorer.archive;

import android.content.Context;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.util.FLog;

public class RemoteArchiveManager {

    private static final String TAG = "RemoteArchiveManager";

    public interface ArchiveProgressListener {
        void onProgress(int current, int total, String currentFile);
        void onNewTargetDirectory(String targetDir);
        void onFileArchived(String targetDir);
        void onComplete(int archived, int skipped, int failed);
    }

    private Context context;
    private Rclone rclone;
    private ArchiveConfig config;
    private ArchiveProgressListener listener;

    public RemoteArchiveManager(Context context, String remoteName) {
        this.context = context;
        this.rclone = new Rclone(context);
        this.config = ArchiveConfig.load(context, remoteName);
    }

    public void setListener(ArchiveProgressListener listener) {
        this.listener = listener;
    }

    public void archiveItems(RemoteItem remote, List<FileItem> selectedItems, ArchiveConfig.Granularity granularity) {
        // Step 1: Collect all files recursively
        List<FileItem> allFiles = new ArrayList<>();
        Set<String> processedDirs = new HashSet<>();

        for (FileItem item : selectedItems) {
            if (item.isDir()) {
                List<FileItem> recursiveFiles = rclone.listFilesRecursive(remote, item.getPath());
                if (recursiveFiles != null) {
                    if (allFiles.size() + recursiveFiles.size() > ArchiveConfig.MAX_ARCHIVE_FILES) {
                        throw new ArchiveLimitExceededException(allFiles.size() + recursiveFiles.size());
                    }
                    for (FileItem f : recursiveFiles) {
                        if (!f.isDir()) {
                            // listFilesRecursive already returns the full path relative to the remote root.
                            // Prepending basePath was causing a "double path" bug (e.g. DCIM/DCIM/photo.jpg).
                            allFiles.add(f);
                        }
                    }
                    processedDirs.add(item.getPath());
                }
            } else {
                if (allFiles.size() + 1 > ArchiveConfig.MAX_ARCHIVE_FILES) {
                    throw new ArchiveLimitExceededException(allFiles.size() + 1);
                }
                allFiles.add(item);
            }
        }

        int total = allFiles.size();
        int archived = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> newDirNotified = new HashSet<>();

        // Step 2: Process each file
        for (int i = 0; i < total; i++) {
            FileItem file = allFiles.get(i);
            if (listener != null) {
                listener.onProgress(i + 1, total, file.getName());
            }

            try {
                // Determine date
                Calendar date = DateExtractor.extractDateFromFilename(file.getName());
                if (date == null) {
                    // Try EXIF fallback (remote partial cat)
                    try (InputStream is = rclone.catPartial(remote, file.getPath(), 65536)) {
                        date = ExifDateExtractor.extractDateFromStream(is);
                    } catch (Exception e) {
                        FLog.w(TAG, "archiveItems: EXIF failed for " + file.getName());
                    }
                }
                if (date == null && file.getModTime() > 0) {
                    // Final fallback: modtime
                    date = Calendar.getInstance();
                    date.setTimeInMillis(file.getModTime());
                }

                // Compute target path
                String targetSubDir = computeTargetSubDir(date, granularity);
                // Normalize archiveRoot: strip leading slash to match DirectoryObject path format
                // (DirectoryObject uses "abc/2022-12", not "/abc/2022-12")
                String archivePath = config.archiveRoot;
                if (archivePath.startsWith("/")) {
                    archivePath = archivePath.substring(1);
                }
                if (!archivePath.isEmpty() && !archivePath.endsWith("/")) {
                    archivePath += "/";
                }
                String targetDir = archivePath + targetSubDir;
                String targetPath = targetDir + "/" + file.getName();

                // Create target directory explicitly before moving so it appears on remote immediately 
                // UI can refresh and show the new dir without waiting for the file
                if (newDirNotified.add(targetDir)) {
                    rclone.makeDirectory(remote, targetDir);
                    if (listener != null) {
                        listener.onNewTargetDirectory(targetDir);
                    }
                }

                // Handle duplicates
                if (rclone.fileExists(remote, targetPath)) {
                    switch (config.duplicateAction) {
                        case SKIP:
                            skipped++;
                            continue;
                        case OVERWRITE:
                            // Overwrite is default behavior for rclone moveto/copyto
                            break;
                        case RENAME:
                            targetPath = findUniqueName(rclone, remote, targetDir, file.getName());
                            break;
                    }
                }

                // Execute move or copy
                boolean success;
                if (config.sourceAction == ArchiveConfig.SourceAction.DELETE) {
                    Process p = rclone.moveTo(remote, file, targetDir); 
                    if (p != null) {
                        p.waitFor();
                        success = p.exitValue() == 0;
                    } else {
                        success = false;
                    }
                } else {
                    Process p = rclone.copyTo(remote, file.getPath(), targetPath);
                    if (p != null) {
                        p.waitFor();
                        success = p.exitValue() == 0;
                    } else {
                        success = false;
                    }
                }

                if (success) {
                    archived++;
                    if (listener != null) {
                        listener.onFileArchived(targetDir);
                    }
                } else {
                    failed++;
                }

            } catch (Exception e) {
                FLog.e(TAG, "archiveItems: failed to process " + file.getName(), e);
                failed++;
            }
        }

        // Cleanup empty source directories if delete policy
        if (config.sourceAction == ArchiveConfig.SourceAction.DELETE) {
            for (String dirPath : processedDirs) {
                rclone.removeEmptyDir(remote, dirPath);
            }
        }

        if (listener != null) {
            listener.onComplete(archived, skipped, failed);
        }
    }

    public static String computeTargetSubDir(Calendar date, ArchiveConfig.Granularity granularity) {
        if (date == null) {
            return "unknown";
        }
        int year = date.get(Calendar.YEAR);
        if (granularity == ArchiveConfig.Granularity.YEAR) {
            return String.valueOf(year);
        } else {
            int month = date.get(Calendar.MONTH) + 1; // 1-based
            return String.format("%04d-%02d", year, month);
        }
    }

    public static String findUniqueName(Rclone rclone, RemoteItem remote, String targetDir, String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex == -1 ? fileName : fileName.substring(0, dotIndex);
        String extension = dotIndex == -1 ? "" : fileName.substring(dotIndex);
        
        int suffix = 1;
        while (true) {
            String newName = baseName + "_" + suffix + extension;
            String newPath = targetDir + "/" + newName;
            if (!rclone.fileExists(remote, newPath)) {
                return newPath;
            }
            suffix++;
            if (suffix > 999) break; // sanity
        }
        return targetDir + "/" + fileName + "_too_many_duplicates";
    }
    public static class ArchiveLimitExceededException extends RuntimeException {
        private final int count;
        public ArchiveLimitExceededException(int count) {
            super("Archive limit exceeded: " + count);
            this.count = count;
        }
        public int getCount() { return count; }
    }
}

package ca.pkay.rcloneexplorer.archive;

import android.content.Context;
import java.io.File;
import java.util.Calendar;
import java.util.List;

import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.util.FLog;

public class UploadArchiveManager {

    private static final String TAG = "UploadArchiveManager";

    public interface UploadArchiveProgressListener {
        void onProgress(int current, int total, String currentFile);
        void onComplete(int uploaded, int skipped, int failed);
    }

    private Context context;
    private Rclone rclone;
    private ArchiveConfig config;
    private UploadArchiveProgressListener listener;

    public UploadArchiveManager(Context context, String remoteName) {
        this.context = context;
        this.rclone = new Rclone(context);
        this.config = ArchiveConfig.load(context, remoteName);
    }

    public void setListener(UploadArchiveProgressListener listener) {
        this.listener = listener;
    }

    public void uploadAndArchive(RemoteItem remote, List<String> localPaths, ArchiveConfig.Granularity granularity) {
        java.util.List<File> allFiles = new java.util.ArrayList<>();
        for (String path : localPaths) {
            collectLocalFiles(new File(path), allFiles);
            if (allFiles.size() > ArchiveConfig.MAX_ARCHIVE_FILES) {
                throw new RemoteArchiveManager.ArchiveLimitExceededException(allFiles.size());
            }
        }

        int total = allFiles.size();
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;

        for (int i = 0; i < total; i++) {
            File localFile = allFiles.get(i);
            String localPath = localFile.getAbsolutePath();
            if (listener != null) {
                listener.onProgress(i + 1, total, localFile.getName());
            }

            try {
                // Determine date from local file
                Calendar date = DateExtractor.extractDateFromFilename(localFile.getName());
                if (date == null) {
                    // Try local EXIF fallback (ExifInterface, cheap)
                    date = ExifDateExtractor.extractDateFromLocalFile(localPath);
                }
                if (date == null) {
                    // Final fallback: local file modtime
                    date = Calendar.getInstance();
                    date.setTimeInMillis(localFile.lastModified());
                }

                // Compute target path
                String targetSubDir = RemoteArchiveManager.computeTargetSubDir(date, granularity);
                String archivePath = config.archiveRoot;
                if (!archivePath.equals("/") && !archivePath.isEmpty()) {
                    if (!archivePath.endsWith("/")) {
                        archivePath += "/";
                    }
                } else if (archivePath.equals("/")) {
                    archivePath = "";
                }
                
                String targetDir = archivePath + targetSubDir;
                String targetPath = targetDir + "/" + localFile.getName();

                // Handle duplicates
                if (rclone.fileExists(remote, targetPath)) {
                    switch (config.duplicateAction) {
                        case SKIP:
                            skipped++;
                            continue;
                        case OVERWRITE:
                            break;
                        case RENAME:
                            targetPath = RemoteArchiveManager.findUniqueName(rclone, remote, targetDir, localFile.getName());
                            break;
                    }
                }

                // Execute upload directly to target path
                Process p = rclone.uploadFile(remote, targetDir, localPath);
                if (p != null) {
                    p.waitFor();
                    if (p.exitValue() == 0) {
                        uploaded++;
                    } else {
                        failed++;
                    }
                } else {
                    failed++;
                }

            } catch (Exception e) {
                FLog.e(TAG, "uploadAndArchive: failed to process " + localFile.getName(), e);
                failed++;
            }
        }

        if (listener != null) {
            listener.onComplete(uploaded, skipped, failed);
        }
    }

    private void collectLocalFiles(File root, List<File> allFiles) {
        if (allFiles.size() > ArchiveConfig.MAX_ARCHIVE_FILES) return;
        if (root.isFile()) {
            allFiles.add(root);
        } else if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children != null) {
                for (File child : children) {
                    collectLocalFiles(child, allFiles);
                }
            }
        }
    }

}

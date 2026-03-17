package ca.pkay.rcloneexplorer.util;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.util.HashMap;

import ca.pkay.rcloneexplorer.Items.FileItem;

/**
 * Extract video frames from cached videos for thumbnail generation
 * Uses MediaMetadataRetriever to extract frame at timestamp 0
 */
public class VideoFrameExtractor {
    private static final String TAG = "VideoFrameExtractor";

    /**
     * Extract frame from video URL (rclone HTTP serve)
     * @param videoUrl HTTP URL to video (http://127.0.0.1:29180/path)
     * @param fileItem Original file item for logging
     * @return Bitmap frame or null if extraction failed
     */
    public static Bitmap extractFrame(String videoUrl, FileItem fileItem) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            long startTime = System.currentTimeMillis();

            FLog.d(TAG, "Extracting frame for: %s", fileItem.getName());
            FLog.v(TAG, "Video URL: %s", videoUrl);

            // Set data source (cached video via HTTP)
            retriever.setDataSource(videoUrl, new HashMap<>());
            long setDataSourceTime = System.currentTimeMillis() - startTime;

            // Extract metadata for logging
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            FLog.v(TAG, "Video metadata: duration=%sms, size=%sx%s, setDataSource took %dms",
                    duration, width, height, setDataSourceTime);

            // Extract frame at timestamp 0 (first frame)
            startTime = System.currentTimeMillis();
            Bitmap frame = retriever.getFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            );
            long extractTime = System.currentTimeMillis() - startTime;

            if (frame == null) {
                // Fallback: try frame at 1 second
                FLog.d(TAG, "Frame at 0 returned null, trying 1 second: %s", fileItem.getName());
                frame = retriever.getFrameAtTime(
                        1_000_000, // 1 second in microseconds
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                );
                extractTime = System.currentTimeMillis() - startTime;
            }

            if (frame != null) {
                // Scale down if too large to save memory
                int maxSize = 512;
                if (frame.getWidth() > maxSize || frame.getHeight() > maxSize) {
                    float scale = Math.min(
                            (float) maxSize / frame.getWidth(),
                            (float) maxSize / frame.getHeight()
                    );
                    int newWidth = Math.round(frame.getWidth() * scale);
                    int newHeight = Math.round(frame.getHeight() * scale);

                    Bitmap scaledFrame = Bitmap.createScaledBitmap(frame, newWidth, newHeight, true);
                    frame.recycle();
                    frame = scaledFrame;

                    FLog.d(TAG, "Frame extracted and scaled: %s -> %dx%d in %dms",
                            fileItem.getName(), newWidth, newHeight, extractTime);
                } else {
                    FLog.d(TAG, "Frame extracted: %s -> %dx%d in %dms",
                            fileItem.getName(), frame.getWidth(), frame.getHeight(), extractTime);
                }

                return frame;
            } else {
                FLog.w(TAG, "Frame extraction returned null for: %s", fileItem.getName());
                return null;
            }

        } catch (IllegalArgumentException e) {
            FLog.e(TAG, "Invalid data source for %s", e, fileItem.getName());
            return null;
        } catch (SecurityException e) {
            FLog.e(TAG, "Security error accessing %s", e, fileItem.getName());
            return null;
        } catch (Exception e) {
            FLog.e(TAG, "Unexpected error extracting frame for %s", e, fileItem.getName());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                FLog.w(TAG, "Failed to release retriever for %s", e, fileItem.getName());
            }
        }
    }

}

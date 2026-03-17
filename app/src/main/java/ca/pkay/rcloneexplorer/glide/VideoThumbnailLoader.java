package ca.pkay.rcloneexplorer.glide;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.VideoFrameExtractor;
import ca.pkay.rcloneexplorer.util.VideoPrefetchManager;

/**
 * Glide loader for video thumbnails from cached videos
 */
public class VideoThumbnailLoader implements ModelLoader<VideoThumbnailLoader.Model, Bitmap> {
    private static final String TAG = "VideoThumbnailLoader";

    /**
     * Model containing video URL, file item, and prefetch manager
     */
    public static class Model {
        private final String videoUrl;
        private final FileItem fileItem;
        private final VideoPrefetchManager prefetchManager;

        /**
         * @param videoUrl HTTP URL to video (http://127.0.0.1:29180/path)
         * @param fileItem Video file item
         * @param prefetchManager Manager to check cache status
         */
        public Model(String videoUrl, FileItem fileItem, VideoPrefetchManager prefetchManager) {
            this.videoUrl = videoUrl;
            this.fileItem = fileItem;
            this.prefetchManager = prefetchManager;
        }

        public String getVideoUrl() {
            return videoUrl;
        }

        public FileItem getFileItem() {
            return fileItem;
        }

        public VideoPrefetchManager getPrefetchManager() {
            return prefetchManager;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Model that = (Model) o;
            return videoUrl.equals(that.videoUrl);
        }

        @Override
        public int hashCode() {
            return videoUrl.hashCode();
        }
    }

    /**
     * DataFetcher that checks cache and extracts frame
     */
    static class DataFetcher implements com.bumptech.glide.load.data.DataFetcher<Bitmap> {
        private final Model model;

        DataFetcher(Model model) {
            this.model = model;
        }

        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super Bitmap> callback) {
            try {
                // Check if video is cached
                if (!model.getPrefetchManager().isVideoCached(model.getFileItem())) {
                    callback.onLoadFailed(new Exception("Video not cached yet"));
                    return;
                }

                // Extract frame from cached video
                Bitmap frame = VideoFrameExtractor.extractFrame(
                        model.getVideoUrl(),
                        model.getFileItem()
                );

                if (frame != null) {
                    callback.onDataReady(frame);
                } else {
                    FLog.w(TAG, "Frame extraction failed: %s", model.getFileItem().getName());
                    callback.onLoadFailed(new Exception("Failed to extract frame"));
                }

            } catch (Exception e) {
                FLog.e(TAG, "Error loading video thumbnail for %s", e, model.getFileItem().getName());
                callback.onLoadFailed(e);
            }
        }

        @Override
        public void cleanup() {
            // Nothing to clean up - bitmap is managed by Glide
        }

        @Override
        public void cancel() {
            // Cannot cancel MediaMetadataRetriever operation once started
        }

        @NonNull
        @Override
        public Class<Bitmap> getDataClass() {
            return Bitmap.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }

    /**
     * Build Glide load data with cache key and data fetcher
     * @param model Video thumbnail model
     * @return LoadData containing cache key and fetcher
     */
    @Nullable
    @Override
    public LoadData<Bitmap> buildLoadData(@NonNull Model model, int width, int height, @NonNull Options options) {
        return new LoadData<>(
                new ObjectKey(model.getFileItem().getPath()),
                new DataFetcher(model)
        );
    }

    @Override
    public boolean handles(@NonNull Model model) {
        return true;
    }

    /**
     * Factory for creating VideoThumbnailLoader instances
     * Registered in ThumbnailGlideModule
     */
    public static class Factory implements ModelLoaderFactory<Model, Bitmap> {

        /**
         * Build a new VideoThumbnailLoader instance
         */
        @NonNull
        @Override
        public ModelLoader<Model, Bitmap> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new VideoThumbnailLoader();
        }

        @Override
        public void teardown() {
            // No resources to clean up
        }
    }
}

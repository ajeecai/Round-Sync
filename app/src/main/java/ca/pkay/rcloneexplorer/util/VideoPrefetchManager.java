package ca.pkay.rcloneexplorer.util;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.RcloneServerManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Manages video prefetch queue with priority-based execution
 * - Priority 0: Currently clicked video
 * - Priority 1: Adjacent videos (±5 from clicked)
 * - Priority 2: Visible videos in RecyclerView
 */
public class VideoPrefetchManager {
    private static final String TAG = "VideoPrefetchManager";
    private static final int PREFETCH_SIZE_BYTES = 5 * 1024 * 1024; // 5MB
    private static final int CONCURRENT_PREFETCH_THREADS = 3;

    private static VideoPrefetchManager instance;
    private final Context context;
    private final ExecutorService executorService;
    private final PriorityBlockingQueue<VideoPrefetchItem> prefetchQueue; // Slow O(log n) for priority updates, but needed for ordering
    private final ConcurrentHashMap<String, VideoPrefetchItem> prefetchItems; // Fast O(1) lookup for deduplicate prefetch and priority updates
    private final AtomicBoolean isRunning;
    private PrefetchCallback prefetchCallback;

    public enum PrefetchState {
        QUEUED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Callback interface for prefetch completion events
     */
    public interface PrefetchCallback {
        void onPrefetchCompleted(FileItem fileItem, boolean success);
    }

    /**
     * Represents a video prefetch task with priority
     */
    public static class VideoPrefetchItem implements Comparable<VideoPrefetchItem> {
        public final FileItem fileItem;
        public final String cacheKey;
        public volatile PrefetchState state;
        public int priority; // 0=highest
        public final long queuedTime;

        public VideoPrefetchItem(FileItem fileItem, int priority) {
            this.fileItem = fileItem;
            this.cacheKey = fileItem.getPath();
            this.priority = priority;
            this.state = PrefetchState.QUEUED;
            this.queuedTime = System.currentTimeMillis();
        }

        @Override
        public int compareTo(VideoPrefetchItem other) {
            // Lower priority value = higher priority
            int priorityCompare = Integer.compare(this.priority, other.priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            // Same priority: FIFO (older first)
            return Long.compare(this.queuedTime, other.queuedTime);
        }
    }

    private VideoPrefetchManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefetchQueue = new PriorityBlockingQueue<>();
        this.prefetchItems = new ConcurrentHashMap<>();
        this.executorService = Executors.newFixedThreadPool(CONCURRENT_PREFETCH_THREADS);
        this.isRunning = new AtomicBoolean(true);

        // Start worker threads
        for (int i = 0; i < CONCURRENT_PREFETCH_THREADS; i++) {
            executorService.submit(this::workerLoop);
        }

        FLog.i(TAG, "VideoPrefetchManager initialized with %d threads", CONCURRENT_PREFETCH_THREADS);
    }

    public static synchronized VideoPrefetchManager getInstance(Context context) {
        if (instance == null) {
            instance = new VideoPrefetchManager(context);
        }
        return instance;
    }

    /**
     * Set callback for prefetch completion events
     * @param callback Callback to be invoked when prefetch completes
     */
    public void setPrefetchCallback(PrefetchCallback callback) {
        this.prefetchCallback = callback;
    }

    /**
     * Add video to prefetch queue or update priority if already queued
     * @param fileItem Video file
     * @param priority 0=highest (clicked), 1=adjacent, 2=visible
     */
    public void addToPrefetchQueue(FileItem fileItem, int priority) {
        String cacheKey = fileItem.getPath();

        // Check if already in cache
        if (isVideoCached(fileItem)) {
            return;
        }

        VideoPrefetchItem existingItem = prefetchItems.get(cacheKey);

        if (existingItem != null) {
            // Update priority if new priority is higher (lower number)
            if (priority < existingItem.priority) {
                existingItem.priority = priority;

                // Re-queue with new priority (PriorityQueue will re-sort)
                if (existingItem.state == PrefetchState.QUEUED) {
                    prefetchQueue.remove(existingItem);
                    prefetchQueue.offer(existingItem);
                }
            }
        } else {
            // New item
            VideoPrefetchItem newItem = new VideoPrefetchItem(fileItem, priority);
            prefetchItems.put(cacheKey, newItem);
            prefetchQueue.offer(newItem);
            FLog.i(TAG, "Added to prefetch queue: %s (priority=%d)", fileItem.getName(), priority);
        }
    }

    /**
     * Cancel queued prefetch tasks for videos that scrolled out of view
     * Only cancels QUEUED items, not IN_PROGRESS ones
     */
    public void cancelQueued(List<FileItem> fileItems) {
        int cancelledCount = 0;
        for (FileItem fileItem : fileItems) {
            String cacheKey = fileItem.getPath();
            VideoPrefetchItem item = prefetchItems.get(cacheKey);

            if (item != null && item.state == PrefetchState.QUEUED) {
                item.state = PrefetchState.CANCELLED;
                prefetchQueue.remove(item);
                prefetchItems.remove(cacheKey);
                cancelledCount++;
            }
        }

        if (cancelledCount > 0) {
            FLog.i(TAG, "Cancelled %d queued prefetch tasks", cancelledCount);
        }
    }

    /**
     * Check if video is already cached via direct file system access
     * Uses same approach as CacheFrameExtractor
     */
    public boolean isVideoCached(FileItem video) {
        try {
            String appDataDir = context.getApplicationInfo().dataDir;
            String cacheRoot = appDataDir + "/cache/rclone/vfs/crypt";
            String relativePath = extractRelativePath(video.getPath());
            String cacheFilePath = cacheRoot + "/" + relativePath;

            File cacheFile = new File(cacheFilePath);
            boolean exists = cacheFile.exists();
            long size = exists ? cacheFile.length() : 0;

            // Consider cached if file exists and has enough data for frame extraction
            // Use the smaller of PREFETCH_SIZE_BYTES or actual video size
            long minSize = Math.min(PREFETCH_SIZE_BYTES, video.getSize());
            return exists && size >= minSize;

        } catch (Exception e) {
            FLog.e(TAG, "Failed to check cache for %s", e, video.getName());
            return false;
        }
    }

    /**
     * Extract relative path from full video path
     * //crypt/DCIM/pictures/video.mp4 -> DCIM/pictures/video.mp4
     */
    private String extractRelativePath(String fullPath) {
        if (fullPath.startsWith("//crypt/")) {
            return fullPath.substring("//crypt/".length());
        } else if (fullPath.startsWith("/crypt/")) {
            return fullPath.substring("/crypt/".length());
        } else {
            // Fallback: remove leading slashes
            return fullPath.replaceAll("^/+", "");
        }
    }

    /**
     * Worker thread that processes prefetch queue
     */
    private void workerLoop() {
        while (isRunning.get()) {
            try {
                // Block until item available (with timeout to allow shutdown)
                VideoPrefetchItem item = prefetchQueue.poll(1, TimeUnit.SECONDS);

                if (item == null) {
                    continue;
                }

                // Skip if cancelled
                if (item.state == PrefetchState.CANCELLED) {
                    prefetchItems.remove(item.cacheKey);
                    continue;
                }

                // Skip if already completed or in progress
                if (item.state != PrefetchState.QUEUED) {
                    continue;
                }

                // Execute prefetch
                item.state = PrefetchState.IN_PROGRESS;
                boolean success = prefetchVideo(item.fileItem);

                if (success) {
                    item.state = PrefetchState.COMPLETED;
                    FLog.i(TAG, "Prefetch completed: %s", item.fileItem.getName());
                } else {
                    item.state = PrefetchState.FAILED;
                    FLog.w(TAG, "Prefetch failed: %s", item.fileItem.getName());
                }

                // Notify callback on main thread
                if (prefetchCallback != null) {
                    final boolean finalSuccess = success;
                    final FileItem finalFileItem = item.fileItem;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        prefetchCallback.onPrefetchCompleted(finalFileItem, finalSuccess);
                    });
                }

            } catch (InterruptedException e) {
                FLog.w(TAG, "Worker thread interrupted", e);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                FLog.e(TAG, "Unexpected error in worker loop", e);
            }
        }
    }

    /**
     * Prefetch video using HTTP Range request for first 5MB
     * This triggers rclone VFS to cache the video data
     */
    private boolean prefetchVideo(FileItem fileItem) {
        long startTime = System.currentTimeMillis();

        try {
            String videoUrl = buildVideoUrl(fileItem);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build();

            // HTTP Range request for first 5MB
            Request request = new Request.Builder()
                    .url(videoUrl)
                    .addHeader("Range", "bytes=0-" + (PREFETCH_SIZE_BYTES - 1))
                    .build();

            Response response = client.newCall(request).execute();

            if (response.isSuccessful() || response.code() == 206) { // 206 Partial Content
                // Read response to trigger download
                long bytesRead = response.body().bytes().length;
                long duration = System.currentTimeMillis() - startTime;

                FLog.i(TAG, "Prefetch success: %s, %d bytes in %dms (%.2f MB/s)",
                        fileItem.getName(), bytesRead, duration,
                        (bytesRead / 1024.0 / 1024.0) / (duration / 1000.0));

                response.close();
                return true;
            } else {
                FLog.e(TAG, "Prefetch HTTP error: %s, code=%d",
                        fileItem.getName(), response.code());
                response.close();
                return false;
            }

        } catch (IOException e) {
            FLog.e(TAG, "Prefetch IO error for %s", e, fileItem.getName());
            return false;
        } catch (Exception e) {
            FLog.e(TAG, "Prefetch unexpected error for %s", e, fileItem.getName());
            return false;
        }
    }

    /**
     * Build HTTP URL for video streaming
     */
    private String buildVideoUrl(FileItem fileItem) {
        String remotePath = fileItem.getPath();
        String urlPath;

        // Extract path after remote name
        if (remotePath.startsWith("//crypt/")) {
            urlPath = remotePath.substring("//crypt".length());
        } else if (remotePath.startsWith("/crypt/")) {
            urlPath = remotePath.substring("/crypt".length());
        } else if (remotePath.startsWith("//")) {
            urlPath = remotePath.substring(remotePath.indexOf('/', 2));
        } else {
            urlPath = remotePath.startsWith("/") ? remotePath : "/" + remotePath;
        }

        return "http://" + RcloneServerManager.LOCALHOST + ":" + RcloneServerManager.STREAMING_SERVICE_PORT + urlPath;
    }

    /**
     * Get current queue size (for debugging/monitoring)
     */
    public int getQueueSize() {
        return prefetchQueue.size();
    }

    /**
     * Get total tracked items (for debugging/monitoring)
     */
    public int getTrackedItemsCount() {
        return prefetchItems.size();
    }

    /**
     * Clear all prefetch queue and tracked items
     * Call when exiting directory or fragment
     */
    public void clearAll() {
        prefetchQueue.clear();
        prefetchItems.clear();
        FLog.i(TAG, "Cleared all prefetch queue and tracked items");
    }

    /**
     * Shutdown manager (call when app exits)
     */
    public void shutdown() {
        FLog.i(TAG, "Shutting down VideoPrefetchManager");
        isRunning.set(false);
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                FLog.w(TAG, "ExecutorService did not terminate in time");
            }
        } catch (InterruptedException e) {
            FLog.w(TAG, "Interrupted while waiting for shutdown", e);
            Thread.currentThread().interrupt();
        }
    }
}

package ca.pkay.rcloneexplorer.Activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;

import java.io.File;
import java.util.ArrayList;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.PersistentGlideUrl;

public class MediaViewerActivity extends AppCompatActivity {

    private static final String TAG = "MediaViewerActivity";
    private static final int SWIPE_THRESHOLD = 100;
    private static final int SWIPE_VELOCITY_THRESHOLD = 100;

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_MIME_TYPE = "mime_type";
    public static final String EXTRA_FILE_ITEMS = "file_items";
    public static final String EXTRA_CURRENT_INDEX = "current_index";
    public static final String EXTRA_THUMBNAIL_SERVER_PORT = "thumbnail_server_port";
    public static final String EXTRA_THUMBNAIL_SERVER_HIDDEN_PATH = "thumbnail_server_hidden_path";
    public static final String EXTRA_VIDEO_SERVER_PORT = "video_server_port";

    private PhotoView imageView;
    private StyledPlayerView playerView;
    private ExoPlayer player;
    private ProgressBar progressBar;
    private GestureDetector gestureDetector;

    private boolean isImageZoomed = false;
    private long lastScaleChangeTime = 0;
    private static final long SCALE_GESTURE_COOLDOWN_MS = 300; // 300ms cooldown after scale gesture

    private String filePath;
    private String mimeType;
    private ArrayList<FileItem> fileItems;
    private int currentIndex;
    private int thumbnailServerPort;
    private String thumbnailServerHiddenPath;
    private int videoServerPort;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        // Keep screen on during viewing
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        imageView = findViewById(R.id.image_view);
        playerView = findViewById(R.id.video_view);
        progressBar = findViewById(R.id.progress_bar);

        // Setup PhotoView: three zoom levels - 1.0 (normal), 2.0 (medium), 4.0 (maximum)
        imageView.setMinimumScale(1.0f);
        imageView.setMediumScale(2.0f);  // Double-tap cycles: 1.0 -> 2.0 -> 4.0 -> 1.0
        imageView.setMaximumScale(4.0f);

        // Disable zoom animation to prevent flashing during pinch gestures
        imageView.setZoomTransitionDuration(0); // No animation - instant zoom for smooth pinch
        imageView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);

        // Allow smoother edge handling
        imageView.setAllowParentInterceptOnEdge(true);

        // Setup PhotoView to disable swipe navigation when zoomed or recently scaled
        imageView.setOnScaleChangeListener((scaleFactor, focusX, focusY) -> {
            // Disable swipe when zoomed in (use threshold to account for floating point precision)
            float currentScale = imageView.getScale();
            isImageZoomed = currentScale > 1.05f;  // Use 1.05 threshold instead of 1.0

            // Only set cooldown for pinch gestures (when scale is not close to 1.0)
            // Double-tap to normal size (1.0) should not trigger cooldown
            if (Math.abs(currentScale - 1.0f) < 0.05f) {
                // At normal size, allow immediate swipe (no cooldown)
                lastScaleChangeTime = 0;
                isImageZoomed = false; // Ensure swipe is enabled immediately
                FLog.d(TAG, "Image at normal scale (1.0), swipe enabled immediately");
            } else {
                // Record the time of scale change to prevent accidental swipe right after pinch
                lastScaleChangeTime = System.currentTimeMillis();
                FLog.d(TAG, "Image scale changed: " + currentScale + ", cooldown active");
            }
        });

        // Initialize ExoPlayer with aggressive buffering for fast startup with VFS cache
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        250,      // Min buffer: 250ms (fast startup)
                        5000,     // Max buffer: 5s
                        250,      // Playback buffer: 250ms (fast startup)
                        250       // Playback rebuffer: 250ms (must be <= minBuffer)
                ).build();

        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build();
        playerView.setPlayer(player);

        // Configure controller: hidden by default, manual show/hide on tap
        playerView.setUseController(true);
        playerView.setControllerAutoShow(false); // Don't show on video start
        playerView.setControllerHideOnTouch(false); // Manual control
        playerView.setControllerShowTimeoutMs(0); // Never auto-hide

        // Custom touch handling: tap to toggle controller, swipe to navigate
        playerView.setOnTouchListener(new View.OnTouchListener() {
            private float downX = 0;
            private float downY = 0;
            private long downTime = 0;
            private boolean isSwiping = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        downTime = System.currentTimeMillis();
                        isSwiping = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float deltaX = Math.abs(event.getX() - downX);
                        float deltaY = Math.abs(event.getY() - downY);
                        if (deltaX > SWIPE_THRESHOLD && deltaX > deltaY) {
                            isSwiping = true;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        long tapDuration = System.currentTimeMillis() - downTime;
                        float moveDistance = (float) Math.sqrt(
                                Math.pow(event.getX() - downX, 2) +
                                Math.pow(event.getY() - downY, 2));

                        if (isSwiping) {
                            // Handle swipe gesture for navigation
                            gestureDetector.onTouchEvent(event);
                            return true;
                        } else if (tapDuration < 300 && moveDistance < 20) {
                            // Single tap: toggle controller visibility
                            if (playerView.isControllerFullyVisible()) {
                                playerView.hideController();
                            } else {
                                playerView.showController();
                            }
                            return true;
                        }
                        break;
                }
                return false;
            }
        });

        // Add player listener for loading state
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    progressBar.setVisibility(View.GONE);
                    FLog.i(TAG, "ExoPlayer: video ready, starting playback");
                } else if (playbackState == Player.STATE_BUFFERING) {
                    progressBar.setVisibility(View.VISIBLE);
                    FLog.i(TAG, "ExoPlayer: buffering...");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                progressBar.setVisibility(View.GONE);
                FLog.e(TAG, "ExoPlayer: playback error", error);
                Toast.makeText(MediaViewerActivity.this, "Failed to play video", Toast.LENGTH_SHORT).show();
            }
        });

        // Hide system UI for immersive viewing
        hideSystemUI();

        // Get intent data
        Intent intent = getIntent();
        filePath = intent.getStringExtra(EXTRA_FILE_PATH);
        mimeType = intent.getStringExtra(EXTRA_MIME_TYPE);
        fileItems = intent.getParcelableArrayListExtra(EXTRA_FILE_ITEMS);
        currentIndex = intent.getIntExtra(EXTRA_CURRENT_INDEX, -1);
        thumbnailServerPort = intent.getIntExtra(EXTRA_THUMBNAIL_SERVER_PORT, -1);
        thumbnailServerHiddenPath = intent.getStringExtra(EXTRA_THUMBNAIL_SERVER_HIDDEN_PATH);
        videoServerPort = intent.getIntExtra(EXTRA_VIDEO_SERVER_PORT, -1);

        FLog.d(TAG, "onCreate: filePath=%s, mimeType=%s, currentIndex=%d, fileItems size=%d, videoServerPort=%d",
                filePath, mimeType, currentIndex, fileItems != null ? fileItems.size() : 0, videoServerPort);

        // Setup gesture detector for swipe
        gestureDetector = new GestureDetector(this, new SwipeGestureListener());

        // Load initial media
        loadMedia(filePath, mimeType);
    }

    private void hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void loadMedia(String path, String mime) {
        if (path == null || mime == null) {
            FLog.e(TAG, "loadMedia: null path or mime");
            finish();
            return;
        }

        FLog.i(TAG, "loadMedia: path=%s, mime=%s, thumbnailServerPort=%d, videoServerPort=%d",
                path, mime, thumbnailServerPort, videoServerPort);
        FLog.d(TAG, "loadMedia: loading %s as %s (URL mode: %b)", path, mime, thumbnailServerPort > 0);

        if (mime.startsWith("image/")) {
            // Check if we should load from URL or local file
            if (thumbnailServerPort > 0 && thumbnailServerHiddenPath != null) {
                // URL mode: load directly from rclone serve (Glide will cache)
                loadImageFromUrl(path);
            } else {
                // Legacy mode: load from local file
                File file = new File(path);
                if (!file.exists()) {
                    FLog.e(TAG, "loadMedia: file not found: %s", path);
                    Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                loadImage(file);
            }
        } else if (mime.startsWith("video/")) {
            // Video playback using persistent video server
            if (videoServerPort > 0) {
                FLog.i(TAG, "loadMedia: VIDEO URL mode - calling loadVideoFromUrl()");
                // URL mode: load from persistent video server (port 29180)
                loadVideoFromUrl(path);
            } else {
                FLog.i(TAG, "loadMedia: VIDEO LOCAL mode - calling loadVideo() for local file (videoServerPort=%d)", videoServerPort);
                // Legacy mode: load from local file
                File file = new File(path);
                if (!file.exists()) {
                    FLog.e(TAG, "loadMedia: video file not found: %s", path);
                    Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                loadVideo(file);
            }
        } else {
            FLog.w(TAG, "loadMedia: unsupported mime type: %s", mime);
            Toast.makeText(this, "Unsupported media type", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void loadImage(File file) {
        FLog.d(TAG, "loadImage from file: %s", file.getAbsolutePath());

        player.stop();

        imageView.setVisibility(View.VISIBLE);
        playerView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        Glide.with(this)
                .load(file)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                        progressBar.setVisibility(View.GONE);
                        FLog.e(TAG, "loadImage: failed to load image", e);
                        Toast.makeText(MediaViewerActivity.this, "Failed to load image", Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                        progressBar.setVisibility(View.GONE);
                        FLog.d(TAG, "loadImage: image loaded successfully from %s", dataSource);
                        return false;
                    }
                })
                .into(imageView);
    }

    /**
     * Load image from URL (using Glide cache for both thumbnails and full images).
     * This method loads images directly from rclone serve http URL,
     * allowing Glide to cache the original image data.
     * Thumbnails in the file list and full images here share the same Glide cache.
     */
    private void loadImageFromUrl(String remotePath) {
        FLog.d(TAG, "loadImageFromUrl: path=%s, port=%d, hiddenPath=%s",
               remotePath, thumbnailServerPort, thumbnailServerHiddenPath);

        if (thumbnailServerPort <= 0 || thumbnailServerHiddenPath == null) {
            FLog.e(TAG, "loadImageFromUrl: invalid server params (port=%d, hiddenPath=%s)",
                   thumbnailServerPort, thumbnailServerHiddenPath);
            Toast.makeText(this, "Invalid server configuration", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Remove //remoteName prefix from path
        // Path format: //remoteName/subpath/file.jpg
        // Server baseurl includes remoteName, so we need: subpath/file.jpg (no leading slash)
        // Extract remote name from path (everything between // and first /)
        String pathAfterRemote;
        if (remotePath.startsWith("//")) {
            int thirdSlash = remotePath.indexOf('/', 2);
            if (thirdSlash > 0) {
                // Path is //crypt/DCIM/file.jpg -> extract DCIM/file.jpg
                pathAfterRemote = remotePath.substring(thirdSlash + 1);
            } else {
                // Root path //crypt -> empty
                pathAfterRemote = "";
            }
        } else {
            pathAfterRemote = remotePath.startsWith("/") ? remotePath.substring(1) : remotePath;
        }

        String url = "http://127.0.0.1:" + thumbnailServerPort + "/" + thumbnailServerHiddenPath +
                     (pathAfterRemote.isEmpty() ? "" : "/" + pathAfterRemote);
        FLog.d(TAG, "loadImageFromUrl: URL=%s", url);

        // Stop video playback when switching to image
        player.stop();

        imageView.setVisibility(View.VISIBLE);
        playerView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        // Use PersistentGlideUrl to ensure cache key matches thumbnails
        // This allows instant loading from Glide cache (no re-download)
        Glide.with(this)
                .load(new PersistentGlideUrl(url))
                .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache original image data
                .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                        progressBar.setVisibility(View.GONE);
                        FLog.e(TAG, "loadImageFromUrl: failed to load image from %s", e, url);
                        Toast.makeText(MediaViewerActivity.this, "Failed to load image", Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                        progressBar.setVisibility(View.GONE);
                        FLog.d(TAG, "loadImageFromUrl: image loaded successfully from %s", dataSource);
                        return false;
                    }
                })
                .into(imageView);
    }

    private void loadVideo(File file) {
        FLog.d(TAG, "loadVideo: %s", file.getAbsolutePath());

        // Stop current playback if any
        player.stop();

        imageView.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);

        Uri videoUri = Uri.fromFile(file);
        MediaItem mediaItem = MediaItem.fromUri(videoUri);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void loadVideoFromUrl(String remotePath) {
        FLog.i(TAG, "loadVideoFromUrl called - VERSION CHECK MARKER");
        FLog.d(TAG, "loadVideoFromUrl: path=%s, port=%d", remotePath, videoServerPort);

        if (videoServerPort <= 0) {
            FLog.e(TAG, "loadVideoFromUrl: invalid video server port: %d", videoServerPort);
            Toast.makeText(this, "Invalid video server configuration", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Build URL using persistent root server (port 29180)
        // Server serves remoteName: so we need to remove //remoteName prefix
        // Path format: //remoteName/subpath/file.mp4 -> /subpath/file.mp4
        String urlPath;
        if (remotePath.startsWith("//")) {
            int thirdSlash = remotePath.indexOf('/', 2);
            if (thirdSlash > 0) {
                // Path is //crypt/DCIM/file.mp4 -> extract /DCIM/file.mp4
                urlPath = remotePath.substring(thirdSlash);
            } else {
                // Root path //crypt -> /
                urlPath = "/";
            }
        } else {
            urlPath = remotePath.startsWith("/") ? remotePath : "/" + remotePath;
        }

        String videoUrl = "http://127.0.0.1:" + videoServerPort + urlPath;

        FLog.i(TAG, "loadVideoFromUrl: URL=%s (from path: %s)", videoUrl, remotePath);

        // Stop current playback if any
        player.stop();

        imageView.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);

        // Create MediaItem and set to player
        MediaItem mediaItem = MediaItem.fromUri(videoUrl);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(true); // Auto-play when ready
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // This handles touches on the Activity background (not on views)
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Only feed single-touch events to gesture detector for complete event sequence
        // Multi-touch (pinch zoom) should be handled exclusively by PhotoView
        if (event.getPointerCount() == 1) {
            gestureDetector.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    private class SwipeGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || e2 == null) {
                return false;
            }

            // Check if swipe navigation is currently allowed
            float currentScale = imageView.getScale();
            boolean isCurrentlyZoomed = currentScale > 1.05f;
            long timeSinceLastScale = System.currentTimeMillis() - lastScaleChangeTime;
            boolean isInScaleCooldown = timeSinceLastScale < SCALE_GESTURE_COOLDOWN_MS;

            // Don't navigate when zoomed or in cooldown period
            if (isCurrentlyZoomed || isInScaleCooldown) {
                // FLog.d(TAG, "onFling: navigation blocked (zoomed=%b, cooldown=%b)", isCurrentlyZoomed, isInScaleCooldown);
                return false;
            }

            float diffX = e2.getX() - e1.getX();
            float diffY = e2.getY() - e1.getY();

            if (Math.abs(diffX) > Math.abs(diffY)) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        onSwipeRight();
                    } else {
                        onSwipeLeft();
                    }
                    return true;
                }
            }
            return false;
        }
    }

    private void onSwipeLeft() {
        FLog.i(TAG, "onSwipeLeft: next media");
        navigateToNext();
    }

    private void onSwipeRight() {
        FLog.i(TAG, "onSwipeRight: previous media");
        navigateToPrevious();
    }

    private void navigateToNext() {
        if (fileItems == null || currentIndex < 0) {
            FLog.d(TAG, "navigateToNext: no file list or invalid index");
            return;
        }

        // Find next media file (image or video)
        for (int i = currentIndex + 1; i < fileItems.size(); i++) {
            FileItem item = fileItems.get(i);
            String mime = item.getMimeType();
            if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                currentIndex = i;
                loadFileItem(item);
                return;
            }
        }

        Toast.makeText(this, "Last media", Toast.LENGTH_SHORT).show();
    }

    private void navigateToPrevious() {
        if (fileItems == null || currentIndex < 0) {
            FLog.d(TAG, "navigateToPrevious: no file list or invalid index");
            return;
        }

        // Find previous media file (image or video)
        for (int i = currentIndex - 1; i >= 0; i--) {
            FileItem item = fileItems.get(i);
            String mime = item.getMimeType();
            if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                currentIndex = i;
                loadFileItem(item);
                return;
            }
        }

        Toast.makeText(this, "First media", Toast.LENGTH_SHORT).show();
    }

    private void loadFileItem(FileItem item) {
        FLog.i(TAG, "loadFileItem: %s (mime=%s, size=%d MB, URL mode: %b)",
               item.getName(), item.getMimeType(), item.getSize() / 1024 / 1024, thumbnailServerPort > 0);

        // In URL mode, directly load from URL (Glide cache will be used automatically)
        if (thumbnailServerPort > 0 && thumbnailServerHiddenPath != null) {
            filePath = item.getPath(); // Store path for loadMedia
            mimeType = item.getMimeType();
            FLog.i(TAG, "loadFileItem: calling loadMedia for path=%s", filePath);
            loadMedia(filePath, mimeType);
        } else {
            // Legacy mode: would need local file (not implemented in URL-first design)
            FLog.w(TAG, "loadFileItem: file navigation not available in legacy mode: %s", item.getName());
            Toast.makeText(this, "File not available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && playerView.getVisibility() == View.VISIBLE) {
            player.play();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}

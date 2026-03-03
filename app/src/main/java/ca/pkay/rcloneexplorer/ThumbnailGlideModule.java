package ca.pkay.rcloneexplorer;

import android.content.Context;
import androidx.annotation.NonNull;
import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Custom Glide configuration for thumbnail caching.
 *
 * Cache sizes:
 * - Disk cache: 500MB (for encrypted crypt thumbnails)
 * - Memory cache: 100MB (for quick scrolling performance)
 *
 * Cache strategy: LRU (Least Recently Used)
 * - Automatically evicts oldest accessed items when limit is reached
 * - More efficient than FIFO for typical usage patterns
 */
@GlideModule
public class ThumbnailGlideModule extends AppGlideModule {

    // Disk cache size: 500MB
    private static final int DISK_CACHE_SIZE = 500 * 1024 * 1024;

    // Memory cache size: 100MB
    private static final int MEMORY_CACHE_SIZE = 100 * 1024 * 1024;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // Set disk cache size (stored in app's internal cache directory)
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, DISK_CACHE_SIZE));

        // Set memory cache size
        builder.setMemoryCache(new LruResourceCache(MEMORY_CACHE_SIZE));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        // No custom components needed
    }

    @Override
    public boolean isManifestParsingEnabled() {
        // Disable manifest parsing for better performance
        return false;
    }
}

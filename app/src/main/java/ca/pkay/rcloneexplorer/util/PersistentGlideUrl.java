package ca.pkay.rcloneexplorer.util;

import com.bumptech.glide.load.model.GlideUrl;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Custom GlideUrl that generates cache keys based on the remote file path only,
 * ignoring the server host/port and hidden path prefix.
 *
 * This ensures that the same remote file shares the same Glide cache entry
 * regardless of:
 * - Different server ports
 * - Different hidden path values
 * - Whether loaded as thumbnail or full image
 *
 * Example:
 * URL: http://127.0.0.1:29179/sPeBAPSyWaBpzjz4bb7fjw/crypt/DCIM/image.jpg
 * Cache key: /crypt/DCIM/image.jpg
 *
 * This allows thumbnails and full images to share the same cached data,
 * making full image viewing instant after thumbnails are loaded.
 */
public class PersistentGlideUrl extends GlideUrl {

    public PersistentGlideUrl(String url) {
        super(url);
    }

    @Override
    public String getCacheKey() {
        try {
            URL url = super.toURL();
            String path = url.getPath();
            // Extract path after the first '/' (removes hidden path prefix)
            // e.g., "/hiddenPath/crypt/DCIM/image.jpg" -> "/crypt/DCIM/image.jpg"
            int secondSlash = path.indexOf('/', 1);
            if (secondSlash > 0) {
                return path.substring(secondSlash);
            }
            return path;
        } catch (MalformedURLException e) {
            return super.getCacheKey();
        }
    }
}

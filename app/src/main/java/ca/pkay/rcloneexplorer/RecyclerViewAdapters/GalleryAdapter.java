package ca.pkay.rcloneexplorer.RecyclerViewAdapters;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.RcloneServerManager;
import ca.pkay.rcloneexplorer.glide.VideoThumbnailLoader;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.PersistentGlideUrl;
import ca.pkay.rcloneexplorer.util.VideoPrefetchManager;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.file.FileAccessError;
import android.net.Uri;

public class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final String TAG = "GalleryAdapter";
    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_MEDIA = 1;

    private final Context context;
    private final List<Object> items; // String for date headers, FileItem for media
    private final OnMediaClickListener listener;
    private final long sizeLimit;
    private final VideoPrefetchManager videoPrefetchManager;

    public interface OnMediaClickListener {
        void onMediaClicked(FileItem fileItem);
        String[] getThumbnailServerParams();
    }

    public GalleryAdapter(Context context, OnMediaClickListener listener) {
        this.context = context;
        this.listener = listener;
        this.items = new ArrayList<>();
        this.sizeLimit = PreferenceManager.getDefaultSharedPreferences(context)
                .getLong(context.getString(R.string.pref_key_thumbnail_size_limit),
                        context.getResources().getInteger(R.integer.default_thumbnail_size_limit));
        this.videoPrefetchManager = VideoPrefetchManager.getInstance(context);
    }

    public void setData(List<FileItem> fileItems) {
        items.clear();

        // Filter to only images and videos
        List<FileItem> mediaItems = new ArrayList<>();
        for (FileItem item : fileItems) {
            if (!item.isDir()) {
                String mime = item.getMimeType();
                if (mime.startsWith("image/") || mime.startsWith("video/")) {
                    mediaItems.add(item);
                }
            }
        }

        // Sort by modTime descending (newest first)
        Collections.sort(mediaItems, (a, b) -> Long.compare(b.getModTime(), a.getModTime()));

        // Group by date
        String lastDateLabel = null;
        for (FileItem item : mediaItems) {
            String dateLabel = getDateLabel(item.getModTime());
            if (!dateLabel.equals(lastDateLabel)) {
                items.add(dateLabel);
                lastDateLabel = dateLabel;
            }
            items.add(item);
        }

        notifyDataSetChanged();
    }

    private String getDateLabel(long timeMillis) {
        if (timeMillis <= 0) {
            return "Unknown";
        }

        Calendar itemCal = Calendar.getInstance();
        itemCal.setTimeInMillis(timeMillis);

        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);

        if (isSameDay(itemCal, today)) {
            return "今天";
        } else if (isSameDay(itemCal, yesterday)) {
            return "昨天";
        } else {
            int month = itemCal.get(Calendar.MONTH) + 1;
            int day = itemCal.get(Calendar.DAY_OF_MONTH);
            int year = itemCal.get(Calendar.YEAR);
            int currentYear = today.get(Calendar.YEAR);
            if (year == currentYear) {
                return month + "月" + day + "日";
            } else {
                return year + "年" + month + "月" + day + "日";
            }
        }
    }

    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
               a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof String ? VIEW_TYPE_HEADER : VIEW_TYPE_MEDIA;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.gallery_date_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.gallery_grid_item, parent, false);
            // Make it square
            int spanCount = 4;
            int screenWidth = parent.getContext().getResources().getDisplayMetrics().widthPixels;
            int itemSize = screenWidth / spanCount;
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp == null) {
                lp = new ViewGroup.LayoutParams(itemSize, itemSize);
            } else {
                lp.width = itemSize;
                lp.height = itemSize;
            }
            view.setLayoutParams(lp);
            return new MediaViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).dateText.setText((String) items.get(position));
        } else if (holder instanceof MediaViewHolder) {
            MediaViewHolder mediaHolder = (MediaViewHolder) holder;
            FileItem item = (FileItem) items.get(position);
            mediaHolder.fileItem = item;

            // Clear previous image
            Glide.with(context).clear(mediaHolder.thumbnail);

            // Show/hide video indicator
            String mimeType = item.getMimeType();
            if (mimeType.startsWith("video/")) {
                mediaHolder.videoIndicator.setVisibility(View.VISIBLE);
            } else {
                mediaHolder.videoIndicator.setVisibility(View.GONE);
            }

            // Load thumbnail
            boolean localLoad = item.getRemote().getType() == RemoteItem.SAFW;
            RequestOptions glideOption = new RequestOptions()
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_file)
                    .error(R.drawable.ic_file)
                    .timeout(15000);

            if (localLoad) {
                try {
                    Uri contentUri = SafAccessProvider.getDirectServer(context).getDocumentUri('/' + item.getPath());
                    Glide.with(context)
                            .load(contentUri)
                            .apply(glideOption)
                            .thumbnail(0.1f)
                            .into(mediaHolder.thumbnail);
                } catch (FileAccessError e) {
                    FLog.e(TAG, "SAF error", e);
                    mediaHolder.thumbnail.setImageResource(R.drawable.ic_file);
                }
            } else {
                String[] serverParams = listener.getThumbnailServerParams();
                String hiddenPath = serverParams[0];
                int serverPort = Integer.parseInt(serverParams[1]);

                String itemPath = item.getPath();
                String pathAfterRemote;
                if (itemPath.startsWith("//")) {
                    int thirdSlash = itemPath.indexOf('/', 2);
                    if (thirdSlash > 0) {
                        pathAfterRemote = itemPath.substring(thirdSlash + 1);
                    } else {
                        pathAfterRemote = "";
                    }
                } else {
                    pathAfterRemote = itemPath.startsWith("/") ? itemPath.substring(1) : itemPath;
                }

                if (mimeType.startsWith("video/")) {
                    String videoUrl = "http://" + RcloneServerManager.LOCALHOST + ":" + RcloneServerManager.STREAMING_SERVICE_PORT + "/" + pathAfterRemote;
                    Glide.with(context)
                            .asBitmap()
                            .load(new VideoThumbnailLoader.Model(videoUrl, item, videoPrefetchManager))
                            .apply(glideOption)
                            .into(mediaHolder.thumbnail);
                } else {
                    String url = "http://" + RcloneServerManager.LOCALHOST + ":" + serverPort + "/" + hiddenPath +
                                (pathAfterRemote.isEmpty() ? "" : "/" + pathAfterRemote);
                    Glide.with(context)
                            .load(new PersistentGlideUrl(url))
                            .apply(glideOption)
                            .into(mediaHolder.thumbnail);
                }
            }

            // Click listener
            mediaHolder.itemView.setOnClickListener(v -> listener.onMediaClicked(item));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public GridLayoutManager.SpanSizeLookup getSpanSizeLookup(int spanCount) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return getItemViewType(position) == VIEW_TYPE_HEADER ? spanCount : 1;
            }
        };
    }

    /**
     * Returns the ordered list of media FileItems (no date headers),
     * for passing to MediaViewer for correct swipe navigation.
     */
    public List<FileItem> getMediaItems() {
        List<FileItem> mediaItems = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof FileItem) {
                mediaItems.add((FileItem) item);
            }
        }
        return mediaItems;
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView dateText;

        HeaderViewHolder(View itemView) {
            super(itemView);
            dateText = itemView.findViewById(R.id.gallery_date_header_text);
        }
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final ImageView videoIndicator;
        FileItem fileItem;

        MediaViewHolder(View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.gallery_thumbnail);
            videoIndicator = itemView.findViewById(R.id.gallery_video_indicator);
        }
    }
}

package ca.pkay.rcloneexplorer.Fragments;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartActivity;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartActivityForResult;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartService;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.leinardi.android.speeddial.SpeedDialActionItem;
import com.leinardi.android.speeddial.SpeedDialOverlayLayout;
import com.leinardi.android.speeddial.SpeedDialView;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import ca.pkay.rcloneexplorer.Activities.MainActivity;
import ca.pkay.rcloneexplorer.BuildConfig;
import ca.pkay.rcloneexplorer.Dialogs.Dialogs;
import ca.pkay.rcloneexplorer.Dialogs.FilePropertiesDialog;
import ca.pkay.rcloneexplorer.Dialogs.GoToDialog;
import ca.pkay.rcloneexplorer.Dialogs.InputDialog;
import ca.pkay.rcloneexplorer.Dialogs.LinkDialog;
import ca.pkay.rcloneexplorer.Dialogs.LoadingDialog;
import ca.pkay.rcloneexplorer.Dialogs.OpenAsDialog;
import ca.pkay.rcloneexplorer.Dialogs.ServeDialog;
import ca.pkay.rcloneexplorer.Dialogs.SortDialog;
import ca.pkay.rcloneexplorer.FileComparators;
import ca.pkay.rcloneexplorer.FilePicker;
import ca.pkay.rcloneexplorer.Items.DirectoryObject;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.Items.Task;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.RcloneServerManager;
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.FileExplorerRecyclerViewAdapter;
import ca.pkay.rcloneexplorer.Services.StreamingService;
import ca.pkay.rcloneexplorer.Services.ThumbnailsLoadingService;
import ca.pkay.rcloneexplorer.util.ActivityHelper;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.LargeParcel;
import ca.pkay.rcloneexplorer.workmanager.EphemeralTaskManager;
import ca.pkay.rcloneexplorer.workmanager.SyncManager;
import de.felixnuesse.ui.BreadcrumbView;
import es.dmoral.toasty.Toasty;
import java9.util.stream.Collectors;
import java9.util.stream.StreamSupport;
import jp.wasabeef.recyclerview.animators.LandingAnimator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FileExplorerFragment extends Fragment implements   FileExplorerRecyclerViewAdapter.OnClickListener,
                                                                SwipeRefreshLayout.OnRefreshListener,
                                                                BreadcrumbView.OnClickListener,
                                                                OpenAsDialog.OnClickListener,
                                                                InputDialog.OnPositive,
                                                                GoToDialog.Callbacks,
                                                                SortDialog.OnClickListener,
                                                                ServeDialog.Callback {

    public static final int STREAMING_INTENT_RESULT = 468;
    private static final String TAG = "FileExplorerFragment";
    private static final String ARG_REMOTE = "remote_param";
    private static final String SHARED_PREFS_SORT_ORDER = "ca.pkay.rcexplorer.sort_order";
    private static final int FILE_PICKER_UPLOAD_RESULT = 186;
    private static final int FILE_PICKER_DOWNLOAD_RESULT = 204;
    private static final int FILE_PICKER_SYNC_RESULT = 45;
    private static final int PREFETCH_IMAGE_COUNT = 5; // Number of videos to prefetch before and after current video
    private static final long VIDEO_PREFETCH_SIZE = 10L * 1024L * 1024L; // 10MB - amount to prefetch for videos via HTTP Range (rclone chunk cache)
    private static final int STREAMING_SERVICE_PORT = 29180; // Fixed port for persistent streaming service
    private final String SAVED_PATH = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SAVED_PATH";
    private final String SAVED_CONTENT = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SAVED_CONTENT";
    private final String SAVED_SEARCH_MODE = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SEARCH_MODE";
    private final String SAVED_SEARCH_STRING = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SEARCH_STRING";
    private final String SAVED_RENAME_ITEM = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_RENAME_ITEM";
    private final String SAVED_SELECTED_ITEMS = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SELECTED_ITEMS";
    private final String SAVED_IS_IN_MOVE_MODE = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_IS_IN_MOVE_MODE";
    private final String SAVED_START_AT_BOOT = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_START_AT_BOOT";
    private final String SAVED_DOWNLOAD_LIST = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_DOWNLOAD_LIST";
    private final String SAVED_MOVE_START_PATH = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_MOVE_START_PATH";
    private final String SAVED_SYNC_DIRECTION = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SYNC_DIRECTION";
    private final String SAVED_SYNC_REMOTE_PATH = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SYNC_REMOTE_PATH";
    private String originalToolbarTitle;
    private Stack<String> pathStack;
    private Map<String, Integer> directoryPosition;
    private DirectoryObject directoryObject;
    private List<FileItem> moveList;
    private String moveStartPath;
    private List<FileItem> downloadList;
    private FileItem renameItem;
    private BreadcrumbView breadcrumbView;
    private Rclone rclone;
    private RemoteItem remote;
    private String remoteName;
    private FileExplorerRecyclerViewAdapter recyclerViewAdapter;
    private LinearLayoutManager recyclerViewLinearLayoutManager;
    private SwipeRefreshLayout swipeRefreshLayout;
    private View searchBar;
    private View searchButton;
    private View searchClear;
    private AsyncTask fetchDirectoryTask;
    private boolean isRunning;
    private int sortOrder;
    private boolean isInMoveMode;
    private SpeedDialView fab;
    private MenuItem menuSelectAll;
    private MenuItem menuGoTo;
    private MenuItem menuLink;
    private MenuItem menuHttpServe;
    private MenuItem menuEmptyTrash;
    private Boolean isDarkTheme;
    private Boolean isSearchMode;
    private String searchString;
    private String syncRemotePath;
    private int syncDirection;
    private boolean is720dp;
    private boolean showThumbnails;
    private boolean isThumbnailsServiceRunning;
    private boolean startAtRoot;
    private boolean goToDefaultSet;
    private Context context;
    private String thumbnailServerAuth;
    private int thumbnailServerPort;
    private boolean wrapFilenames;
    private SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public FileExplorerFragment() {
    }

    @SuppressWarnings("unused")
    public static FileExplorerFragment newInstance(RemoteItem remoteItem) {
        FileExplorerFragment fragment = new FileExplorerFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_REMOTE, remoteItem);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() == null) {
            return;
        }

        if (getContext() == null) {
            return;
        }
        setHasOptionsMenu(true);

        remote = getArguments().getParcelable(ARG_REMOTE);
        if (remote == null) {
            return;
        }
        remoteName = remote.getName();
        pathStack = new Stack<>();
        directoryPosition = new HashMap<>();
        directoryObject = new DirectoryObject();

        String path;
        if (savedInstanceState == null) {
            path = "//" + remoteName;
            directoryObject.setPath(path);
        } else {
            path = savedInstanceState.getString(SAVED_PATH);
            if (path == null) {
                return;
            }
            directoryObject.setPath(path);
            ArrayList<FileItem> savedContent = savedInstanceState.getParcelableArrayList(SAVED_CONTENT);
            if(null == savedContent){
                directoryObject.clear();
            } else {
                directoryObject.setContent(savedContent);
            }

            buildStackFromPath(remoteName, path);
            renameItem = savedInstanceState.getParcelable(SAVED_RENAME_ITEM);
        }

        originalToolbarTitle = ((FragmentActivity) context).getTitle().toString();
        setTitle();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        sortOrder = sharedPreferences.getInt(SHARED_PREFS_SORT_ORDER, SortDialog.ALPHA_ASCENDING);
        showThumbnails = sharedPreferences.getBoolean(getString(R.string.pref_key_show_thumbnails), false);
        isDarkTheme = ActivityHelper.isDarkTheme(this.getActivity());
        goToDefaultSet = sharedPreferences.getBoolean(getString(R.string.pref_key_go_to_default_set), false);
        String wrapFilenamesKey = getString(R.string.pref_key_wrap_filenames);
        prefChangeListener = (pref, key) -> {
            if (key.equals(wrapFilenamesKey) && recyclerViewAdapter != null) {
                recyclerViewAdapter.setWrapFileNames(pref.getBoolean(wrapFilenamesKey, true));
            }
        };
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefChangeListener);
        wrapFilenames = sharedPreferences.getBoolean(getString(R.string.pref_key_wrap_filenames), true);

        if (goToDefaultSet) {
            startAtRoot = sharedPreferences.getBoolean(getString(R.string.pref_key_start_at_root), false);
        }

        rclone = new Rclone(getContext());

        isSearchMode = false;
        isInMoveMode = false;
        is720dp = getResources().getBoolean(R.bool.is720dp);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_file_explorer_list, container, false);
        if (savedInstanceState != null) {
            startAtRoot = savedInstanceState.getBoolean(SAVED_START_AT_BOOT);
        }

        if (showThumbnails) {
            initializeThumbnailParams();
            startThumbnailService();
        }

        swipeRefreshLayout = view.findViewById(R.id.file_explorer_srl);
        swipeRefreshLayout.setOnRefreshListener(this);

        Context context = view.getContext();

        RecyclerView recyclerView = view.findViewById(R.id.file_explorer_list);
        recyclerViewLinearLayoutManager = new LinearLayoutManager(context);

        // Optimize scrolling performance with prefetch
        recyclerView.setItemViewCacheSize(20); // Cache 20 ViewHolders (smooth back-scroll)
        recyclerViewLinearLayoutManager.setInitialPrefetchItemCount(4); // Prefetch 4 items ahead

        recyclerView.setItemAnimator(new LandingAnimator());
        recyclerView.setLayoutManager(recyclerViewLinearLayoutManager);
        View emptyFolderView = view.findViewById(R.id.empty_folder_view);
        View noSearchResultsView = view.findViewById(R.id.no_search_results_view);
        recyclerViewAdapter = new FileExplorerRecyclerViewAdapter(context, emptyFolderView, noSearchResultsView, this);
        recyclerViewAdapter.showThumbnails(showThumbnails);
        recyclerViewAdapter.setWrapFileNames(wrapFilenames);
        recyclerView.setAdapter(recyclerViewAdapter);

        if (remote.isRemoteType(RemoteItem.SFTP) && !goToDefaultSet & savedInstanceState == null) {
            showSFTPgoToDialog();
        } else {
            if (directoryObject.isDirectoryContentEmpty()) {
                fetchDirectoryTask = new FetchDirectoryContent().execute();
                swipeRefreshLayout.setRefreshing(true);
            } else {
                recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            }
        }

        fab = view.findViewById(R.id.fab_fragment_file_explorer_list);
        fab.setOverlayLayout((SpeedDialOverlayLayout)view.findViewById(R.id.fab_overlay));
        fab.setOnActionSelectedListener(actionItem -> {
            switch (actionItem.getId()) {
                case R.id.fab_add_folder:
                    onCreateNewDirectory();
                    break;
                case R.id.fab_upload:
                    onUploadFiles();
                    break;
            }
            return false;
        });
        fab.addActionItem(new SpeedDialActionItem.Builder(R.id.fab_upload, R.drawable.ic_file_upload)
                .setFabImageTintColor(ResourcesCompat.getColor(getResources(), R.color.colorAccent, this.context.getTheme()))
                .setLabel(getString(R.string.fab_upload_files))
                .create());
        fab.addActionItem(new SpeedDialActionItem.Builder(R.id.fab_add_folder, R.drawable.ic_create_new_folder)
                .setFabImageTintColor(ResourcesCompat.getColor(getResources(), R.color.colorAccent, this.context.getTheme()))
                .setLabel(getString(R.string.fab_new_folder))
                .create());

        breadcrumbView = ((FragmentActivity) context).findViewById(R.id.breadcrumb_view);
        breadcrumbView.setOnClickListener(this);
        breadcrumbView.setVisibility(View.VISIBLE);
        breadcrumbView.addCrumb(remote.getDisplayName(), "//" + remoteName);
        if (savedInstanceState != null) {
            if (!directoryObject.getCurrentPath().equals("//" + remoteName)) {
                breadcrumbView.buildBreadCrumbsFromPath(directoryObject.getCurrentPath());
            }
        }

        searchBar = ((FragmentActivity) context).findViewById(R.id.search_bar);
        searchButton = ((FragmentActivity) context).findViewById(R.id.searchButton);
        searchButton.setOnClickListener(click -> searchClicked());
        searchButton.setVisibility(View.VISIBLE);

        searchClear = ((FragmentActivity) context).findViewById(R.id.search_clear);
        searchClear.setOnClickListener(v -> {
            EditText searchField = searchBar.findViewById(R.id.search_field);
            if (searchField.getText().toString().isEmpty()) {
                searchClicked();
            } else {
                searchDirContent("");
                searchField.setText("");
                InputMethodManager keyboard = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                keyboard.hideSoftInputFromWindow(getActivity().getCurrentFocus().getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
            }
        });

        if (view.findViewById(R.id.background) != null) {
            view.findViewById(R.id.background).setOnClickListener(v -> onClickOutsideOfView());
        }

        setBottomBarClickListeners(view);

        if (savedInstanceState != null && savedInstanceState.getBoolean(SAVED_SEARCH_MODE, false)) {
            searchString = savedInstanceState.getString(SAVED_SEARCH_STRING);
            searchClicked();
        }

        // Start video server for this remote (on-demand)
        // This ensures the server serves the correct remote the user is browsing
        ensureVideoServerRunning();

        isRunning = true;
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        registerReceivers();

        if (showThumbnails) {
            startThumbnailService();
        }

        if (directoryObject.isContentValid()) {
            return;
        }
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        swipeRefreshLayout.setRefreshing(true);
        fetchDirectoryTask = new FetchDirectoryContent(true).execute();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(SAVED_PATH, directoryObject.getCurrentPath());
        ArrayList<FileItem> content = new ArrayList<>(directoryObject.getDirectoryContent());
        outState.putParcelableArrayList(SAVED_CONTENT, content);
        outState.putBoolean(SAVED_SEARCH_MODE, isSearchMode);
        outState.putParcelable(SAVED_RENAME_ITEM, renameItem);
        outState.putBoolean(SAVED_START_AT_BOOT, startAtRoot);
        outState.putInt(SAVED_SYNC_DIRECTION, syncDirection);
        if (isSearchMode) {
            outState.putString(SAVED_SEARCH_STRING, searchString);
        }
        if (recyclerViewAdapter.isInSelectMode()) {
            outState.putParcelableArrayList(SAVED_SELECTED_ITEMS, new ArrayList<>(recyclerViewAdapter.getSelectedItems()));
        }
        if (isInMoveMode) {
            outState.putBoolean(SAVED_IS_IN_MOVE_MODE, true);
            outState.putParcelableArrayList(SAVED_SELECTED_ITEMS, new ArrayList<>(moveList));
        }
        if (downloadList != null && !downloadList.isEmpty()) {
            outState.putParcelableArrayList(SAVED_DOWNLOAD_LIST, new ArrayList<>(downloadList));
        }
        if (moveStartPath != null) {
            outState.putString(SAVED_MOVE_START_PATH, moveStartPath);
        }
        if (syncRemotePath != null) {
            outState.putString(SAVED_SYNC_REMOTE_PATH, syncRemotePath);
        }
        if (LargeParcel.calculateBundleSize(outState) > 250 * 1024) {
            outState.remove(SAVED_CONTENT);
        }
    }



    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (savedInstanceState == null) {
            return;
        }

        List<FileItem> selectedItems = savedInstanceState.getParcelableArrayList(SAVED_SELECTED_ITEMS);
        boolean moveMode = savedInstanceState.getBoolean(SAVED_IS_IN_MOVE_MODE, false);
        if (selectedItems != null && !selectedItems.isEmpty() && !moveMode) {
            recyclerViewAdapter.setSelectedItems(selectedItems);
            handleFilesSelected();
        }

        if (moveMode) {
            isInMoveMode = true;
            moveList = savedInstanceState.getParcelableArrayList(SAVED_SELECTED_ITEMS);
            recyclerViewAdapter.setMoveMode(true);
            ((FragmentActivity) context).setTitle(getString(R.string.select_destination));
            ((FragmentActivity) context).findViewById(R.id.move_bar).setVisibility(View.VISIBLE);
            fab.hide();
            fab.setVisibility(View.INVISIBLE);
            setFabBehaviour(false);
        }
        downloadList = savedInstanceState.getParcelableArrayList(SAVED_DOWNLOAD_LIST);
        moveStartPath = savedInstanceState.getString(SAVED_MOVE_START_PATH);
        syncDirection = savedInstanceState.getInt(SAVED_SYNC_DIRECTION, -1);
        syncRemotePath = savedInstanceState.getString(SAVED_SYNC_REMOTE_PATH);
    }

    private void setFabBehaviour(boolean enableSnackBarBehaviour) {
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) fab.getLayoutParams();

        if (enableSnackBarBehaviour) {
            params.setBehavior(new SpeedDialView.ScrollingViewSnackbarBehavior());
            fab.requestLayout();
        } else {
            params.setBehavior(new SpeedDialView.NoBehavior());
            fab.requestLayout();
        }
    }

    private void setTitle() {
        String title;
        if (remote.isCrypt()) {
            title = "crypt" + " " + "(" + remote.getTypeReadable() + ")";
        } else {
            title = remote.getTypeReadable();
        }
        ((FragmentActivity) context).setTitle(title);
    }

    private void buildStackFromPath(String remote, String path) {
        String root = "//" + remote;
        if (root.equals(path)) {
            return;
        }
        pathStack.clear();
        pathStack.push(root);

        int index = 0;

        while ((index = path.indexOf("/", index)) > 0) {
            String p = path.substring(0, index);
            pathStack.push(p);
            index++;
        }
    }

    private void registerReceivers() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(getString(R.string.background_service_broadcast));
        LocalBroadcastManager.getInstance(context).registerReceiver(backgroundTaskBroadcastReceiver, intentFilter);
    }

    private BroadcastReceiver backgroundTaskBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String broadcastRemote = intent.getStringExtra(getString(R.string.background_service_broadcast_data_remote));
            String broadcastPath = intent.getStringExtra(getString(R.string.background_service_broadcast_data_path));
            String broadcastPath2 = intent.getStringExtra(getString(R.string.background_service_broadcast_data_path2));
            String path = directoryObject.getCurrentPath();
            if (!remoteName.equals(broadcastRemote)) {
                return;
            }

            if (path.equals(broadcastPath)) {
                if (fetchDirectoryTask != null) {
                    fetchDirectoryTask.cancel(true);
                }
                if (directoryObject.isPathInCache(broadcastPath)) {
                    directoryObject.removePathFromCache(broadcastPath);
                }
                fetchDirectoryTask = new FetchDirectoryContent(true).execute();
            } else if (directoryObject.isPathInCache(broadcastPath)) {
                directoryObject.removePathFromCache(broadcastPath);
            }

            if (broadcastPath2 == null) {
                return;
            }

            if (path.equals(broadcastPath2)) {
                if (fetchDirectoryTask != null) {
                    fetchDirectoryTask.cancel(true);
                }
                swipeRefreshLayout.setRefreshing(false);
                if (directoryObject.isPathInCache(broadcastPath2)) {
                    directoryObject.removePathFromCache(broadcastPath2);
                }
                fetchDirectoryTask = new FetchDirectoryContent(true).execute();
            } else if (directoryObject.isPathInCache(broadcastPath2)) {
                directoryObject.removePathFromCache(broadcastPath2);
            }
        }
    };

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_UPLOAD_RESULT && resultCode == FragmentActivity.RESULT_OK) {
            @SuppressWarnings("unchecked")
            ArrayList<File> result = (ArrayList<File>) data.getSerializableExtra(FilePicker.FILE_PICKER_RESULT);
            ArrayList<String> uploadList = new ArrayList<>();
            for (File file : result) {
                uploadList.add(file.getPath());
            }

            for (String uploadFile : uploadList) {
                EphemeralTaskManager.Companion.queueUpload(this.context, remote, uploadFile, directoryObject.getCurrentPath());
            }
        } else if (requestCode == FILE_PICKER_DOWNLOAD_RESULT) {
            if (resultCode != FragmentActivity.RESULT_OK) {
                downloadList.clear();
                return;
            }
            String selectedPath = data.getStringExtra(FilePicker.FILE_PICKER_RESULT);
            recyclerViewAdapter.cancelSelection();

            for (FileItem downloadItem : downloadList) {
                EphemeralTaskManager.Companion.queueDownload(this.context, remote, downloadItem, selectedPath);
            }
            downloadList.clear();
        } else if (requestCode == FILE_PICKER_SYNC_RESULT && resultCode == FragmentActivity.RESULT_OK) {
            String path = data.getStringExtra(FilePicker.FILE_PICKER_RESULT);

            Task ephemeralTask = new Task(-1);
            ephemeralTask.setRemoteId(remote.getName());
            ephemeralTask.setLocalPath(path);
            ephemeralTask.setDirection(syncDirection);
            ephemeralTask.setRemotePath(syncRemotePath);
            (new SyncManager(this.context)).queueEphemeral(ephemeralTask);

        } else if (requestCode == STREAMING_INTENT_RESULT) {
            Intent serveIntent = new Intent(getContext(), StreamingService.class);
            context.stopService(serveIntent);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.file_explorer_folder_menu, menu);
        menuSelectAll = menu.findItem(R.id.action_select_all);
        menuGoTo = menu.findItem(R.id.action_go_to);
        menuLink = menu.findItem(R.id.action_link);
        menuHttpServe = menu.findItem(R.id.action_serve);
        menuEmptyTrash = menu.findItem(R.id.action_empty_trash);

        if (!remote.hasTrashCan()) {
            menu.findItem(R.id.action_empty_trash).setVisible(false);
        }
        if (!remote.hasLinkSupport()) {
            menu.findItem(R.id.action_link).setVisible(false);
        }
        if (!remote.isRemoteType(RemoteItem.SFTP)) {
            menu.findItem(R.id.action_go_to).setVisible(false);
        }
        if (!remote.hasSyncSupport()) {
            menu.findItem(R.id.action_sync).setVisible(false);
        }

        if (isInMoveMode || recyclerViewAdapter.isInSelectMode()) {
            setOptionsMenuVisibility(false);
        }
    }

    private void setOptionsMenuVisibility(boolean setVisible) {
        if (menuSelectAll == null || menuGoTo == null || menuLink == null || menuHttpServe == null || menuEmptyTrash == null) {
            return;
        }

        menuHttpServe.setVisible(setVisible);
        if (!setVisible && isInMoveMode) {
            menuSelectAll.setVisible(false);
        } else {
            menuSelectAll.setVisible(true);
        }
        if (!remote.isCrypt()) {
            menuLink.setVisible(setVisible);
        }
        if (remote.hasTrashCan()) {
            menuEmptyTrash.setVisible(setVisible);
        }
        if (remote.isRemoteType(RemoteItem.SFTP)) {
            menuGoTo.setVisible(setVisible);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id) {
            case R.id.action_sort:
                showSortMenu();
                return true;
            case R.id.action_select_all:
                recyclerViewAdapter.toggleSelectAll();
                return true;
            case R.id.action_serve:
                serve();
                return true;
            case R.id.action_empty_trash:
                emptyTrash();
                return true;
            case R.id.action_link:
                new LinkTask().execute(directoryObject.getCurrentPath());
                return true;
            case R.id.action_sync:
                showSyncDialog(directoryObject.getCurrentPath());
                return true;
            case R.id.action_go_to:
                showSFTPgoToDialog();
                return true;
            case R.id.action_send_logs:
                sendLogs();
                return true;
            case android.R.id.home:
                if (isInMoveMode) {
                    cancelMoveClicked();
                } else if (recyclerViewAdapter.isInSelectMode()) {
                    recyclerViewAdapter.cancelSelection();
                } else {
                    ((MainActivity)context).openNavigationDrawer();
                }
                return true;
            default:
                    return super.onOptionsItemSelected(item);
        }
    }

    private void serve() {
        ServeDialog serveDialog = new ServeDialog();
        serveDialog.show(getChildFragmentManager(), "serve dialog");
    }

    // serve callback
    @Override
    public void onServeOptionsSelected(int protocol, boolean allowRemoteAccess, String user, String password) {
        // GH-87: Release old stream
        context.stopService(new Intent(context, StreamingService.class));

        Intent intent = new Intent(getContext(), StreamingService.class);
        intent.putExtra(StreamingService.SERVE_PATH_ARG, directoryObject.getCurrentPath());
        intent.putExtra(StreamingService.REMOTE_ARG, remote);
        intent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, true);
        intent.putExtra(StreamingService.ALLOW_REMOTE_ACCESS, allowRemoteAccess);
        intent.putExtra(StreamingService.AUTHENTICATION_USERNAME, user);
        intent.putExtra(StreamingService.AUTHENTICATION_PASSWORD, password);

        switch (protocol) {
            case Rclone.SERVE_PROTOCOL_HTTP: // HTTP
                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_HTTP);
                break;
            case Rclone.SERVE_PROTOCOL_FTP: // FTP
                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_FTP);
                break;
            case Rclone.SERVE_PROTOCOL_DLNA: // DLNA
                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_DLNA);
                break;
            case Rclone.SERVE_PROTOCOL_WEBDAV: // Webdav
                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_WEBDAV);
                break;
            default:
                return;
        }
        tryStartService(context, intent);
    }

    private void emptyTrash() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setMessage(R.string.empty_trash_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> new EmptyTrashTask().execute())
                .show();
    }

    private void showSFTPgoToDialog() {
        GoToDialog goToDialog = new GoToDialog();
        goToDialog.show(getChildFragmentManager(), "go to dialog");
    }

    /*
     * Swipe to refresh
     */
    @Override
    public void onRefresh() {
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        fetchDirectoryTask = new FetchDirectoryContent(true).execute();
    }

    private void startThumbnailService() {
        if(RemoteItem.SAFW == remote.getType()){
            // safdav also serves files for thumbnails
            return;
        }
        Intent serveIntent = new Intent(getContext(), ThumbnailsLoadingService.class);
        serveIntent.putExtra(ThumbnailsLoadingService.REMOTE_ARG, remote);
        serveIntent.putExtra(ThumbnailsLoadingService.HIDDEN_PATH, thumbnailServerAuth);
        serveIntent.putExtra(ThumbnailsLoadingService.SERVER_PORT, thumbnailServerPort);
        tryStartService(context, serveIntent);
        isThumbnailsServiceRunning = true;
    }

    /**
     * Ensure video server is running for the current remote.
     * Only starts/switches server if needed - doesn't restart if already serving correct remote.
     */
    private void ensureVideoServerRunning() {
        if (remote == null || context == null) {
            FLog.w(TAG, "ensureVideoServerRunning: remote or context is null, skipping");
            return;
        }

        FLog.i(TAG, "ensureVideoServerRunning: checking server for remote: %s", remote.getName());

        RcloneServerManager serverManager = RcloneServerManager.getInstance();

        // Check if server is already serving this remote
        if (serverManager.isServingRemote(remote.getName())) {
            FLog.i(TAG, "ensureVideoServerRunning: server already serving remote: %s, no action needed", remote.getName());
            return;
        }

        // Start/switch server to this remote in background thread
        FLog.i(TAG, "ensureVideoServerRunning: starting server for remote: %s", remote.getName());
        new Thread(() -> {
            boolean success = serverManager.startServerForRemote(context, remote);
            if (success) {
                FLog.i(TAG, "ensureVideoServerRunning: successfully started server for remote: %s", remote.getName());
            } else {
                FLog.e(TAG, "ensureVideoServerRunning: failed to start server for remote: %s", remote.getName());
            }
        }).start();
    }

    private void initializeThumbnailParams() {
        SecureRandom random = new SecureRandom();
        byte[] values = new byte[16];
        random.nextBytes(values);
        thumbnailServerAuth = Base64.encodeToString(values, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
        thumbnailServerPort = allocatePort(29179, true);
    }

    private static int allocatePort(int port, boolean allocateFallback) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            if (allocateFallback) {
                return allocatePort(0, false);
            }
        }
        throw new IllegalStateException("No port available");
    }

    private void searchClicked() {
        InputMethodManager keyboard = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (isSearchMode) {
            searchBar.setVisibility(View.INVISIBLE);
            searchButton.setVisibility(View.VISIBLE);
            searchDirContent("");
            ((EditText)searchBar.findViewById(R.id.search_field)).setText("");
            recyclerViewAdapter.setSearchMode(false);
            isSearchMode = false;
        } else {
            searchBar.setVisibility(View.VISIBLE);
            searchButton.setVisibility(View.INVISIBLE);
            recyclerViewAdapter.setSearchMode(true);
            isSearchMode = true;
            EditText search = searchBar.findViewById(R.id.search_field);
            search.requestFocus();
            keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void showOpenAsDialog(FileItem fileItem) {
        OpenAsDialog openAsDialog = new OpenAsDialog();
        openAsDialog
                .setFileItem(fileItem);
        if (getFragmentManager() != null) {
            openAsDialog.show(getChildFragmentManager(), "open as");
        }
    }

    /*
     * Open As dialog callbacks
     */
    @Override
    public void onClickText(FileItem fileItem) {
        new DownloadAndOpen(DownloadAndOpen.OPEN_AS_TEXT).execute(fileItem);
    }

    @Override
    public void onClickAudio(FileItem fileItem) {
        new StreamTask(StreamTask.OPEN_AS_AUDIO).execute(fileItem);
    }

    @Override
    public void onClickVideo(FileItem fileItem) {
        new StreamTask(StreamTask.OPEN_AS_VIDEO).execute(fileItem);

        // Prefetch adjacent videos for smooth navigation
        int currentIndex = recyclerViewAdapter.getCurrentContent().indexOf(fileItem);
        prefetchAdjacentVideos(currentIndex);
    }

    @Override
    public void onClickImage(FileItem fileItem) {
        // Check if thumbnail service is available (required for URL loading)
        if (showThumbnails && isThumbnailsServiceRunning && thumbnailServerPort > 0) {
            // URL loading approach: directly open MediaViewerActivity with URL
            // Glide will load from URL and cache the original image automatically
            FLog.d(TAG, String.format("onClickImage: opening %s with URL loading (port=%d)", fileItem.getName(), thumbnailServerPort));

            String[] serverParams = getThumbnailServerParams();
            String hiddenPath = serverParams[0];
            int serverPort = Integer.parseInt(serverParams[1]);

            // Note: Persistent video server is already started by MainActivity.onCreate()

            Intent intent = new Intent(context, ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.class);
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_FILE_PATH, fileItem.getPath());
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_MIME_TYPE, fileItem.getMimeType());
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_THUMBNAIL_SERVER_PORT, serverPort);
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_THUMBNAIL_SERVER_HIDDEN_PATH, hiddenPath);
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_VIDEO_SERVER_PORT, STREAMING_SERVICE_PORT);
            // Server now serves root directory, so no need to pass current directory path

            // Pass file list and current index for swipe navigation
            if (directoryObject != null) {
                java.util.List<FileItem> files = directoryObject.getDirectoryContent();
                if (files != null && !files.isEmpty()) {
                    int currentIndex = files.indexOf(fileItem);
                    if (currentIndex >= 0) {
                        intent.putParcelableArrayListExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_FILE_ITEMS,
                                new java.util.ArrayList<>(files));
                        intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    }
                }
            }

            ActivityHelper.tryStartActivity(context, intent);
        } else {
            // Fallback: download image first, then open (thumbnail service not available)
            FLog.d(TAG, "onClickImage: thumbnail service not available, downloading %s first", fileItem.getName());
            new DownloadAndOpen(DownloadAndOpen.OPEN_AS_IMAGE).execute(fileItem);
        }
    }

    private void showFileProperties(FileItem fileItem) {
        FilePropertiesDialog filePropertiesDialog = new FilePropertiesDialog()
                .setFile(fileItem)
                .setRemote(remote);
        if (remote.isCrypt()) {
            filePropertiesDialog.withHashCalculations(false);
        }
        if (getFragmentManager() != null) {
            filePropertiesDialog.show(getChildFragmentManager(), "file properties");
        }
    }

    private void setBottomBarClickListeners(final View view) {
        view.findViewById(R.id.file_download).setOnClickListener(v -> {
            downloadList = new ArrayList<>(recyclerViewAdapter.getSelectedItems());
            downloadFiles();
        });

        view.findViewById(R.id.file_move).setOnClickListener(v -> moveFiles(recyclerViewAdapter.getSelectedItems()));

        view.findViewById(R.id.file_rename).setOnClickListener(v -> {
            renameItem = recyclerViewAdapter.getSelectedItems().get(0);
            renameFiles();
        });

        view.findViewById(R.id.file_delete).setOnClickListener(v -> deleteFiles(recyclerViewAdapter.getSelectedItems()));
        view.findViewById(R.id.cancel_move).setOnClickListener(v -> cancelMoveClicked());
        view.findViewById(R.id.select_move).setOnClickListener(v -> moveLocationSelected());
        view.findViewById(R.id.new_folder).setOnClickListener(v -> onCreateNewDirectory());

        ((EditText)searchBar.findViewById(R.id.search_field)).addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchDirContent(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });
    }

    private void searchDirContent(String search) {
        List<FileItem> content = directoryObject.getDirectoryContent();
        List<FileItem> currentShown = recyclerViewAdapter.getCurrentContent();
        List<FileItem> results = new ArrayList<>();

        searchString = search;

        if (search.isEmpty()) {
            if (currentShown.equals(content)) {
                return;
            } else {
                recyclerViewAdapter.newData(content);
            }
        }

        for (FileItem item : content) {
            String fileName = item.getName().toLowerCase();
            if (fileName.contains(search.toLowerCase())) {
                results.add(item);
            }
        }

        if (currentShown.equals(results)) {
            return;
        }
        recyclerViewAdapter.newData(results);
    }

    private void cancelMoveClicked() {
        setTitle();
        recyclerViewAdapter.setMoveMode(false);
        isInMoveMode = false;
        hideMoveBar();
        fab.show();
        fab.setVisibility(View.VISIBLE);
        setFabBehaviour(true);
        showNavDrawerButtonInToolbar();
        setOptionsMenuVisibility(true);
        recyclerViewAdapter.refreshData();

        if (moveStartPath != null && !moveStartPath.equals(directoryObject.getCurrentPath())) {
            if (fetchDirectoryTask != null) {
                fetchDirectoryTask.cancel(true);
            }
            if (directoryObject.isPathInCache(moveStartPath)) {
                directoryObject.restoreFromCache(moveStartPath);
                recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            } else {
                directoryObject.setPath(moveStartPath);
                recyclerViewAdapter.clear();
                fetchDirectoryTask = new FetchDirectoryContent(true).execute();
            }
            buildStackFromPath(remoteName, moveStartPath);
            breadcrumbView.clearCrumbs();
            if (!moveStartPath.equals("//" + remoteName)) {
                breadcrumbView.buildBreadCrumbsFromPath(directoryObject.getCurrentPath());
            }
            breadcrumbView.addCrumb(remote.getDisplayName(), "//" + remoteName);
            moveStartPath = null;
        }
    }

    private void moveLocationSelected() {
        setTitle();
        hideMoveBar();
        fab.show();
        fab.setVisibility(View.VISIBLE);
        setFabBehaviour(true);
        setOptionsMenuVisibility(true);
        recyclerViewAdapter.setMoveMode(false);
        recyclerViewAdapter.refreshData();
        isInMoveMode = false;
        showNavDrawerButtonInToolbar();
        if (moveList.size() < 1) {
            Toasty.error(context, getString(R.string.error_moving_file), Toast.LENGTH_SHORT, true).show();
            moveList.clear();
            moveStartPath = null;
            return;
        }
        String oldPath = moveList.get(0).getPath();
        int index = oldPath.lastIndexOf(moveList.get(0).getName());
        String path2;
        if (index > 0) {
            path2 = moveList.get(0).getPath().substring(0, index - 1);
        } else {
            path2 = "//" + remoteName;
        }
        for (FileItem moveItem : moveList) {
            EphemeralTaskManager.Companion.queueMove(this.context, remote, directoryObject.getCurrentPath(), moveItem, path2);
        }
        Toasty.info(context, getString(R.string.moving_info), Toast.LENGTH_SHORT, true).show();
        moveList.clear();
        moveStartPath = null;
    }

    private void showCancelButtonInToolbar() {
        ActionBar actionbar = ((AppCompatActivity) context).getSupportActionBar();
        if (actionbar != null) {
            actionbar.setDisplayHomeAsUpEnabled(true);
            actionbar.setHomeAsUpIndicator(R.drawable.ic_cancel_white);
        }
    }

    private void showNavDrawerButtonInToolbar() {
        ActionBar actionbar = ((AppCompatActivity)context).getSupportActionBar();
        if (actionbar != null) {
            actionbar.setDisplayHomeAsUpEnabled(true);
            actionbar.setHomeAsUpIndicator(R.drawable.ic_menu);
        }
    }

    private void showSortMenu() {
        SortDialog sortDialog = new SortDialog();
        sortDialog
                .setTitle(R.string.sort)
                .setNegativeButton(R.string.cancel)
                .setPositiveButton(R.string.ok)
                .setSortOrder(sortOrder);
        sortDialog.show(getChildFragmentManager(), "sort dialog");
    }

    /*
     * Sort Dialog callback
     */
    @Override
    public void onPositiveButtonClick(int sortById, int sortOrderId) {
        if (!directoryObject.isDirectoryContentEmpty()) {
            sortSelected(sortById, sortOrderId);
        }
    }

    private void sortSelected(int sortById, int sortOrderId) {
        List<FileItem> directoryContent = directoryObject.getDirectoryContent();

        switch (sortById) {
            case R.id.radio_sort_name:
                if (sortOrderId == R.id.radio_sort_ascending) {
                    Collections.sort(directoryContent, new FileComparators.SortAlphaAscending());
                    sortOrder = SortDialog.ALPHA_ASCENDING;
                } else {
                    Collections.sort(directoryContent, new FileComparators.SortAlphaDescending());
                    sortOrder = SortDialog.ALPHA_DESCENDING;
                }
                break;
            case R.id.radio_sort_date:
                if (sortOrderId == R.id.radio_sort_ascending) {
                    Collections.sort(directoryContent, new FileComparators.SortModTimeAscending());
                    sortOrder = SortDialog.MOD_TIME_ASCENDING;
                } else {
                    Collections.sort(directoryContent, new FileComparators.SortModTimeDescending());
                    sortOrder = SortDialog.MOD_TIME_DESCENDING;
                }
                break;
            case R.id.radio_sort_size:
                if (sortOrderId == R.id.radio_sort_ascending) {
                    Collections.sort(directoryContent, new FileComparators.SortSizeAscending());
                    sortOrder = SortDialog.SIZE_ASCENDING;
                } else {
                    Collections.sort(directoryContent, new FileComparators.SortSizeDescending());
                    sortOrder = SortDialog.SIZE_DESCENDING;
                }
                break;
        }
        directoryObject.setContent(directoryContent);

        if (isSearchMode) {
            List<FileItem> sortedSearch = new ArrayList<>();
            List<FileItem> searchResult = recyclerViewAdapter.getCurrentContent();
            for (FileItem item : directoryContent) {
                if (searchResult.contains(item)) {
                    sortedSearch.add(item);
                }
            }
            recyclerViewAdapter.updateSortedData(sortedSearch);
        } else {
            recyclerViewAdapter.updateSortedData(directoryContent);
        }
        if (sortOrder > 0) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            sharedPreferences.edit().putInt(SHARED_PREFS_SORT_ORDER, sortOrder).apply();
        }
    }

    private void sortDirectory() {
        List<FileItem> directoryContent = directoryObject.getDirectoryContent();
        switch (sortOrder) {
            case SortDialog.MOD_TIME_DESCENDING:
                Collections.sort(directoryContent, new FileComparators.SortModTimeDescending());
                sortOrder = SortDialog.MOD_TIME_ASCENDING;
                break;
            case SortDialog.MOD_TIME_ASCENDING:
                Collections.sort(directoryContent, new FileComparators.SortModTimeAscending());
                sortOrder = SortDialog.MOD_TIME_DESCENDING;
                break;
            case SortDialog.SIZE_DESCENDING:
                Collections.sort(directoryContent, new FileComparators.SortSizeDescending());
                sortOrder = SortDialog.SIZE_ASCENDING;
                break;
            case SortDialog.SIZE_ASCENDING:
                Collections.sort(directoryContent, new FileComparators.SortSizeAscending());
                sortOrder = SortDialog.SIZE_DESCENDING;
                break;
            case SortDialog.ALPHA_ASCENDING:
                Collections.sort(directoryContent, new FileComparators.SortAlphaAscending());
                sortOrder = SortDialog.ALPHA_ASCENDING;
                break;
            case SortDialog.ALPHA_DESCENDING:
            default:
                Collections.sort(directoryContent, new FileComparators.SortAlphaDescending());
                sortOrder = SortDialog.ALPHA_DESCENDING;
        }
        directoryObject.setContent(directoryContent);
    }

    private void onClickOutsideOfView() {
        if (recyclerViewAdapter.isInSelectMode()) {
            recyclerViewAdapter.cancelSelection();
        } else if (recyclerViewAdapter.isInMoveMode()) {
            cancelMoveClicked();
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = context;
        isRunning = true;
    }

    @Override
    public void onStop() {
        super.onStop();
        if (isThumbnailsServiceRunning) {
            Intent intent = new Intent(context, ThumbnailsLoadingService.class);
            context.stopService(intent);
            isThumbnailsServiceRunning = false;
        }

        // Note: We no longer stop the prefetch server here
        // It should remain running across Activity transitions (e.g., opening MediaViewerActivity)
        // The server will be cleaned up when the app process exits

        LocalBroadcastManager.getInstance(context).unregisterReceiver(backgroundTaskBroadcastReceiver);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        breadcrumbView.clearCrumbs();
        breadcrumbView.setVisibility(View.GONE);
        searchBar.setVisibility(View.INVISIBLE);
        ((FragmentActivity) context).setTitle(originalToolbarTitle);
        showNavDrawerButtonInToolbar();
        prefChangeListener = null;
        isRunning = false;
        context = null;
    }

    public boolean onBackButtonPressed() {
        if (recyclerViewAdapter.isInSelectMode()) {
            recyclerViewAdapter.cancelSelection();
            showNavDrawerButtonInToolbar();
            return true;
        } else if (isSearchMode) {
            searchClicked();
            return true;
        } else if (fab.isOpen()) {
            fab.close(true);
            return true;
        } else if (pathStack.isEmpty()) {
            return false;
        }
        if (!isInMoveMode && !recyclerViewAdapter.isInSelectMode()) {
            fab.show();
        }
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        swipeRefreshLayout.setRefreshing(false);
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        breadcrumbView.removeLastCrumb();
        String path = pathStack.pop();
        recyclerViewAdapter.clear();
        if (!directoryObject.isContentValid(path)) {
            swipeRefreshLayout.setRefreshing(true);
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        } else if (directoryObject.isPathInCache(path)) {
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
        } else {
            directoryObject.setPath(path);
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
        return true;
    }

    @Override
    public void onFileClicked(FileItem fileItem) {
        FLog.i(TAG, "onFileClicked: file=%s, mimeType=%s", fileItem.getName(), fileItem.getMimeType());

        String type = fileItem.getMimeType();
        if (type.startsWith("video/") || type.startsWith("audio/")) {
            FLog.i(TAG, "onFileClicked: VIDEO/AUDIO branch - calling StreamTask");

            // Prefetch adjacent videos for smooth navigation
            if (type.startsWith("video/")) {
                int currentIndex = recyclerViewAdapter.getCurrentContent().indexOf(fileItem);
                prefetchAdjacentVideos(currentIndex);
            }

            // stream video or audio
            new StreamTask().execute(fileItem);
        } else if (type.startsWith("image/") && showThumbnails && isThumbnailsServiceRunning && thumbnailServerPort > 0) {
            FLog.i(TAG, "onFileClicked: IMAGE branch (thumbnails enabled) - direct URL loading with videoServerPort");
            // For images, use URL-based loading when thumbnails are enabled
            // This allows instant loading from Glide cache (same as thumbnails)
            FLog.d(TAG, "onFileClicked: opening image %s with URL loading (port=%d)", fileItem.getName(), thumbnailServerPort);

            String[] serverParams = getThumbnailServerParams();
            String hiddenPath = serverParams[0];
            int serverPort = Integer.parseInt(serverParams[1]);

            // Note: Persistent video server is already started by MainActivity.onCreate()

            Intent intent = new Intent(context, ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.class);
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_FILE_PATH, fileItem.getPath());
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_MIME_TYPE, fileItem.getMimeType());
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_THUMBNAIL_SERVER_PORT, serverPort);
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_THUMBNAIL_SERVER_HIDDEN_PATH, hiddenPath);
            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_VIDEO_SERVER_PORT, STREAMING_SERVICE_PORT);
            // Server now serves root directory, so no need to pass current directory path

            // Pass file list and current index for swipe navigation
            if (directoryObject != null) {
                java.util.List<FileItem> files = directoryObject.getDirectoryContent();
                if (files != null && !files.isEmpty()) {
                    int currentIndex = files.indexOf(fileItem);
                    if (currentIndex >= 0) {
                        intent.putParcelableArrayListExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_FILE_ITEMS,
                                new java.util.ArrayList<>(files));
                        intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    }
                }
            }

            ActivityHelper.tryStartActivity(context, intent);
        } else {
            FLog.i(TAG, "onFileClicked: DOWNLOAD branch - calling DownloadAndOpen (thumbnails disabled or non-image)");
            // download and open
            new DownloadAndOpen().execute(fileItem);
        }
    }

    @Override
    public void onDirectoryClicked(FileItem fileItem, int position) {
        directoryPosition.put(directoryObject.getCurrentPath(), position);
        breadcrumbView.addCrumb(fileItem.getName(), fileItem.getPath());
        swipeRefreshLayout.setRefreshing(true);
        pathStack.push(directoryObject.getCurrentPath());
        if (!isInMoveMode && !recyclerViewAdapter.isInSelectMode()) {
            fab.show();
        }

        if (isSearchMode) {
            searchClicked();
        }

        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }

        if (!directoryObject.isContentValid(fileItem.getPath())) {
            swipeRefreshLayout.setRefreshing(true);
            directoryObject.restoreFromCache(fileItem.getPath());
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        } else if (directoryObject.isPathInCache(fileItem.getPath())) {
            directoryObject.restoreFromCache(fileItem.getPath());
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            swipeRefreshLayout.setRefreshing(false);
        } else {
            directoryObject.setPath(fileItem.getPath());
            recyclerViewAdapter.clear();
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }

    @Override
    public void onFilesSelected() {
        handleFilesSelected();
    }

    private void handleFilesSelected() {
        int numOfSelected = recyclerViewAdapter.getNumberOfSelectedItems();

        if (numOfSelected > 0) { // something is selected
            ((FragmentActivity) context).setTitle(numOfSelected + " " + getString(R.string.selected));
            showBottomBar();
            fab.hide();
            fab.setVisibility(View.INVISIBLE);
            setFabBehaviour(false);
            setOptionsMenuVisibility(false);
            showCancelButtonInToolbar();
            if (numOfSelected > 1) {
                ((FragmentActivity) context).findViewById(R.id.file_rename).setAlpha(.5f);
                ((FragmentActivity) context).findViewById(R.id.file_rename).setClickable(false);
            } else {
                ((FragmentActivity) context).findViewById(R.id.file_rename).setAlpha(1f);
                ((FragmentActivity) context).findViewById(R.id.file_rename).setClickable(true);
            }
        }
    }

    @Override
    public void onFileDeselected() {
        if (!isInMoveMode && !recyclerViewAdapter.isInSelectMode()) {
            setTitle();
            hideBottomBar();
            fab.show();
            fab.setVisibility(View.VISIBLE);
            setFabBehaviour(true);
            setOptionsMenuVisibility(true);
            showNavDrawerButtonInToolbar();
        } else {
            handleFilesSelected();
        }
    }

    @Override
    public void onFileOptionsClicked(View view, FileItem fileItem) {
        showFileMenu(view, fileItem);
    }

    @Override
    public String[] getThumbnailServerParams() {
        return new String[]{thumbnailServerAuth + '/' + remote.getName(), String.valueOf(thumbnailServerPort)};
    }

    private void showFileMenu(View view, final FileItem fileItem) {
        PopupMenu popupMenu = new PopupMenu(context, view);
        popupMenu.getMenuInflater().inflate(R.menu.file_explorer_menu, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.action_file_properties:
                    showFileProperties(fileItem);
                    break;
                case R.id.action_open_as:
                    showOpenAsDialog(fileItem);
                    break;
                case R.id.action_serve:
                    String[] serveOptions = getResources().getStringArray(R.array.serve_options);
                    AlertDialog.Builder builder = new AlertDialog.Builder(context);
                    builder.setItems(serveOptions, (dialog, which) -> {
                        Intent intent = new Intent(getContext(), StreamingService.class);
                        switch (which) {
                            case 0: // HTTP
                                intent.putExtra(StreamingService.SERVE_PATH_ARG, fileItem.getPath());
                                intent.putExtra(StreamingService.REMOTE_ARG, remote);
                                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_HTTP);
                                intent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, true);
                                break;
                            case 1: // Webdav
                                intent.putExtra(StreamingService.SERVE_PATH_ARG, fileItem.getPath());
                                intent.putExtra(StreamingService.REMOTE_ARG, remote);
                                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_WEBDAV);
                                intent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, true);
                                break;
                            default:
                                return;
                        }
                        // GH-87: Release old server
                        context.stopService(new Intent(context, StreamingService.class));
                        tryStartService(context, intent);
                    });
                    builder.setTitle(R.string.pick_a_protocol);
                    builder.show();
                    break;
                case R.id.action_download:
                    downloadList = new ArrayList<>();
                    downloadList.add(fileItem);
                    downloadFiles();
                    break;
                case R.id.action_move:
                    moveFiles(Collections.singletonList(fileItem));
                    break;
                case R.id.action_rename:
                    renameItem = fileItem;
                    renameFiles();
                    break;
                case R.id.action_delete:
                    deleteFiles(Collections.singletonList(fileItem));
                    break;
                case R.id.action_link:
                    new LinkTask().execute(fileItem.getPath());
                    break;
                case R.id.action_sync:
                    showSyncDialog(fileItem.getPath());
                    break;
                default:
                    return false;
            }
            return true;
        });
        popupMenu.show();
        if (fileItem.isDir()) {
            popupMenu.getMenu().findItem(R.id.action_open_as).setVisible(false);
        } else {
            popupMenu.getMenu().findItem(R.id.action_sync).setVisible(false);
        }
        if (!remote.hasSyncSupport()) {
            // TODO: remove once destination sync is added.
            popupMenu.getMenu().findItem(R.id.action_sync).setVisible(false);
        }
        if (!remote.hasLinkSupport()) {
            popupMenu.getMenu().findItem(R.id.action_link).setVisible(false);
        }
        if (remote.isRemoteType(RemoteItem.LOCAL, RemoteItem.SAFW) || remote.isPathAlias()) {
            // TODO: replace with .setTitle(copy) once destination copy is added
            popupMenu.getMenu().findItem(R.id.action_download).setVisible(false);
        }
    }

    @Override
    public void onBreadCrumbClicked(String path) {
        if (fab.isOpen()) {
            fab.close(true);
        }
        if (isSearchMode) {
            searchClicked();
        }
        if (!isInMoveMode && !recyclerViewAdapter.isInSelectMode()) {
            fab.show();
        }
        if (directoryObject.getCurrentPath().equals(path)) {
            return;
        }
        swipeRefreshLayout.setRefreshing(false);
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        directoryObject.setPath(path);
        //noinspection StatementWithEmptyBody
        while (!pathStack.empty() && !pathStack.pop().equals(path)) {
            // pop stack until we find path
        }
        breadcrumbView.removeCrumbsUpTo(path);
        recyclerViewAdapter.clear();

        if (!directoryObject.isContentValid(path)) {
            swipeRefreshLayout.setRefreshing(true);
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        } else if (directoryObject.isPathInCache(path)) {
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
        } else {
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }

    private void showSyncDialog(String path) {
        syncRemotePath = path;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        String[] options = getResources().getStringArray(R.array.sync_direction_options);
        builder.setTitle(R.string.select_sync_direction);
        builder.setItems(options, (dialog, which) -> {
            int value = which+1;
            switch (value){
                case SyncDirectionObject.SYNC_REMOTE_TO_LOCAL: syncDirection = SyncDirectionObject.SYNC_REMOTE_TO_LOCAL; break;
                case SyncDirectionObject.COPY_LOCAL_TO_REMOTE: syncDirection = SyncDirectionObject.COPY_LOCAL_TO_REMOTE; break;
                case SyncDirectionObject.COPY_REMOTE_TO_LOCAL: syncDirection = SyncDirectionObject.COPY_REMOTE_TO_LOCAL; break;
                default: syncDirection = SyncDirectionObject.SYNC_LOCAL_TO_REMOTE; break;
            }

            Intent intent = new Intent(context, FilePicker.class);
            intent.putExtra(FilePicker.FILE_PICKER_PICK_DESTINATION_TYPE, true);
            startActivityForResult(intent, FILE_PICKER_SYNC_RESULT);
        });
        builder.show();
    }

    private void showBottomBar() {
        View bottomBar = ((FragmentActivity) context).findViewById(R.id.bottom_bar);
        if (bottomBar.getVisibility() == View.VISIBLE) {
            return;
        }
        bottomBar.setVisibility(View.VISIBLE);
        Animation animation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_in_animation);
        bottomBar.startAnimation(animation);
    }

    private void hideBottomBar() {
        View bottomBar = ((FragmentActivity) context).findViewById(R.id.bottom_bar);
        if (bottomBar.getVisibility() != View.VISIBLE) {
            return;
        }
        Animation animation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_out_animation);
        bottomBar.setAnimation(animation);
        bottomBar.setVisibility(View.GONE);
    }

    private void hideMoveBar() {
        View moveBar = ((FragmentActivity) context).findViewById(R.id.move_bar);
        Animation animation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_out_animation);
        moveBar.setAnimation(animation);
        moveBar.setVisibility(View.GONE);
    }

    private void deleteFiles(final List<FileItem> deleteList) {
        String title = getResources().getQuantityString(R.plurals.delete_x_items, deleteList.size(), deleteList.size());
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder
                .setTitle(title)
                .setNegativeButton(getResources().getString(R.string.cancel), null)
                .setPositiveButton(getResources().getString(R.string.delete), (dialog, which) -> {
                    recyclerViewAdapter.cancelSelection();
                    for (FileItem deleteItem : deleteList) {
                        EphemeralTaskManager.Companion.queueDelete(this.context, remote, deleteItem, directoryObject.getCurrentPath());
                    }
                    Toasty.info(context, getString(R.string.deleting_info), Toast.LENGTH_SHORT, true).show();
                });
        if(deleteList.size() == 1) {
            builder.setMessage(getString(R.string.name_will_be_deleted, deleteList.get(0).getName()));
        }
        builder.create().show();
    }

    private void renameFiles() {
        if (getFragmentManager() != null) {
            new InputDialog()
                    .setTitle(R.string.rename_file)
                    .setMessage(R.string.type_new_file_name)
                    .setNegativeButton(R.string.cancel)
                    .setPositiveButton(R.string.okay_confirmation)
                    .setFilledText(renameItem.getName())
                    .setTag("rename file")
                    .show(getChildFragmentManager(), "input dialog");
        }
    }

    private void downloadFiles() {
        Intent intent = new Intent(context, FilePicker.class);
        intent.putExtra(FilePicker.FILE_PICKER_PICK_DESTINATION_TYPE, true);
        startActivityForResult(intent, FILE_PICKER_DOWNLOAD_RESULT);
    }

    private void moveFiles(List<FileItem> moveItems) {
        if (moveItems.size() < 1) {
            return;
        }
        moveStartPath = directoryObject.getCurrentPath();
        moveList = new ArrayList<>(moveItems);
        recyclerViewAdapter.cancelSelection();
        recyclerViewAdapter.setMoveMode(true);
        isInMoveMode = true;
        showCancelButtonInToolbar();
        ((FragmentActivity) context).setTitle(getString(R.string.select_destination));
        ((FragmentActivity) context).findViewById(R.id.move_bar).setVisibility(View.VISIBLE);
        setOptionsMenuVisibility(false);
        fab.hide();
        fab.setVisibility(View.INVISIBLE);
        setFabBehaviour(false);
    }

    private void onCreateNewDirectory() {
        if (getFragmentManager() != null) {
            new InputDialog()
                    .setTitle(R.string.create_new_folder)
                    .setMessage(R.string.type_new_folder_name)
                    .setNegativeButton(R.string.cancel)
                    .setPositiveButton(R.string.okay_confirmation)
                    .setTag("new dir")
                    .setHint(R.string.hint_new_folder)
                    .show(getChildFragmentManager(), "input dialog");
        }
    }

    /*
     * Input Dialog callback
     */
    @Override
    public void onPositive(String tag, String input) {
        switch (tag) {
            case "new dir":
                if (input.trim().length() == 0) {
                    return;
                }
                String newDir;
                if (directoryObject.getCurrentPath().equals("//" + remoteName)) {
                    newDir = input;
                } else {
                    newDir = directoryObject.getCurrentPath() + "/" + input;
                }
                new MakeDirectoryTask().execute(newDir);
                break;
            case "rename file":
                if (renameItem.getName().equals(input)) {
                    return;
                }
                recyclerViewAdapter.cancelSelection();
                String newFilePath;
                if (directoryObject.getCurrentPath().equals("//" + remoteName)) {
                    newFilePath = input;
                } else {
                    newFilePath = directoryObject.getCurrentPath() + "/" + input;
                }
                new RenameFileTask().execute(renameItem.getPath(), newFilePath);
                renameItem = null;
                break;
        }
    }

    private void onUploadFiles() {
        Intent intent = new Intent(context, FilePicker.class);
        startActivityForResult(intent, FILE_PICKER_UPLOAD_RESULT);
    }

    /**
     * Prewarm serve http's directory cache by sending a HEAD request to the first video file.
     * This populates rclone's dir cache so subsequent video clicks don't trigger Google Drive API.
     * Called after FetchDirectoryContent completes.
     */
    private void prewarmFirstVideoCache() {
        if (recyclerViewAdapter == null) {
            return;
        }

        RcloneServerManager serverManager = RcloneServerManager.getInstance();
        if (!serverManager.isServerRunning()) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Find the first video file in current directory
                    List<FileItem> items = recyclerViewAdapter.getCurrentContent();
                    FileItem firstVideo = null;
                    for (FileItem item : items) {
                        if (item.getMimeType() != null && item.getMimeType().startsWith("video/")) {
                            firstVideo = item;
                            break;
                        }
                    }

                    if (firstVideo == null) {
                        FLog.d(TAG, "prewarmFirstVideoCache: no video files in directory");
                        return;
                    }

                    // Build URL for the first video
                    String remotePath = firstVideo.getPath();
                    String remoteName = remote.getName();
                    String urlPath;
                    if (remotePath.startsWith("//" + remoteName + "/")) {
                        urlPath = remotePath.substring(("//" + remoteName).length());
                    } else if (remotePath.equals("//" + remoteName)) {
                        urlPath = "/";
                    } else {
                        urlPath = remotePath.startsWith("/") ? remotePath : "/" + remotePath;
                    }

                    String url = "http://127.0.0.1:" + STREAMING_SERVICE_PORT + urlPath;
                    FLog.i(TAG, "prewarmFirstVideoCache: sending HEAD to %s", firstVideo.getName());

                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            .build();

                    Request request = new Request.Builder()
                            .url(url)
                            .head()
                            .build();

                    long startTime = System.currentTimeMillis();
                    Response response = client.newCall(request).execute();
                    long duration = System.currentTimeMillis() - startTime;

                    if (response.isSuccessful()) {
                        FLog.i(TAG, "prewarmFirstVideoCache: SUCCESS - dir cache warmed in %dms for %s",
                               duration, firstVideo.getName());
                    } else {
                        FLog.w(TAG, "prewarmFirstVideoCache: HEAD returned %d for %s",
                               response.code(), firstVideo.getName());
                    }
                    response.close();

                } catch (Exception e) {
                    FLog.e(TAG, "prewarmFirstVideoCache: error", e);
                }
            }
        }).start();
    }

    /**
     * Prefetch adjacent videos using HTTP Range requests to populate rclone's chunk cache.
     * This allows for instant playback start when navigating to nearby videos.
     * @param currentIndex Current video index in the file list
     */
    private void prefetchAdjacentVideos(final int currentIndex) {
        if (currentIndex < 0 || recyclerViewAdapter == null) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long prefetchStartTime = System.currentTimeMillis();
                    FLog.d(TAG, "prefetchAdjacentVideos: starting video prefetch at index=%d", currentIndex);

                    // Find adjacent videos within PREFETCH_IMAGE_COUNT range
                    ArrayList<FileItem> videosToPreload = new ArrayList<>();
                    List<FileItem> allItems = recyclerViewAdapter.getCurrentContent();

                    // Search backwards
                    for (int i = currentIndex - 1; i >= 0 && videosToPreload.size() < PREFETCH_IMAGE_COUNT; i--) {
                        FileItem item = allItems.get(i);
                        if (item.getMimeType() != null && item.getMimeType().startsWith("video/")) {
                            videosToPreload.add(item);
                        }
                    }

                    // Search forwards
                    for (int i = currentIndex + 1; i < allItems.size() && videosToPreload.size() < PREFETCH_IMAGE_COUNT * 2; i++) {
                        FileItem item = allItems.get(i);
                        if (item.getMimeType() != null && item.getMimeType().startsWith("video/")) {
                            videosToPreload.add(item);
                        }
                    }

                    if (videosToPreload.isEmpty()) {
                        FLog.d(TAG, "prefetchAdjacentVideos: no videos to prefetch");
                        return;
                    }

                    FLog.i(TAG, "prefetchAdjacentVideos: found %d videos to prefetch", videosToPreload.size());

                    // Note: Persistent video server is already started by MainActivity.onCreate()

                    // Prefetch each video using HTTP Range request
                    OkHttpClient httpClient = new OkHttpClient.Builder()
                            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .build();

                    for (FileItem video : videosToPreload) {
                        try {
                            long videoStartTime = System.currentTimeMillis();

                            // Build URL for video through rclone serve
                            // Format: http://localhost:29180/[path]
                            String remotePath = video.getPath();
                            // Ensure path starts with / for proper URL format
                            if (!remotePath.startsWith("/")) {
                                remotePath = "/" + remotePath;
                            }
                            String url = "http://localhost:" + STREAMING_SERVICE_PORT + remotePath;

                            // Calculate range size (min of VIDEO_PREFETCH_SIZE or actual file size)
                            long rangeEnd = Math.min(VIDEO_PREFETCH_SIZE - 1, video.getSize() - 1);
                            String rangeHeader = "bytes=0-" + rangeEnd;

                            FLog.d(TAG, "prefetchAdjacentVideos: fetching %s (range=%s, size=%d MB)",
                                   video.getName(), rangeHeader, video.getSize() / 1024 / 1024);

                            Request request = new Request.Builder()
                                    .url(url)
                                    .header("Range", rangeHeader)
                                    .build();

                            Response response = httpClient.newCall(request).execute();

                            if (response.isSuccessful() || response.code() == 206) { // 206 = Partial Content
                                // Read the response body to trigger download
                                long bytesRead = 0;
                                if (response.body() != null) {
                                    byte[] buffer = new byte[8192];
                                    java.io.InputStream inputStream = response.body().byteStream();
                                    int read;
                                    while ((read = inputStream.read(buffer)) != -1) {
                                        bytesRead += read;
                                    }
                                    inputStream.close();
                                }

                                long prefetchTime = System.currentTimeMillis() - videoStartTime;
                                FLog.i(TAG, "prefetchAdjacentVideos: SUCCESS prefetched %s (%d KB in %d ms, %.2f KB/s)",
                                       video.getName(), bytesRead / 1024, prefetchTime,
                                       prefetchTime > 0 ? (bytesRead / 1024.0) / (prefetchTime / 1000.0) : 0);
                            } else {
                                FLog.w(TAG, "prefetchAdjacentVideos: HTTP error %d for %s",
                                       response.code(), video.getName());
                            }

                            response.close();

                        } catch (Exception e) {
                            FLog.e(TAG, "prefetchAdjacentVideos: error prefetching %s", e, video.getName());
                        }
                    }

                    long totalTime = System.currentTimeMillis() - prefetchStartTime;
                    FLog.i(TAG, "prefetchAdjacentVideos: completed prefetch of %d videos in %d ms",
                           videosToPreload.size(), totalTime);

                } catch (Exception e) {
                    FLog.e(TAG, "prefetchAdjacentVideos: error", e);
                }
            }
        }).start();
    }

    /*
     * Go To Dialog Callback
     */
    @Override
    public void onRootClicked(boolean isSetAsDefault) {
        startAtRoot = true;
        directoryObject.clear();
        String path = "//" + remoteName;
        directoryObject.setPath(path);
        swipeRefreshLayout.setRefreshing(true);
        fetchDirectoryTask = new FetchDirectoryContent().execute();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (isSetAsDefault) {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), true);
            editor.putBoolean(getString(R.string.pref_key_start_at_root), true);
        } else {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), false);
        }
        editor.apply();
    }

    /*
     * Go To Dialog Callback
     */
    @Override
    public void onHomeClicked(boolean isSetAsDefault) {
        startAtRoot = false;
        directoryObject.clear();
        String path = "//" + remoteName;
        directoryObject.setPath(path);
        swipeRefreshLayout.setRefreshing(true);
        fetchDirectoryTask = new FetchDirectoryContent().execute();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (isSetAsDefault) {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), true);
            editor.putBoolean(getString(R.string.pref_key_start_at_root), false);
        } else {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), false);
        }
        editor.apply();
    }

    private void sendLogs() {
        LoadingDialog loadingDialog = new LoadingDialog()
                .setCanCancel(false)
                .setTitle(getString(R.string.uploading_logs));
        if (getFragmentManager() != null) {
            loadingDialog.show(getChildFragmentManager(), "uploading logs");
        }
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                    String urlBase = prefs.getString(getString(R.string.pref_key_log_upload_url), "");
                    String user = prefs.getString(getString(R.string.pref_key_log_upload_username), "");
                    String pass = prefs.getString(getString(R.string.pref_key_log_upload_password), "");
                    if (urlBase == null || urlBase.isEmpty()) return false;
                    if (!urlBase.endsWith("/")) urlBase += "/";
                    String androidId = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    String fileName = "log-" + androidId + ".txt";
                    java.io.File logsDir = context.getExternalFilesDir("logs");
                    if (logsDir == null || !logsDir.exists()) return false;
                    
                    String url = urlBase + "log-" + androidId + ".txt";
                    
                    // Use streaming RequestBody to avoid loading all logs into memory
                    RequestBody body = new RequestBody() {
                        @Override
                        public MediaType contentType() {
                            return MediaType.parse("text/plain; charset=utf-8");
                        }
                        
                        @Override
                        public void writeTo(okio.BufferedSink sink) throws java.io.IOException {
                            // Write rclone log files - stream directly from files
                            java.io.File[] files = logsDir.listFiles();
                            if (files != null && files.length > 0) {
                                for (java.io.File f : files) {
                                    if (!f.isFile()) continue;
                                    sink.writeUtf8("===== " + f.getName() + " =====\n");
                                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                                        String line;
                                        while ((line = r.readLine()) != null) {
                                            sink.writeUtf8(line);
                                            sink.writeUtf8("\n");
                                        }
                                    }
                                    sink.writeUtf8("\n\n");
                                }
                            }
                            
                            // Write Android logcat - stream directly from process output
                            sink.writeUtf8("===== android_logcat.txt =====\n");
                            try {
                                Process logcatProcess = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime"});
                                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(logcatProcess.getInputStream()));
                                String line;
                                String packageName = context.getPackageName();
                                while ((line = reader.readLine()) != null) {
                                    // Filter for relevant logs using centralized filter
                                    if (ca.pkay.rcloneexplorer.util.LogFilterUtil.shouldIncludeLogLine(line, packageName)) {
                                        sink.writeUtf8(line);
                                        sink.writeUtf8("\n");
                                    }
                                }
                                reader.close();
                                // Wait for logcat process to finish, with timeout
                                if (!logcatProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                                    logcatProcess.destroy();
                                    sink.writeUtf8("... (logcat collection timeout)\n");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new java.io.IOException("Log collection interrupted", e);
                            } catch (java.io.IOException e) {
                                // Network error - rethrow to let caller know
                                throw e;
                            } catch (Exception e) {
                                // Non-IO error (e.g. logcat command failed) - log and continue
                                try {
                                    sink.writeUtf8("Failed to collect logcat: " + e.getMessage() + "\n");
                                } catch (java.io.IOException ioe) {
                                    // Even writing error message failed - network is broken
                                    throw ioe;
                                }
                            }
                        }
                    };

                    // Use default OkHttpClient config (10s timeout for connect/read/write)
                    OkHttpClient client = new OkHttpClient.Builder().build();
                        Request.Builder rb = new Request.Builder()
                            .url(url)
                            .put(body)
                            .addHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                    if (user != null && !user.isEmpty()) {
                        rb.addHeader("Authorization", Credentials.basic(user, pass == null ? "" : pass));
                    }
                    Response resp = client.newCall(rb.build()).execute();
                    boolean ok = resp.isSuccessful();
                    resp.close();
                    return ok;
                } catch (Exception e) {
                    FLog.e(TAG, "sendLogs: upload failed", e);
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                Dialogs.dismissSilently(loadingDialog);
                android.widget.Toast.makeText(context,
                        success ? R.string.log_upload_success : R.string.log_upload_failed,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }.execute();
    }

    /***********************************************************************************************
     * AsyncTask classes
     ***********************************************************************************************/
    @SuppressLint("StaticFieldLeak")
    private class FetchDirectoryContent extends AsyncTask<Void, Void, List<FileItem>> {

        private boolean silentFetch;

        FetchDirectoryContent() {
            this(false);
        }

        FetchDirectoryContent(boolean silentFetch) {
            this.silentFetch = silentFetch;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            FLog.i(TAG, "FetchDirectoryContent/onPreExecute: starting");

            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(true);
            }
        }

        @Override
        protected List<FileItem> doInBackground(Void... voids) {
            FLog.i(TAG, "FetchDirectoryContent/doInBackground: remote=%s, path=%s, startAtRoot=%b",
                    remote.getName(), directoryObject.getCurrentPath(), startAtRoot);
            List<FileItem> fileItemList;
            try {
                fileItemList = rclone.getDirectoryContent(remote, directoryObject.getCurrentPath(), startAtRoot);
            } catch (Exception e) {
                e.printStackTrace();
                fileItemList = null;
            }
            return fileItemList;
        }

        @Override
        protected void onPostExecute(List<FileItem> fileItems) {
            super.onPostExecute(fileItems);
            FLog.i(TAG, "FetchDirectoryContent/onPostExecute: received %s items", fileItems != null ? String.valueOf(fileItems.size()) : "null");
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false);
            }
            if (null == getContext()) {
                FLog.w(TAG, "FetchDirectoryContent/onPostExecute: discarding refresh");
                return;
            }
            if (fileItems == null) {
                if (silentFetch) {
                    return;
                }
                Toasty.error(context, getString(R.string.error_getting_dir_content), Toast.LENGTH_SHORT, true).show();
                fileItems = new ArrayList<>();
            }

            directoryObject.setContent(fileItems);
            sortDirectory();

            if (isSearchMode && searchString != null) {
                searchDirContent(searchString);
            } else {
                if (recyclerViewAdapter != null) {
                    if (silentFetch) {
                        recyclerViewAdapter.updateData(directoryObject.getDirectoryContent());
                    } else {
                        recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
                    }
                }
            }

            // Prewarm serve http's dir cache by HEAD requesting the first video file
            // operations/list and serve http use different dir caches even in the same process
            // This eliminates the ~500ms Google Drive API delay on first video click
            prewarmFirstVideoCache();
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false);
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class RenameFileTask extends AsyncTask<String, Void, Boolean> {

        private String pathWhenTaskStarted;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pathWhenTaskStarted = directoryObject.getCurrentPath();
            swipeRefreshLayout.setRefreshing(true);
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            String oldFileName = strings[0];
            String newFileName = strings[1];

            return rclone.moveTo(remote, oldFileName, newFileName);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (!isRunning) {
                return;
            }
            if (result) {
                Toasty.success(context, getString(R.string.file_renamed_success), Toast.LENGTH_SHORT, true).show();
            } else {
                Toasty.error(context, getString(R.string.error_moving_file), Toast.LENGTH_SHORT, true).show();

            }
            if (!pathWhenTaskStarted.equals(directoryObject.getCurrentPath())) {
                directoryObject.removePathFromCache(pathWhenTaskStarted);
                return;
            }
            if (fetchDirectoryTask != null) {
                fetchDirectoryTask.cancel(true);
            }
            swipeRefreshLayout.setRefreshing(false);

            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class MakeDirectoryTask extends AsyncTask<String, Void, Boolean> {

        private String pathWhenTaskStarted;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pathWhenTaskStarted = directoryObject.getCurrentPath();
            swipeRefreshLayout.setRefreshing(true);
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            String newDir = strings[0];
            return rclone.makeDirectory(remote, newDir);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (!isRunning) {
                return;
            }
            if (result) {
                Toasty.success(context, getString(R.string.make_directory_success), Toast.LENGTH_SHORT, true).show();
            } else {
                Toasty.error(context, getString(R.string.error_mkdir), Toast.LENGTH_SHORT, true).show();
            }
            if (!pathWhenTaskStarted.equals(directoryObject.getCurrentPath())) {
                directoryObject.removePathFromCache(pathWhenTaskStarted);
                return;
            }
            swipeRefreshLayout.setRefreshing(false);
            if (fetchDirectoryTask != null) {
                fetchDirectoryTask.cancel(true);
            }
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class DownloadAndOpen extends AsyncTask<FileItem, Void, Boolean> {

        public static final int OPEN_AS_TEXT = 1;
        public static final int OPEN_AS_IMAGE = 2;
        private int openAs;
        private LoadingDialog loadingDialog;
        private String fileLocation;
        private Process process;
        private volatile boolean isCancelled = false;
        private String mimeType;
        private FileItem currentFileItem; // Store current file for prefetching

        DownloadAndOpen() {
            this(-1);
        }

        DownloadAndOpen(int openAs) {
            this.openAs = openAs;
        }

        private void cancelProcess() {
            isCancelled = true;
            if (null != process) {
                process.destroy();
            }
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            loadingDialog = new LoadingDialog()
                    .setCanCancel(false)
                    .setTitle(getString(R.string.loading_file))
                    .setNegativeButton(getResources().getString(R.string.cancel))
                    .setOnNegativeListener(() -> {
                        cancelProcess();
                        cancel(true);
                    });
            if (getFragmentManager() != null) {
                loadingDialog.show(getChildFragmentManager(), "loading dialog");
            }
        }

        @Override
        protected Boolean doInBackground(FileItem... fileItems) {
            currentFileItem = fileItems[0];
            mimeType = currentFileItem.getMimeType();

            // Download the file (images now use Glide direct loading via MediaViewerActivity)
            FLog.d(TAG, "DownloadAndOpen: downloading %s from remote", currentFileItem.getName());
            long downloadStartTime = System.currentTimeMillis();
            File[] extCacheDirs = ContextCompat.getExternalCacheDirs(context);
            if (extCacheDirs.length < 1) {
                return false;
            }
            String saveLocation = extCacheDirs[0].getAbsolutePath();

            fileLocation = saveLocation + "/" + currentFileItem.getName();

            process = rclone.downloadFile(remote, currentFileItem, saveLocation);

            if (process != null) {
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    if (!isCancelled) {
                        FLog.e(TAG, "DownloadAndOpen/doInBackground: error waiting for process", e);
                    }
                    return false;
                }
            }

            if (process != null && process.exitValue() == 0) {
                long downloadTime = System.currentTimeMillis() - downloadStartTime;
                FLog.d(TAG, "DownloadAndOpen: download completed for %s (took %dms)", currentFileItem.getName(), downloadTime);
                File savedFile = new File(fileLocation);

                // Verify downloaded file size
                if (savedFile.exists() && savedFile.length() != currentFileItem.getSize()) {
                    FLog.w(TAG, "DownloadAndOpen: downloaded file size mismatch for %s, expected=%d actual=%d",
                           currentFileItem.getName(), currentFileItem.getSize(), savedFile.length());
                    savedFile.delete();
                    return false;
                }

                savedFile.setReadOnly();
            }

            if (process != null && process.exitValue() != 0) {
                rclone.logErrorOutput(process);
            }

            return process != null && process.exitValue() == 0;
        }

        @Override
        protected void onPostExecute(Boolean status) {
            super.onPostExecute(status);
            Dialogs.dismissSilently(loadingDialog);
            if (!status) {
                return;
            }
            if (null == context) {
                FLog.e(TAG, "Cannot provide file %s using dead context", fileLocation);
                return;
            }

            FLog.i(TAG, "DownloadAndOpen.onPostExecute: openAs=%d, mimeType=%s, fileLocation=%s",
                    openAs, mimeType, fileLocation);

            // Use custom MediaViewerActivity for images (with swipe support and cache)
            if (openAs == OPEN_AS_IMAGE || (mimeType != null && mimeType.startsWith("image/"))) {
                FLog.i(TAG, "DownloadAndOpen: IMAGE branch - using local file path");

                Intent intent = new Intent(context, ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.class);
                intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_FILE_PATH, fileLocation);
                intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_MIME_TYPE, mimeType);

                // Pass file list and current index for swipe navigation
                if (directoryObject != null && currentFileItem != null) {
                    java.util.List<FileItem> files = directoryObject.getDirectoryContent();
                    if (files != null && !files.isEmpty()) {
                        // Find current index
                        int currentIndex = -1;
                        for (int i = 0; i < files.size(); i++) {
                            if (files.get(i).getPath().equals(currentFileItem.getPath())) {
                                currentIndex = i;
                                break;
                            }
                        }

                        if (currentIndex >= 0) {
                            intent.putParcelableArrayListExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_FILE_ITEMS,
                                    new java.util.ArrayList<>(files));
                            intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                        }
                    }
                }

                tryStartActivity(context, intent);
            } else if (mimeType != null && mimeType.startsWith("video/")) {
                FLog.i(TAG, "DownloadAndOpen: VIDEO branch - will call StreamTask for URL streaming");
                // All videos: use streaming (leverages rclone's chunk cache with HTTP Range prefetch)
                long videoSize = currentFileItem != null ? currentFileItem.getSize() : 0;
                FLog.d(TAG, "DownloadAndOpen: video (%d MB), using streaming with chunk cache", videoSize / 1024 / 1024);

                // Prefetch adjacent videos for smooth navigation
                if (recyclerViewAdapter != null && currentFileItem != null) {
                    int currentIndex = recyclerViewAdapter.getCurrentContent().indexOf(currentFileItem);
                    prefetchAdjacentVideos(currentIndex);
                }

                new StreamTask(StreamTask.OPEN_AS_VIDEO).execute(currentFileItem);
                return; // Don't call tryStartActivity, StreamTask will handle it
            } else {
                FLog.i(TAG, "DownloadAndOpen: OTHER branch - using system viewer with local file");
                // Use system viewer for other file types
                Uri sharedFileUri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", new File(fileLocation));
                Intent intent = new Intent(Intent.ACTION_VIEW, sharedFileUri);

                if (openAs == OPEN_AS_TEXT) {
                    intent.setDataAndType(sharedFileUri,"text/*");
                } else {
                    if (mimeType != null && !"application/octet-stream".equals(mimeType)) {
                        intent.setDataAndTypeAndNormalize(sharedFileUri, mimeType);
                    } else {
                        intent.setDataAndType(sharedFileUri, "*/*");
                    }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                tryStartActivity(context, intent);
            }

        }
    }

    @SuppressLint("StaticFieldLeak")
    private class StreamTask extends AsyncTask<FileItem, Void, Boolean> {

        private static final String TAG = "StreamTask";
        public static final int OPEN_AS_VIDEO = 0;
        public static final int OPEN_AS_AUDIO = 1;

        // Shared HTTP client for connection pooling (faster repeated requests to localhost)
        private static final OkHttpClient sharedClient = new OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        private int openAs;
        private LoadingDialog loadingDialog;
        private Intent serveIntent;
        private FileItem fileItem;
        private Intent intent;

        StreamTask() {
            this(-1);
        }

        StreamTask(int openAs) {
            this.openAs = openAs;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            long preExecuteTime = System.currentTimeMillis();
            FLog.i(TAG, "StreamTask.onPreExecute: called at %d", preExecuteTime);

            loadingDialog = new LoadingDialog()
                    .setCanCancel(true)
                    .setTitle(R.string.loading)
                    .setNegativeButton(getResources().getString(R.string.cancel))
                    .setOnNegativeListener(() -> {
                        cancel(true);
                        if (context != null) {
                            context.stopService(new Intent(context, StreamingService.class));
                        }
                    });

            if (getFragmentManager() != null) {
                FLog.i(TAG, "StreamTask.onPreExecute: calling dialog.show()");
                loadingDialog.show(getChildFragmentManager(), "loading dialog");
                FLog.i(TAG, "StreamTask.onPreExecute: dialog.show() returned");
            } else {
                FLog.w(TAG, "StreamTask.onPreExecute: FragmentManager is null, cannot show dialog");
            }
        }

        @Override
        protected Boolean doInBackground(FileItem... fileItems) {
            long doInBackgroundStartTime = System.currentTimeMillis();
            FLog.i(TAG, "StreamTask.doInBackground: called at %d - VERSION CHECK MARKER", doInBackgroundStartTime);

            if(context == null) {
                FLog.w(TAG, "doInBackground: could not start stream, context is invalid");
                return false;
            }
            fileItem = fileItems[0];
            long startTime = System.currentTimeMillis();
            FLog.i(TAG, "StreamTask: preparing video stream for file=%s", fileItem.getName());
            FLog.i(TAG, "StreamTask: file details - fullPath=%s, size=%d bytes, mimeType=%s",
                    fileItem.getPath(), fileItem.getSize(), fileItem.getMimeType());

            // Log current remote being browsed
            FLog.i(TAG, "StreamTask: current remote being browsed - name=%s, type=%s, isCrypt=%b",
                    remote.getName(), remote.getType(), remote.isCrypt());
            FLog.i(TAG, "StreamTask: current path in fragment: %s",
                    pathStack != null && !pathStack.isEmpty() ? pathStack.peek() : "(root)");

            // Ensure video server is serving the correct remote
            // (MainActivity starts server for first remote, but user might be browsing a different one)
            RcloneServerManager serverManager = RcloneServerManager.getInstance();
            FLog.i(TAG, "StreamTask: checking if server is serving remote: %s", remote.getName());
            boolean isServingCorrectRemote = serverManager.isServingRemote(remote.getName());
            FLog.i(TAG, "StreamTask: isServingRemote result: %b", isServingCorrectRemote);

            if (!isServingCorrectRemote) {
                FLog.i(TAG, "StreamTask: need to switch server to remote: %s", remote.getName());
                boolean started = serverManager.startServerForRemote(context, remote);
                if (!started) {
                    FLog.e(TAG, "StreamTask: failed to start server for remote: %s", remote.getName());
                    return false;
                }
                // Give server a moment to start
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } else {
                FLog.i(TAG, "StreamTask: server already serving correct remote: %s", remote.getName());
            }

            // Build URL using persistent root server (port 29180)
            String remotePath = fileItem.getPath();

            // Remove //remoteName prefix from path
            // Path format: //remoteName/subpath/file.jpg
            // Server serves remoteName: so URL should be: /subpath/file.jpg
            String remoteName = remote.getName();
            String urlPath;
            if (remotePath.startsWith("//" + remoteName + "/")) {
                // Path is //crypt/DCIM/file.jpg -> extract /DCIM/file.jpg
                urlPath = remotePath.substring(("//" + remoteName).length());
            } else if (remotePath.equals("//" + remoteName)) {
                // Root path //crypt -> /
                urlPath = "/";
            } else {
                // Fallback: shouldn't happen
                urlPath = remotePath.startsWith("/") ? remotePath : "/" + remotePath;
            }

            Uri uri = Uri.parse("http://127.0.0.1:" + STREAMING_SERVICE_PORT + urlPath);
            FLog.i(TAG, "StreamTask: video URL: %s (from path: %s)", uri.toString(), remotePath);

            String type = fileItem.getMimeType();

            // For video files, use MediaViewerActivity instead of system player
            if (type != null && type.startsWith("video/") && (openAs == OPEN_AS_VIDEO || openAs == -1)) {
                // Get thumbnail server params for swipe to images support
                String[] serverParams = getThumbnailServerParams();
                String hiddenPath = serverParams[0];
                int thumbnailPort = Integer.parseInt(serverParams[1]);

                intent = new Intent(context, ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.class);
                intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_FILE_PATH, fileItem.getPath());
                intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_MIME_TYPE, fileItem.getMimeType());
                intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_THUMBNAIL_SERVER_PORT, thumbnailPort);
                intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_THUMBNAIL_SERVER_HIDDEN_PATH, hiddenPath);
                intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_VIDEO_SERVER_PORT, STREAMING_SERVICE_PORT);

                // Add file list for swipe navigation
                if (recyclerViewAdapter != null) {
                    int currentIndex = recyclerViewAdapter.getCurrentContent().indexOf(fileItem);
                    if (currentIndex >= 0) {
                        intent.putParcelableArrayListExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_FILE_ITEMS,
                                new java.util.ArrayList<>(recyclerViewAdapter.getCurrentContent()));
                        intent.putExtra(ca.pkay.rcloneexplorer.Activities.MediaViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    }
                }
            } else {
                // For audio or other types, use system player
                intent = new Intent(Intent.ACTION_VIEW);

                // open as takes precedence
                if (openAs == OPEN_AS_VIDEO) {
                    intent.setDataAndType(uri, "video/*");
                } else if (openAs == OPEN_AS_AUDIO) {
                    intent.setDataAndType(uri, "audio/*");
                } else {
                    if (type != null && type.startsWith("audio/")) {
                        intent.setDataAndType(uri, "audio/*");
                    } else if (type != null && type.startsWith("video/")) {
                        intent.setDataAndType(uri, "video/*");
                    } else {
                        intent.setData(uri);
                    }
                }
            }

            // Quick availability check - persistent server should be ready immediately
            // Using shared client for connection pooling
            Request request = new Request.Builder().url(uri.toString()).head().build();

            boolean available = false;
            int maxAttempts = 12; // 12 attempts * 250ms = 3 seconds max wait

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (isCancelled()) {
                    FLog.i(TAG, "StreamTask: cancelled by user");
                    return false;
                }

                try {
                    long headStartTime = System.currentTimeMillis();
                    Response response = sharedClient.newCall(request).execute();
                    long headEndTime = System.currentTimeMillis();
                    int code = response.code();

                    long headDuration = headEndTime - headStartTime;

                    // Get headers BEFORE closing response
                    String contentLength = response.header("Content-Length", "none");
                    String lastModified = response.header("Last-Modified", "none");
                    String etag = response.header("ETag", "none");
                    String acceptRanges = response.header("Accept-Ranges", "none");
                    String cacheControl = response.header("Cache-Control", "none");

                    // Get all headers for complete picture
                    StringBuilder allHeaders = new StringBuilder();
                    for (int i = 0; i < response.headers().size(); i++) {
                        allHeaders.append(response.headers().name(i))
                                .append("=")
                                .append(response.headers().value(i))
                                .append("; ");
                    }

                    response.close();

                    if (code == 200) {
                        available = true;
                        long elapsed = System.currentTimeMillis() - startTime;
                        FLog.i(TAG, "StreamTask: server ready after %d attempts (%.1fs), HEAD took %dms, URL: %s",
                                attempt, elapsed / 1000.0, headDuration, uri.toString());
                        FLog.i(TAG, "StreamTask: response headers - Content-Length: %s, Last-Modified: %s, ETag: %s",
                                contentLength, lastModified, etag);
                        FLog.i(TAG, "StreamTask: response headers - Accept-Ranges: %s, Cache-Control: %s",
                                acceptRanges, cacheControl);
                        FLog.i(TAG, "StreamTask: all response headers: %s", allHeaders.toString());
                        break;
                    }
                    // Log all non-200 responses (404, 500, etc.)
                    FLog.w(TAG, "StreamTask: HEAD attempt %d, response code: %d, took %dms, URL: %s",
                            attempt, code, headDuration, uri.toString());
                } catch (IOException e) {
                    if (attempt == 1 || attempt == maxAttempts) {
                        FLog.w(TAG, "StreamTask: HEAD attempt %d failed: %s, URL: %s", attempt, e.getMessage(), uri.toString());
                    }
                }

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    FLog.i(TAG, "StreamTask: interrupted");
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (!available) {
                long elapsed = System.currentTimeMillis() - startTime;
                FLog.e(TAG, "StreamTask: server not available after %.1fs, URL: %s, remotePath: %s", elapsed / 1000.0, uri.toString(), remotePath);
            }

            long doInBackgroundEndTime = System.currentTimeMillis();
            FLog.i(TAG, "StreamTask.doInBackground: ending at %d, result=%b", doInBackgroundEndTime, available);
            return available;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            long postExecuteTime = System.currentTimeMillis();
            FLog.i(TAG, "StreamTask.onPostExecute: called at %d, success=%b", postExecuteTime, success);

            if (!isRunning) {
                // TODO: restructure user flow
                //       1) Start the host service and display a notification
                //          "Loading file for streaming"
                //       2) If the stream becomes available, set notification
                //          intent to start stream
                //          a) The user has not navigated away -> invoke Intent
                //          b) The user has navigated away -> Send an intent to
                //             the service to update its notification
                FLog.w(TAG, "StreamTask: user navigated away before stream could load");
                return;
            }

            FLog.i(TAG, "StreamTask.onPostExecute: calling dismissSilently(loadingDialog)");
            Dialogs.dismissSilently(loadingDialog);
            FLog.i(TAG, "StreamTask.onPostExecute: dismissSilently returned");

            if(success) {
                FLog.i(TAG, "StreamTask: success, opening video player for %s", fileItem.getName());
                Activity activity = getActivity();
                if (null == activity) {
                    FLog.e(TAG, "StreamTask: activity is null, cannot start player");
                    return;
                }
                tryStartActivityForResult(activity, intent, STREAMING_INTENT_RESULT);
            } else {
                FLog.e(TAG, "StreamTask: failed to start stream for %s", fileItem.getName());
                Toasty.error(context, getString(R.string.streaming_task_failed), Toast.LENGTH_LONG, true).show();
                // Note: We're using persistent server now, so no need to stop service
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class EmptyTrashTask extends AsyncTask<Void, Void, Boolean> {

        @Override
        protected Boolean doInBackground(Void... voids) {
            return rclone.emptyTrashCan(remoteName);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (isRunning) {
                if (result) {
                    Toasty.success(context, getString(R.string.trash_emptied), Toast.LENGTH_SHORT, true).show();
                } else {
                    Toasty.error(context, getString(R.string.error_emptying_trash), Toast.LENGTH_SHORT, true).show();
                }
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class LinkTask extends AsyncTask<String, Void, String> {

        private LoadingDialog loadingDialog;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingDialog = new LoadingDialog()
                    .setTitle(R.string.generating_public_link);
            if (getFragmentManager() != null) {
                loadingDialog.show(getChildFragmentManager(), "loading dialog");
            }
        }

        @Override
        protected String doInBackground(String... strings) {
            String linkPath = strings[0];
            return rclone.link(remote, linkPath);
        }

        @Override
        protected void onPostExecute(String link) {
            Dialogs.dismissSilently(loadingDialog);
            if (null == getContext()) {
                return;
            }

            if (link == null) {
                Toasty.error(context, getString(R.string.error_generating_link), Toast.LENGTH_SHORT, true).show();
                return;
            }

            LinkDialog linkDialog = new LinkDialog()
                    .setLinkUrl(link);
            if (getFragmentManager() != null && !getChildFragmentManager().isStateSaved()) {
                linkDialog.show(getChildFragmentManager(), "link dialog");
            }

            ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clipData = ClipData.newPlainText("Copied link", link);
            if (clipboardManager == null) {
                return;
            }
            clipboardManager.setPrimaryClip(clipData);
            Toasty.info(context, getString(R.string.link_copied_to_clipboard), Toast.LENGTH_SHORT, true).show();
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            Dialogs.dismissSilently(loadingDialog);
        }
    }
}

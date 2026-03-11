package ca.pkay.rcloneexplorer;

import android.content.Context;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.util.FLog;
import java.util.List;

/**
 * Global singleton to manage the persistent rclone video server.
 * Starts server on app launch for fast video playback.
 */
public class RcloneServerManager {
    private static final String TAG = "RcloneServerManager";
    private static final int STREAMING_SERVICE_PORT = 29180;

    private static RcloneServerManager instance;
    private Process serverProcess;
    private boolean isStarting = false;
    private String servingRemoteName = null; // Track which remote is being served

    private RcloneServerManager() {
    }

    public static synchronized RcloneServerManager getInstance() {
        if (instance == null) {
            instance = new RcloneServerManager();
        }
        return instance;
    }

    /**
     * Pre-start the video server in background for the first available remote.
     * This ensures the server is ready by the time user opens a video.
     * Should be called from MainActivity.onCreate()
     */
    public void preStartServer(Context context) {
        FLog.i(TAG, "preStartServer called - VERSION CHECK MARKER");

        if (serverProcess != null && serverProcess.isAlive()) {
            FLog.d(TAG, "preStartServer: server already running");
            return;
        }

        if (isStarting) {
            FLog.d(TAG, "preStartServer: server is already starting");
            return;
        }

        // Check if server is already running from previous session
        if (isServerReachable()) {
            FLog.i(TAG, "preStartServer: server already running from previous session");
            return;
        }

        // Start server in background thread
        isStarting = true;
        new Thread(() -> {
            try {
                FLog.i(TAG, "preStartServer: starting video server in background...");

                Rclone rclone = new Rclone(context);
                List<RemoteItem> remotes = rclone.getRemotes();

                if (remotes == null || remotes.isEmpty()) {
                    FLog.w(TAG, "preStartServer: no remotes configured, skipping server start");
                    isStarting = false;
                    return;
                }

                // Start server for the first remote (most commonly used)
                RemoteItem firstRemote = remotes.get(0);
                FLog.i(TAG, "preStartServer: starting server for remote: name=%s, type=%s, isCrypt=%b",
                        firstRemote.getName(), firstRemote.getType(), firstRemote.isCrypt());
                FLog.i(TAG, "preStartServer: available remotes count: %d", remotes.size());
                for (int i = 0; i < remotes.size(); i++) {
                    RemoteItem r = remotes.get(i);
                    FLog.i(TAG, "preStartServer:   remote[%d]: name=%s, type=%s, isCrypt=%b",
                            i, r.getName(), r.getType(), r.isCrypt());
                }

                serverProcess = rclone.serve(
                        Rclone.SERVE_PROTOCOL_HTTP,
                        STREAMING_SERVICE_PORT,
                        false, // no authentication
                        null,  // no username
                        null,  // no password
                        firstRemote,
                        "",    // root directory
                        null   // no hidden path
                );

                if (serverProcess == null) {
                    FLog.e(TAG, "preStartServer: failed to start server");
                    isStarting = false;
                    return;
                }

                servingRemoteName = firstRemote.getName();
                FLog.i(TAG, "preStartServer: video server started successfully on port %d for remote: %s", STREAMING_SERVICE_PORT, servingRemoteName);
                isStarting = false;

            } catch (Exception e) {
                FLog.e(TAG, "preStartServer: error starting server", e);
                serverProcess = null;
                isStarting = false;
            }
        }).start();
    }

    /**
     * Check if server is reachable and actually responding to HTTP requests
     */
    private boolean isServerReachable() {
        try {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url("http://127.0.0.1:" + STREAMING_SERVICE_PORT + "/")
                    .head()
                    .build();

            okhttp3.Response response = client.newCall(request).execute();
            int code = response.code();
            response.close();

            boolean ready = (code >= 200 && code < 500);
            FLog.d(TAG, "isServerReachable: server responded with HTTP %d, ready=%b", code, ready);
            return ready;
        } catch (Exception e) {
            FLog.d(TAG, "isServerReachable: port %d not reachable: %s", STREAMING_SERVICE_PORT, e.getMessage());
            return false;
        }
    }

    /**
     * Check if server process is alive
     */
    public boolean isServerRunning() {
        return (serverProcess != null && serverProcess.isAlive()) || isServerReachable();
    }

    /**
     * Check if the server is serving the specified remote
     */
    public boolean isServingRemote(String remoteName) {
        FLog.i(TAG, "isServingRemote: called with remoteName=%s", remoteName);
        FLog.i(TAG, "isServingRemote: currently serving remoteName=%s", servingRemoteName);

        boolean serverRunning = isServerRunning();
        FLog.i(TAG, "isServingRemote: isServerRunning=%b", serverRunning);

        if (!serverRunning) {
            FLog.i(TAG, "isServingRemote: server not running, returning false");
            return false;
        }
        // If we don't know which remote is being served (app restart scenario),
        // we can't be sure, so return false to trigger a restart
        if (servingRemoteName == null) {
            FLog.i(TAG, "isServingRemote: unknown remote (possibly from previous session), will restart");
            return false;
        }
        boolean match = servingRemoteName.equals(remoteName);
        FLog.i(TAG, "isServingRemote: current=%s, requested=%s, match=%b", servingRemoteName, remoteName, match);
        return match;
    }

    /**
     * Stop the current server process
     */
    public void stopServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            FLog.i(TAG, "stopServer: stopping server for remote: %s", servingRemoteName);
            serverProcess.destroy();
            try {
                serverProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                FLog.w(TAG, "stopServer: interrupted while waiting for server to stop");
            }
        }
        serverProcess = null;
        servingRemoteName = null;
    }

    /**
     * Start server for a specific remote (called from FileExplorerFragment)
     */
    public synchronized boolean startServerForRemote(Context context, RemoteItem remote) {
        if (isStarting) {
            FLog.d(TAG, "startServerForRemote: server is already starting");
            return false;
        }

        // If server is already serving this remote, reuse it
        if (isServingRemote(remote.getName())) {
            FLog.i(TAG, "startServerForRemote: server already serving remote: %s", remote.getName());
            return true;
        }

        // Stop existing server if serving different remote
        if (isServerRunning()) {
            FLog.i(TAG, "startServerForRemote: stopping server for %s to switch to %s", servingRemoteName, remote.getName());
            stopServer();
            // Give it a moment to release the port
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                // ignore
            }
        }

        // Start new server for requested remote
        isStarting = true;
        try {
            FLog.i(TAG, "startServerForRemote: starting server for remote: name=%s, type=%s, isCrypt=%b",
                    remote.getName(), remote.getType(), remote.isCrypt());

            Rclone rclone = new Rclone(context);
            serverProcess = rclone.serve(
                    Rclone.SERVE_PROTOCOL_HTTP,
                    STREAMING_SERVICE_PORT,
                    false, null, null,
                    remote,
                    "",    // root directory
                    null
            );

            if (serverProcess == null) {
                FLog.e(TAG, "startServerForRemote: failed to start server");
                return false;
            }

            servingRemoteName = remote.getName();
            FLog.i(TAG, "startServerForRemote: server started successfully on port %d for remote: %s", STREAMING_SERVICE_PORT, servingRemoteName);
            return true;

        } catch (Exception e) {
            FLog.e(TAG, "startServerForRemote: error starting server", e);
            serverProcess = null;
            servingRemoteName = null;
            return false;
        } finally {
            isStarting = false;
        }
    }
}

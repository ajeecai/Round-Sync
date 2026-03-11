package ca.pkay.rcloneexplorer;

import android.app.Application;
import ca.pkay.rcloneexplorer.util.FLog;

/**
 * Custom Application class for global initialization.
 */
public class RcloneApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize FLog with application context
        FLog.init(this);
    }
}

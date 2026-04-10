package ca.pkay.rcloneexplorer.Settings;

import static ca.pkay.rcloneexplorer.Activities.MainActivity.MAIN_ACTIVITY_START_EXPORT;
import static ca.pkay.rcloneexplorer.Activities.MainActivity.MAIN_ACTIVITY_START_IMPORT;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import ca.pkay.rcloneexplorer.Activities.MainActivity;
import ca.pkay.rcloneexplorer.R;

public class SettingsFragment extends Fragment {

    public enum Category {
        GENERAL,
        FILE_ACCESS,
        LOOK_AND_FEEL,
        LOGGING,
        NOTIFICATION,
        ARCHIVE
    }
    private OnSettingCategorySelectedListener clickListener;

    public interface OnSettingCategorySelectedListener {
        void onSettingCategoryClicked(Category category);
    }

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public SettingsFragment() {
    }

    public static SettingsFragment newInstance() {
        return new SettingsFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.settings_fragment, container, false);
        setClickListeners(view);

        if (getActivity() != null) {
            getActivity().setTitle(getString(R.string.settings));
        }

        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof OnSettingCategorySelectedListener) {
            clickListener = (OnSettingCategorySelectedListener) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement listener");
        }
    }

    private void setClickListeners(View view) {

        view.findViewById(R.id.general_settings).setOnClickListener(v -> clickListener.onSettingCategoryClicked(Category.GENERAL));

        view.findViewById(R.id.logging_settings).setOnClickListener(v -> clickListener.onSettingCategoryClicked(Category.LOGGING));

        view.findViewById(R.id.look_and_feel_settings).setOnClickListener(v -> clickListener.onSettingCategoryClicked(Category.LOOK_AND_FEEL));

        view.findViewById(R.id.notification_settings).setOnClickListener(v -> clickListener.onSettingCategoryClicked(Category.NOTIFICATION));

        view.findViewById(R.id.file_access_settings).setOnClickListener(v -> clickListener.onSettingCategoryClicked(Category.FILE_ACCESS));

        view.findViewById(R.id.archive_settings).setOnClickListener(v -> clickListener.onSettingCategoryClicked(Category.ARCHIVE));

        view.findViewById(R.id.importSettings).setOnClickListener(v -> startActivity(getImportIntent()));
        view.findViewById(R.id.exportSettings).setOnClickListener(v -> startActivity(getExportIntent()));
    }

    private Intent getImportIntent() {
        Intent i = new Intent(this.getContext(), MainActivity.class);
        i.setAction(MAIN_ACTIVITY_START_IMPORT);
        return i;
    }

    private Intent getExportIntent() {
        Intent i = new Intent(this.getContext(), MainActivity.class);
        i.setAction(MAIN_ACTIVITY_START_EXPORT);
        return i;
    }
}

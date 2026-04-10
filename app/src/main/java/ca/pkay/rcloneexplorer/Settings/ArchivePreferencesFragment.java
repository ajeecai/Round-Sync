package ca.pkay.rcloneexplorer.Settings;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import java.util.List;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;

public class ArchivePreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.settings_archive_preferences, rootKey);
        if (getActivity() != null) {
            getActivity().setTitle(getString(R.string.archive_settings));
        }

        PreferenceCategory remotesCategory = findPreference("archive_remotes_category");
        Rclone rclone = new Rclone(getContext());
        List<RemoteItem> remotes = rclone.getRemotes();

        if (remotes != null && remotesCategory != null) {
            for (RemoteItem remote : remotes) {
                EditTextPreference remotePreference = new EditTextPreference(requireContext());
                remotePreference.setKey("pref_key_archive_root_" + remote.getName());
                remotePreference.setTitle(remote.getName());
                remotePreference.setDialogTitle(getString(R.string.archive_edit_root_title, remote.getName()));
                remotePreference.setDefaultValue("/");
                remotePreference.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
                remotesCategory.addPreference(remotePreference);
            }
        }
    }
}

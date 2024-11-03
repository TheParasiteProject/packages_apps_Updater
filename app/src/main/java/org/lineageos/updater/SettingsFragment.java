/*
 * Copyright (C) 2024 PixelOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.updater;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemProperties;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.Utils;

public class SettingsFragment extends PreferenceFragmentCompat
        implements OnPreferenceChangeListener {
    private UpdaterController mUpdaterController;
    private ListPreference mAutoUpdateCheckInterval;
    private SwitchPreferenceCompat mAutoDelete;
    private SwitchPreferenceCompat mAbPerfMode;
    private SwitchPreferenceCompat mUpdateRecovery;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        mUpdaterController = UpdaterController.getInstance(getContext());
        mAutoUpdateCheckInterval = findPreference(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL);
        mAutoDelete = findPreference(Constants.PREF_AUTO_DELETE_UPDATES);
        mAbPerfMode = findPreference(Constants.PREF_AB_PERF_MODE);
        mUpdateRecovery = findPreference(Constants.PREF_UPDATE_RECOVERY);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        PreferenceCategory category = getPreferenceScreen().findPreference("general");

        if (category != null) {
            if (supportsPerfMode()) {
                mAbPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));
                mAbPerfMode.setOnPreferenceChangeListener(this);
            } else {
                category.removePreference(mAbPerfMode);
            }

            if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
                // Hide the update feature if explicitly requested.
                // Might be the case of A-only devices using prebuilt vendor images.
                category.removePreference(mUpdateRecovery);
            } else if (Utils.isRecoveryUpdateExecPresent()) {
                mUpdateRecovery.setChecked(
                        SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, true));
                mUpdateRecovery.setOnPreferenceChangeListener(this);
            } else {
                // There is no recovery updater script in the device, so the feature is considered
                // forcefully enabled, just to avoid users to be confused and complain that
                // recovery gets overwritten. That's the case of A/B and recovery-in-boot devices.
                mUpdateRecovery.setChecked(prefs.getBoolean(Constants.PREF_UPDATE_RECOVERY, true));
                category.removePreference(mUpdateRecovery);
                mUpdateRecovery.setOnPreferenceChangeListener(this);
            }
        }

        mAutoUpdateCheckInterval.setValue(prefs.getString(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL, "0"));

        mAutoUpdateCheckInterval.setOnPreferenceChangeListener(this);
        mAutoDelete.setOnPreferenceChangeListener(this);
    }

    private boolean supportsPerfMode() {
        return Utils.isABDevice() && getResources().getBoolean(R.bool.config_ab_perf_mode);
    }

    private boolean isPerfModeEnabled(boolean isEnabled) {
        return supportsPerfMode() && isEnabled;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        final String key = preference.getKey();

        if (Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL.equals(key)) {
            prefs.edit().putString(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL, (String) newValue).apply();
            if (Utils.isUpdateCheckEnabled(getContext())) {
                UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(getContext());
            } else {
                UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(requireContext());
                UpdatesCheckReceiver.cancelUpdatesCheck(requireContext());
            }
        } else if (Constants.PREF_AUTO_DELETE_UPDATES.equals(key)) {
            prefs.edit().putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, (Boolean) newValue).apply();
        } else if (Constants.PREF_AB_PERF_MODE.equals(key)) {
            prefs.edit().putBoolean(Constants.PREF_AB_PERF_MODE, (Boolean) newValue).apply();
            mUpdaterController.setPerformanceMode(isPerfModeEnabled((Boolean) newValue));
        } else if (Constants.PREF_UPDATE_RECOVERY.equals(key)) {
            if (Utils.isRecoveryUpdateExecPresent()) {
                SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY, String.valueOf(newValue));
            }
        }

        return true;
    }
}

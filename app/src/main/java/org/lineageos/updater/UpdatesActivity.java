/*
 * Copyright (C) 2017-2023 The LineageOS Project
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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.icu.text.DateFormat;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import io.noties.markwon.Markwon;

import org.json.JSONException;
import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.download.DownloadClient;
import org.lineageos.updater.misc.BuildInfoUtils;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.StringGenerator;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.Update;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public class UpdatesActivity extends AppCompatActivity implements UpdateImporter.Callbacks {

    private static final String TAG = "UpdatesActivity";
    private static final int BATTERY_PLUGGED_ANY =
            BatteryManager.BATTERY_PLUGGED_AC
                    | BatteryManager.BATTERY_PLUGGED_USB
                    | BatteryManager.BATTERY_PLUGGED_WIRELESS;
    private BroadcastReceiver mBroadcastReceiver;
    private BottomAppBar mBottomAppBar;
    private CircularProgressIndicator mCircularProgress;
    private FrameLayout mCircularProgressContainer;
    private ImageView mUpdateIcon;
    private LinearLayout mCurrentBuildInfo;
    private LinearLayout mProgress;
    private LinearLayout mUpdateStatusLayout;
    private LinearProgressIndicator mProgressBar;
    private MaterialButton mPrimaryActionButton;
    private MaterialButton mSecondaryActionButton;
    private MaterialCardView mWarnMeteredConnectionCard;
    private SwipeRefreshLayout mSwipeRefresh;
    private TextView mChangelogSection;
    private TextView mProgressText;
    private TextView mProgressPercent;
    private TextView mUpdateInfoWarning;
    private TextView mUpdateStatus;
    private String mLatestDownloadId;
    private UpdaterController mUpdaterController;
    private UpdaterService mUpdaterService;
    private final ServiceConnection mConnection =
            new ServiceConnection() {

                @Override
                public void onServiceConnected(ComponentName className, IBinder service) {
                    UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
                    mUpdaterService = binder.getService();
                    mUpdaterController = mUpdaterService.getUpdaterController();
                    getUpdatesList();
                }

                @Override
                public void onServiceDisconnected(ComponentName componentName) {
                    mUpdaterService = null;
                    mUpdaterController = null;
                }
            };
    private ActivityResultLauncher<Intent> mExportUpdate =
        registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent intent = result.getData();
                        if (intent != null) {
                            Uri uri = intent.getData();
                            exportUpdateInternal(uri);
                        }
                    }
                });
    private UpdateInfo mToBeExported = null;

    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;

    private static boolean isScratchMounted() {
        try (Stream<String> lines = Files.lines(Path.of("/proc/mounts"))) {
            return lines.anyMatch(x -> x.split(" ")[1].equals("/mnt/scratch"));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        mUpdateImporter = new UpdateImporter(this, this);

        mBroadcastReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String downloadId =
                                intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                        if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                            handleDownloadStatusChange(downloadId);
                            updateUI(downloadId);
                        } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(
                                        intent.getAction())
                                || UpdaterController.ACTION_INSTALL_PROGRESS.equals(
                                        intent.getAction())) {
                            updateUI(downloadId);
                        } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(
                                intent.getAction())) {
                            mLatestDownloadId = null;
                            updateUI(null);
                            downloadUpdatesList();
                        }
                    }
                };

        MaterialToolbar toolbar = requireViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(null);
        }

        mBottomAppBar = requireViewById(R.id.bottomAppBar);
        mChangelogSection = requireViewById(R.id.changelogSection);
        mCircularProgress = requireViewById(R.id.updateRefreshProgress);
        mCircularProgressContainer = requireViewById(R.id.updateRefreshProgressContainer);
        mCurrentBuildInfo = requireViewById(R.id.buildInfo);
        mPrimaryActionButton = requireViewById(R.id.primaryButton);
        mSecondaryActionButton = requireViewById(R.id.secondaryButton);
        mProgress = requireViewById(R.id.downloadProgress);
        mProgressBar = requireViewById(R.id.progressBar);
        mProgressText = requireViewById(R.id.progressText);
        mProgressPercent = requireViewById(R.id.progressPercent);
        mSwipeRefresh = requireViewById(R.id.swipeRefresh);
        mUpdateInfoWarning = requireViewById(R.id.updateInfoWarning);
        mUpdateStatus = requireViewById(R.id.updateStatus);
        mUpdateStatusLayout = requireViewById(R.id.updateStatusLayout);
        mUpdateIcon = requireViewById(R.id.updateIcon);
        mWarnMeteredConnectionCard = requireViewById(R.id.meteredWarningCard);

        TextView headerBuildVersion = requireViewById(R.id.currentBuildVersion);
        headerBuildVersion.setText(
                getString(R.string.header_android_version, Build.VERSION.RELEASE));

        TextView headerSecurityPatch = requireViewById(R.id.currentSecurityPatch);
        headerSecurityPatch.setText(
                getString(R.string.header_android_security_patch, Utils.getSecurityPatch()));

        NestedScrollView mNestedScrollView = requireViewById(R.id.nestedScrollView);

        updateLastCheckedString();

        mSwipeRefresh.setOnRefreshListener(
                () -> {
                    new Handler(Looper.getMainLooper())
                            .postDelayed(
                                    () -> {
                                        if (mSwipeRefresh.isRefreshing()) {
                                            mSwipeRefresh.setRefreshing(false);
                                        }
                                    },
                                    0);

                    mUpdateInfoWarning.setVisibility(View.GONE);
                    downloadUpdatesList();
                });
        mSwipeRefresh.setEnabled(true);

        mSwipeRefresh.setProgressBackgroundColorSchemeResource(R.color.background);
        // Not sure if there's a better way
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true);
        mSwipeRefresh.setColorSchemeColors(typedValue.data);

        // Setup insets for Edge-to-Edge compatibility
        // from SettingsLib/CollapsingToolBar/EdgeToEdgeUtils.java
        ViewCompat.setOnApplyWindowInsetsListener(
                this.requireViewById(android.R.id.content),
                (v, windowInsets) -> {
                    Insets insets =
                            windowInsets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.ime()
                                            | WindowInsetsCompat.Type.displayCutout());
                    int statusBarHeight =
                            this.getWindow()
                                    .getDecorView()
                                    .getRootWindowInsets()
                                    .getInsets(WindowInsetsCompat.Type.statusBars())
                                    .top;
                    v.setPadding(insets.left, statusBarHeight, insets.right, insets.bottom);

                    return WindowInsetsCompat.CONSUMED;
                });

        mNestedScrollView.setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener)
                        (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                            if (!v.canScrollVertically(1)) {
                                // Prevent swipeRefresh from triggering when swiping quickly to the
                                // top
                                mSwipeRefresh.setEnabled(false);
                                mBottomAppBar.setElevation(0f);
                            } else {
                                mSwipeRefresh.setEnabled(true);
                                mBottomAppBar.setElevation(8f);
                            }
                        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            mUpdateImporter.stopImport();
        }

        super.onPause();
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        handleExportMenuVisibility(menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        handleExportMenuVisibility(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_preferences) {
            Intent settingsActivity = new Intent(this, SettingsActivity.class);
            startActivity(settingsActivity);
            return true;
        } else if (itemId == R.id.menu_show_changelog) {
            Intent openUrl =
                    new Intent(Intent.ACTION_VIEW, Uri.parse(Utils.getChangelogURL(this, true)));
            startActivity(openUrl);
            return true;
        } else if (itemId == R.id.menu_local_update) {
            mUpdateImporter.openImportPicker();
            return true;
        } else if (itemId == R.id.menu_export_update) {
            if (mUpdaterController != null && mLatestDownloadId != null) {
                exportUpdate(mUpdaterController.getUpdate(mLatestDownloadId));
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void handleExportMenuVisibility(Menu menu) {
        UpdateInfo update;
        if (mUpdaterController == null || mLatestDownloadId == null) {
            update = null;
        } else {
            update = mUpdaterController.getUpdate(mLatestDownloadId);   
        }
        if (update == null) {
            menu.findItem(R.id.menu_export_update).setVisible(false);
            return;
        }
        menu.findItem(R.id.menu_export_update).setVisible(
            update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED);
    }

    private void exportUpdateInternal(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    private void exportUpdate(UpdateInfo update) {
        if (update == null) return;
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onImportStarted() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }

        importDialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.local_update_import)
                        .setView(R.layout.progress_dialog)
                        .setCancelable(false)
                        .create();

        importDialog.show();
    }

    @Override
    public void onImportCompleted(Update update) {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }

        if (update == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.local_update_import)
                    .setMessage(R.string.local_update_import_failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final Runnable deleteUpdate =
                () -> UpdaterController.getInstance(this).deleteUpdate(update.getDownloadId());

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(
                        R.string.local_update_import_install,
                        (dialog, which) -> {
                            // Update UI
                            updateUI(update.getDownloadId());
                            getUpdatesList();
                            Utils.triggerUpdate(this, update.getDownloadId());
                        })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> deleteUpdate.run())
                .setOnCancelListener((dialog) -> deleteUpdate.run())
                .show();
    }

    private void startRefreshAnimation() {
        mCircularProgressContainer.setVisibility(View.VISIBLE);
        mCircularProgress.setVisibility(View.VISIBLE);
        mCurrentBuildInfo.setVisibility(View.GONE);
        mUpdateStatusLayout.setVisibility(View.GONE);
        mBottomAppBar.setVisibility(View.GONE);
        mUpdateStatus.setText(R.string.checking_for_update);
        mCircularProgress.setIndicatorSize(600);
        mCircularProgress.animate().alpha(1f).start();
        mCircularProgress.show();
    }

    private void stopRefreshAnimation() {
        mCircularProgress.animate().alpha(0f).start();
        mCircularProgress.setVisibility(View.GONE);
        mCircularProgressContainer.setVisibility(View.GONE);
        mUpdateStatusLayout.setVisibility(View.VISIBLE);
        mBottomAppBar.setVisibility(View.VISIBLE);
    }

    private void setupButtonAction(Action action, MaterialButton button, boolean enabled) {
        if (action == Action.HIDE_BUTTON) {
            if (button.isShown()) {
                button.setVisibility(View.GONE);
            }
            return;
        }
        if (!button.isShown()) {
            button.setVisibility(View.VISIBLE);
        }
        final View.OnClickListener clickListener;
        switch (action) {
            case CHECK_UPDATES:
                button.setText(R.string.check_for_update);
                button.setEnabled(enabled);
                clickListener =
                        enabled
                                ? v -> {
                                    mUpdateInfoWarning.setVisibility(View.GONE);
                                    downloadUpdatesList();
                                }
                                : null;
                break;
            case DOWNLOAD:
                button.setText(R.string.action_download);
                button.setEnabled(enabled);
                clickListener =
                        enabled ? v -> mUpdaterController.startDownload(mLatestDownloadId) : null;
                break;
            case PAUSE:
                button.setText(R.string.action_pause);
                button.setEnabled(enabled);
                clickListener =
                        enabled ? v -> mUpdaterController.pauseDownload(mLatestDownloadId) : null;
                break;
            case RESUME:
                button.setText(R.string.action_resume);
                button.setEnabled(enabled);
                clickListener =
                        enabled
                                ? v -> {
                                    mUpdateInfoWarning.setVisibility(View.GONE);
                                    UpdateInfo update =
                                            mUpdaterController.getUpdate(mLatestDownloadId);
                                    if (Utils.canInstall(update)
                                            || update.getFile().length() == update.getFileSize()) {
                                        mUpdaterController.resumeDownload(mLatestDownloadId);
                                    } else {
                                        showUpdateInfo(R.string.snack_update_not_installable);
                                    }
                                }
                                : null;
                break;
            case INSTALL:
                button.setText(R.string.action_install);
                button.setEnabled(enabled);
                clickListener =
                        enabled
                                ? v -> {
                                    if (Utils.canInstall(
                                            mUpdaterController.getUpdate(mLatestDownloadId))) {
                                        getInstallDialog(mLatestDownloadId).show();
                                    } else {
                                        showUpdateInfo(R.string.snack_update_not_installable);
                                    }
                                }
                                : null;
                break;
            case CANCEL_INSTALLATION:
                button.setText(R.string.action_cancel);
                button.setEnabled(enabled);
                clickListener =
                        enabled
                                ? v -> {
                                    getCancelInstallationDialog(mLatestDownloadId).show();
                                }
                                : null;
                break;
            case REBOOT:
                button.setText(R.string.action_reboot);
                button.setEnabled(enabled);
                clickListener = enabled ? v -> getRebootInstallationDialog().show() : null;
                break;
            default:
                clickListener = null;
        }

        // Disable action mode when a button is clicked
        button.setOnClickListener(
                v -> {
                    if (clickListener != null) {
                        clickListener.onClick(v);
                    }
                });
    }

    private void loadUpdatesList(File jsonFile)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        if (sortedUpdates.isEmpty()) {
            updateUI(null);
        } else {
            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            mLatestDownloadId = sortedUpdates.get(0).getDownloadId();
            updateUI(mLatestDownloadId);
        }
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList();
        }
    }

    private void processNewJson(File json, File jsonNew) {
        try {
            loadUpdatesList(jsonNew);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            updateLastCheckedString();
            if (json.exists()
                    && Utils.isUpdateCheckEnabled(this)
                    && Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            updateUI(
                null,
                R.drawable.ic_system_update_warning,
                R.string.check_for_update_failed,
                R.string.snack_updates_check_failed);
        }
    }

    private void downloadUpdatesList() {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback =
                new DownloadClient.DownloadCallback() {
                    @Override
                    public void onFailure(final boolean cancelled) {
                        Log.e(TAG, "Could not download updates list");
                        runOnUiThread(
                                () -> {
                                    stopRefreshAnimation();
                                    updateUI(
                                        null,
                                        R.drawable.ic_system_update_warning,
                                        R.string.check_for_update_failed,
                                        !cancelled
                                            ? R.string.snack_updates_check_failed
                                            : -1);
                                });
                    }

                    @Override
                    public void onResponse(DownloadClient.Headers headers) {}

                    @Override
                    public void onSuccess() {
                        runOnUiThread(
                                () -> {
                                    Log.d(TAG, "List downloaded");
                                    processNewJson(jsonFile, jsonFileTmp);
                                    stopRefreshAnimation();
                                });
                    }
                };

        final DownloadClient downloadClient;
        try {
            downloadClient =
                    new DownloadClient.Builder()
                            .setUrl(url)
                            .setDestination(jsonFileTmp)
                            .setDownloadCallback(callback)
                            .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            updateUI(
                null,
                R.drawable.ic_system_update_warning,
                R.string.check_for_update_failed,
                R.string.snack_updates_check_failed);
            return;
        }

        startRefreshAnimation();
        downloadClient.start();
    }

    private void setChangelogs(TextView mShowChangelogs) {
        mShowChangelogs.setVisibility(View.VISIBLE);
        String changelogUrl = Utils.getChangelogURL(this, false);

        // Use ExecutorService for background work
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.execute(
                () -> {
                    StringBuilder result = new StringBuilder();
                    try {
                        URL url = new URL(changelogUrl);
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setConnectTimeout(5000);
                        connection.setReadTimeout(5000);

                        BufferedReader reader =
                                new BufferedReader(
                                        new InputStreamReader(connection.getInputStream()));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            result.append(line).append("\n");
                        }
                        reader.close();
                        connection.disconnect();

                        // Update the UI on the main thread
                        runOnUiThread(
                                () -> {
                                    Markwon markwon = Markwon.create(this);
                                    markwon.setMarkdown(mShowChangelogs, result.toString());
                                });
                    } catch (Exception e) {
                        Log.e(TAG, "Could not load changelog", e);
                        runOnUiThread(
                                () -> {
                                    Markwon markwon = Markwon.create(this);
                                    markwon.setMarkdown(
                                            mShowChangelogs, "Failed to load changelogs");
                                });
                    }
                });
    }

    private void updateLastCheckedString() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        long lastCheck = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000;
        String lastCheckString =
                getString(
                        R.string.header_last_updates_check,
                        StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                        StringGenerator.getTimeLocalized(this, lastCheck));
        TextView headerLastCheck = requireViewById(R.id.lastSuccessfulCheck);
        headerLastCheck.setText(lastCheckString);
    }

    private void handleDownloadStatusChange(String downloadId) {
        if (Update.LOCAL_ID.equals(downloadId)) {
            return;
        }

        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                setupButtonAction(Action.CANCEL_INSTALLATION, mSecondaryActionButton, true);
                mUpdateIcon.setImageResource(R.drawable.ic_system_update_warning);
                showUpdateInfo(R.string.snack_download_failed);
                break;
            case VERIFICATION_FAILED:
                mUpdateIcon.setImageResource(R.drawable.ic_system_update_warning);
                showUpdateInfo(R.string.snack_download_verification_failed);
                break;
            case VERIFIED:
                mUpdateInfoWarning.setVisibility(View.GONE);
                mUpdateStatus.setText(R.string.snack_download_verified);
                break;
        }
    }

    private void resetView(int iconRes, int statusRes, int infoRes) {
        mUpdateIcon.setImageResource(iconRes);
        mUpdateStatus.setText(statusRes);
        mProgress.setVisibility(View.GONE);
        mCurrentBuildInfo.setVisibility(View.VISIBLE);
        mSwipeRefresh.setEnabled(false);
        mChangelogSection.setVisibility(View.GONE);
        mWarnMeteredConnectionCard.setVisibility(View.GONE);
        setupButtonAction(Action.HIDE_BUTTON, mSecondaryActionButton, false);
        if (iconRes == R.drawable.ic_system_update_warning && infoRes != -1) {
            showUpdateInfo(infoRes);
        } else {
            mUpdateInfoWarning.setVisibility(View.GONE);
        }
        setupButtonAction(Action.CHECK_UPDATES, mPrimaryActionButton, true);
    }

    private void updateUI(String downloadId) {
        updateUI(
            downloadId,
            R.drawable.ic_system_update,
            R.string.system_up_to_date,
            -1);
    }

    private void updateUI(String downloadId, int iconRes, int statusRes, int infoRes) {
        UpdateInfo update;
        if (mUpdaterController == null || downloadId == null) {
            update = null;
        } else {
            update = mUpdaterController.getUpdate(downloadId);
        }
        if (update == null || update.getStatus()  == UpdateStatus.DELETED) {
            resetView(iconRes, statusRes, infoRes);
            return;
        }

        boolean activeLayout;
        switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.UNKNOWN:
                activeLayout = update.getStatus() == UpdateStatus.STARTING;
                break;
            case UpdateStatus.Persistent.VERIFIED:
                activeLayout = update.getStatus() == UpdateStatus.INSTALLING;
                break;
            case UpdateStatus.Persistent.INCOMPLETE:
                activeLayout = true;
                break;
            default:
                activeLayout = mUpdaterController.isVerifyingUpdate();
                break;
        }

        if (activeLayout) {
            handleActiveStatus(update);
        } else {
            handleNotActiveStatus(update);
        }

        mLatestDownloadId = downloadId;
    }

    private void handleActiveStatus(UpdateInfo update) {
        mCurrentBuildInfo.setVisibility(View.GONE);
        final String downloadId = update.getDownloadId();
        boolean showProgress = true;
        if (mUpdaterController.isDownloading(downloadId)) {
            String downloaded = Formatter.formatShortFileSize(this, update.getFile().length());
            String total = Formatter.formatShortFileSize(this, update.getFileSize());
            String percentage =
                    NumberFormat.getPercentInstance().format(update.getProgress() / 100.f);
            mProgressPercent.setText(percentage);
            mProgressText.setText(
                    getString(R.string.list_download_progress_newer, downloaded, total));
            mUpdateStatus.setText(R.string.system_update_downloading);
            mUpdateIcon.setImageResource(R.drawable.ic_system_update);
            setupButtonAction(Action.PAUSE, mPrimaryActionButton, true);
            mWarnMeteredConnectionCard.setVisibility(View.VISIBLE);
            mProgressBar.setIndeterminate(update.getStatus() == UpdateStatus.STARTING);
            mProgressBar.setProgress(update.getProgress());
        } else if (update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED) {
            mUpdateStatus.setText(R.string.system_update_suspended);
            mUpdateIcon.setImageResource(R.drawable.ic_system_update);
            setupButtonAction(Action.RESUME, mPrimaryActionButton, true);
            mChangelogSection.setVisibility(View.GONE);
            mWarnMeteredConnectionCard.setVisibility(View.GONE);
            showProgress = false;
        } else if (mUpdaterController.isInstallingUpdate(downloadId)) {
            mUpdateStatus.setText(R.string.system_update_installing);
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            if (Update.LOCAL_ID.equals(update.getDownloadId())) {
                mChangelogSection.setVisibility(View.GONE);
                mWarnMeteredConnectionCard.setVisibility(View.GONE);
                mUpdateStatus.setText(R.string.local_update_installing);
            }
            mProgressText.setText(
                    notAB
                            ? R.string.dialog_prepare_zip_message
                            : update.getFinalizing()
                                    ? R.string.finalizing_package
                                    : R.string.preparing_ota_first_boot);
            mProgressPercent.setText(
                    NumberFormat.getPercentInstance().format(update.getInstallProgress() / 100.f));
            mProgressBar.setIndeterminate(false);
            mProgressBar.setProgress(update.getInstallProgress());
            setupButtonAction(Action.HIDE_BUTTON, mPrimaryActionButton, true);
        } else if (mUpdaterController.isVerifyingUpdate(downloadId)) {
            setupButtonAction(Action.HIDE_BUTTON, mPrimaryActionButton, false);
            mUpdateStatus.setText(R.string.system_update_verifying);
            mProgressText.setText(R.string.list_verifying_update);
            mProgressBar.setIndeterminate(true);
        } else {
            setupButtonAction(Action.RESUME, mPrimaryActionButton, !isBusy());
            String downloaded = Formatter.formatShortFileSize(this, update.getFile().length());
            String total = Formatter.formatShortFileSize(this, update.getFileSize());
            String percentage =
                    NumberFormat.getPercentInstance().format(update.getProgress() / 100.f);
            mUpdateIcon.setImageResource(R.drawable.ic_system_update);
            mWarnMeteredConnectionCard.setVisibility(View.VISIBLE);
            mProgressPercent.setText(percentage);
            mProgressText.setText(
                    getString(R.string.list_download_progress_newer, downloaded, total));
            mProgressBar.setIndeterminate(false);
            mProgressBar.setProgress(update.getProgress());
            mUpdateStatus.setText(R.string.system_update_downloading_paused);
        }

        setupButtonAction(Action.CANCEL_INSTALLATION, mSecondaryActionButton, true);

        mProgress.setVisibility(
            showProgress
                ? View.VISIBLE
                : View.GONE);
        mSwipeRefresh.setEnabled(false);
    }

    private void handleNotActiveStatus(UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        boolean showCancelButton = true;
        if (mUpdaterController.isWaitingForReboot(downloadId)) {
            showCancelButton = false;
            mUpdateIcon.setImageResource(R.drawable.ic_system_update_success);
            mUpdateStatus.setText(R.string.installing_update_finished);
            mChangelogSection.setVisibility(View.GONE);
            setupButtonAction(Action.REBOOT, mPrimaryActionButton, true);
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            if (Utils.canInstall(update)) {
                setupButtonAction(Action.INSTALL, mPrimaryActionButton, !isBusy());
            } else {
                setupButtonAction(Action.HIDE_BUTTON, mPrimaryActionButton, false);
            }
        } else if (!Update.LOCAL_ID.equals(downloadId)) {
            showCancelButton = false;
            mUpdateStatus.setText(R.string.system_update_available);
            mCurrentBuildInfo.setVisibility(View.GONE);
            setChangelogs(mChangelogSection);
            mWarnMeteredConnectionCard.setVisibility(View.VISIBLE);
            setupButtonAction(Action.DOWNLOAD, mPrimaryActionButton, !isBusy());
        } else {
            showCancelButton = false;
        }

        setupButtonAction(
                showCancelButton ? Action.CANCEL_INSTALLATION : Action.HIDE_BUTTON,
                mSecondaryActionButton,
                true);

        mProgress.setVisibility(View.GONE);
    }

    private boolean isBusy() {
        return mUpdaterController.hasActiveDownloads()
                || mUpdaterController.isVerifyingUpdate()
                || mUpdaterController.isInstallingUpdate();
    }

    private void showUpdateInfo(int stringId) {
        mUpdateInfoWarning.setVisibility(View.VISIBLE);
        mUpdateInfoWarning.setText(stringId);
    }

    private MaterialAlertDialogBuilder getInstallDialog(final String downloadId) {
        if (!isBatteryLevelOk()) {
            Resources resources = getResources();
            String message =
                    resources.getString(
                            R.string.dialog_battery_low_message_pct,
                            resources.getInteger(R.integer.battery_ok_percentage_discharging),
                            resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        if (isScratchMounted()) {
            return new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_scratch_mounted_title)
                    .setMessage(R.string.dialog_scratch_mounted_message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        int resId;
        try {
            if (Utils.isABUpdate(update.getFile())) {
                resId = R.string.apply_update_dialog_message_ab;
            } else {
                resId = R.string.apply_update_dialog_message;
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not determine the type of the update");
            return null;
        }

        String buildDate =
                StringGenerator.getDateLocalizedUTC(this, DateFormat.MEDIUM, update.getTimestamp());
        String buildInfoText =
                getString(
                        R.string.list_build_version_date,
                        BuildInfoUtils.getBrand(),
                        update.getVersion(),
                        buildDate);
        return new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(getString(resId, buildInfoText, getString(android.R.string.ok)))
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> Utils.triggerUpdate(this, downloadId))
                .setNegativeButton(android.R.string.cancel, null);
    }

    private MaterialAlertDialogBuilder getRebootInstallationDialog() {
        return new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.reboot_installation_dialog_message)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            PowerManager pm = getSystemService(PowerManager.class);
                            pm.reboot(null);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private MaterialAlertDialogBuilder getCancelInstallationDialog(final String downloadId) {
        return new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.cancel_installation_dialog_message)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            Intent intent = new Intent(this, UpdaterService.class);
                            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                            startService(intent);
                            mUpdaterController.pauseDownload(downloadId);
                            mUpdaterController.deleteUpdate(downloadId);
                            mLatestDownloadId = null;
                            updateUI(null);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private boolean isBatteryLevelOk() {
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent =
                Math.round(
                        100.f
                                * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                                / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required =
                (plugged & BATTERY_PLUGGED_ANY) != 0
                        ? getResources().getInteger(R.integer.battery_ok_percentage_charging)
                        : getResources().getInteger(R.integer.battery_ok_percentage_discharging);
        return percent >= required;
    }

    private enum Action {
        CHECK_UPDATES,
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        CANCEL_INSTALLATION,
        REBOOT,
        HIDE_BUTTON,
    }
}

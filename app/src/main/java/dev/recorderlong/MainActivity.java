package dev.recorderlong;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 100;

    private TextView statusText;
    private TextView pathText;
    private TextView permissionText;
    private Button startButton;
    private Button stopButton;
    private EditText autoStopMinutesInput;
    private CheckBox silentNotificationBox;
    private CheckBox dndBox;
    private CheckBox rejectCallsBox;
    private SharedPreferences preferences;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatus(
                    intent.getStringExtra(RecordingService.EXTRA_STATUS),
                    intent.getStringExtra(RecordingService.EXTRA_PATH),
                    intent.getBooleanExtra(RecordingService.EXTRA_RECORDING, false)
            );
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(RecordingService.PREFS, MODE_PRIVATE);
        setContentView(createView());
        refreshLastStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(RecordingService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        refreshSettingControls();
        refreshLastStatus();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(statusReceiver);
        super.onStop();
    }

    private View createView() {
        int pad = dp(20);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setBackgroundColor(0xff121212);

        TextView title = text("RecorderLong", 24, 0xffffffff);
        title.setGravity(Gravity.CENTER);
        content.addView(title, fullWidth());

        statusText = text("", 18, 0xfff2f2f2);
        statusText.setPadding(0, dp(24), 0, dp(8));
        content.addView(statusText, fullWidth());

        pathText = text("", 14, 0xffc7c7c7);
        pathText.setPadding(0, 0, 0, dp(18));
        content.addView(pathText, fullWidth());

        permissionText = text("", 14, 0xffa7d7d2);
        permissionText.setPadding(0, 0, 0, dp(18));
        content.addView(permissionText, fullWidth());

        startButton = button("Start");
        startButton.setOnClickListener(view -> startRecording());
        content.addView(startButton, fullWidth());

        stopButton = button("Stop");
        stopButton.setOnClickListener(view -> stopRecording());
        content.addView(stopButton, fullWidth());

        TextView settingsTitle = text("Settings", 18, 0xffffffff);
        settingsTitle.setPadding(0, dp(22), 0, dp(4));
        content.addView(settingsTitle, fullWidth());

        TextView autoStopLabel = text("Auto stop minutes (default 6 hours)", 14, 0xfff2f2f2);
        content.addView(autoStopLabel, fullWidth());

        autoStopMinutesInput = new EditText(this);
        autoStopMinutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        autoStopMinutesInput.setSingleLine(true);
        autoStopMinutesInput.setSelectAllOnFocus(true);
        autoStopMinutesInput.setTextColor(0xfff2f2f2);
        autoStopMinutesInput.setHintTextColor(0xff8a8a8a);
        autoStopMinutesInput.setText(String.valueOf(getSavedAutoStopMinutes()));
        autoStopMinutesInput.setHint(String.valueOf(RecordingService.DEFAULT_AUTO_STOP_MINUTES));
        content.addView(autoStopMinutesInput, fullWidth());

        Button autoStopButton = button("Apply auto stop");
        autoStopButton.setOnClickListener(view -> {
            int minutes = saveAutoStopMinutesFromInput();
            Toast.makeText(this, "Auto stop after " + minutes + " min", Toast.LENGTH_SHORT).show();
        });
        content.addView(autoStopButton, fullWidth());

        silentNotificationBox = checkbox("Silent recording notification");
        silentNotificationBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(RecordingService.KEY_SILENT_NOTIFICATION, isChecked).apply());
        content.addView(silentNotificationBox, fullWidth());

        dndBox = checkbox("Silence calls while recording");
        dndBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(RecordingService.KEY_DND_WHILE_RECORDING, isChecked).apply());
        content.addView(dndBox, fullWidth());

        rejectCallsBox = checkbox("Reject calls while recording");
        rejectCallsBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(RecordingService.KEY_REJECT_CALLS_WHILE_RECORDING, isChecked).apply());
        content.addView(rejectCallsBox, fullWidth());

        Button callScreeningButton = button("Call screening role");
        callScreeningButton.setOnClickListener(view -> openCallScreeningSettings());
        content.addView(callScreeningButton, fullWidth());

        Button dndSettingsButton = button("DND access");
        dndSettingsButton.setOnClickListener(view -> openDndSettings());
        content.addView(dndSettingsButton, fullWidth());

        Button notificationSettingsButton = button("Notification settings");
        notificationSettingsButton.setOnClickListener(view -> openNotificationSettings());
        content.addView(notificationSettingsButton, fullWidth());

        Button permissionSettingsButton = button("App permission settings");
        permissionSettingsButton.setOnClickListener(view -> openAppSettings());
        content.addView(permissionSettingsButton, fullWidth());

        Button batteryButton = button("Battery background access");
        batteryButton.setOnClickListener(view -> openBatterySettings());
        content.addView(batteryButton, fullWidth());

        updatePermissionText();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xff121212);
        scrollView.addView(content);
        return scrollView;
    }

    private TextView text(String value, int size, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(size);
        textView.setTextColor(color);
        return textView;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private CheckBox checkbox(String value) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(value);
        checkBox.setTextColor(0xfff2f2f2);
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff80cbc4));
        return checkBox;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(6), 0, dp(6));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startRecording() {
        saveAutoStopMinutesFromInput();
        if (!hasRequiredPermissions()) {
            requestNeededPermissions();
            return;
        }

        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopRecording() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_STOP);
        startService(intent);
    }

    private boolean hasRequiredPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.P
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && hasRequiredPermissions()) {
            startRecording();
        }
        updatePermissionText();
    }

    private void refreshSettingControls() {
        if (autoStopMinutesInput != null) {
            autoStopMinutesInput.setText(String.valueOf(getSavedAutoStopMinutes()));
        }
        silentNotificationBox.setChecked(preferences.getBoolean(RecordingService.KEY_SILENT_NOTIFICATION, false));
        dndBox.setChecked(preferences.getBoolean(RecordingService.KEY_DND_WHILE_RECORDING, false));
        rejectCallsBox.setChecked(preferences.getBoolean(RecordingService.KEY_REJECT_CALLS_WHILE_RECORDING, false));
        updatePermissionText();
    }

    private int getSavedAutoStopMinutes() {
        int minutes = preferences.getInt(
                RecordingService.KEY_AUTO_STOP_MINUTES,
                RecordingService.DEFAULT_AUTO_STOP_MINUTES
        );
        return clampAutoStopMinutes(minutes);
    }

    private int saveAutoStopMinutesFromInput() {
        int minutes = RecordingService.DEFAULT_AUTO_STOP_MINUTES;
        if (autoStopMinutesInput != null) {
            String raw = autoStopMinutesInput.getText().toString().trim();
            if (!raw.isEmpty()) {
                try {
                    minutes = Integer.parseInt(raw);
                } catch (NumberFormatException ignored) {
                    minutes = RecordingService.DEFAULT_AUTO_STOP_MINUTES;
                }
            }
        }
        minutes = clampAutoStopMinutes(minutes);
        preferences.edit().putInt(RecordingService.KEY_AUTO_STOP_MINUTES, minutes).apply();
        if (autoStopMinutesInput != null) {
            autoStopMinutesInput.setText(String.valueOf(minutes));
        }
        return minutes;
    }

    private int clampAutoStopMinutes(int minutes) {
        return Math.max(
                RecordingService.MIN_AUTO_STOP_MINUTES,
                Math.min(RecordingService.MAX_AUTO_STOP_MINUTES, minutes)
        );
    }

    private void refreshLastStatus() {
        boolean lastRecording = preferences.getBoolean(RecordingService.KEY_LAST_RECORDING, false);
        boolean requested = preferences.getBoolean(RecordingService.KEY_RECORDING_REQUESTED, false);
        String fallbackStatus = requested ? "Recording in background" : "Idle";
        String status = preferences.getString(RecordingService.KEY_LAST_STATUS, fallbackStatus);
        String path = preferences.getString(RecordingService.KEY_LAST_PATH, "Download/RecorderLong");
        if (status == null || status.isEmpty()) {
            status = fallbackStatus;
        }
        if (path == null || path.isEmpty()) {
            path = "Download/RecorderLong";
        }
        updateStatus(status, path, lastRecording || requested);
    }

    private void openDndSettings() {
        startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
    }

    private void openNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", getPackageName(), null));
        }
        startActivity(intent);
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", getPackageName(), null)));
    }

    private void openCallScreeningSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = getSystemService(RoleManager.class);
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING));
                return;
            }
        }

        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", getPackageName(), null)));
        }
    }

    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void updateStatus(String status, String path, boolean recording) {
        statusText.setText(status == null || status.isEmpty() ? "Idle" : status);
        pathText.setText(path == null || path.isEmpty() ? "Download/RecorderLong" : path);
        startButton.setEnabled(!recording);
        stopButton.setEnabled(recording);
    }

    private void updatePermissionText() {
        if (permissionText == null) {
            return;
        }

        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add("microphone");
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing.add("storage");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add("notification optional");
        }

        if (missing.isEmpty()) {
            permissionText.setText("Permissions ready. Files save to Download/RecorderLong.");
        } else {
            permissionText.setText("Missing: " + String.join(", ", missing) + ". Start will request needed access.");
        }
    }
}

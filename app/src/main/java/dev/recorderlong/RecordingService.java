package dev.recorderlong;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingService extends Service {
    public static final String ACTION_START = "dev.recorderlong.START";
    public static final String ACTION_STOP = "dev.recorderlong.STOP";
    public static final String ACTION_STATUS = "dev.recorderlong.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_RECORDING = "recording";

    public static final String PREFS = "recorderlong_settings";
    public static final String KEY_SILENT_NOTIFICATION = "silent_notification";
    public static final String KEY_DND_WHILE_RECORDING = "dnd_while_recording";
    public static final String KEY_REJECT_CALLS_WHILE_RECORDING = "reject_calls_while_recording";
    public static final String KEY_RECORDING_REQUESTED = "recording_requested";
    public static final String KEY_LAST_STATUS = "last_status";
    public static final String KEY_LAST_PATH = "last_path";
    public static final String KEY_LAST_RECORDING = "last_recording";
    public static final String KEY_AUTO_STOP_MINUTES = "auto_stop_minutes";
    public static final int DEFAULT_AUTO_STOP_MINUTES = 6 * 60;
    public static final int MIN_AUTO_STOP_MINUTES = 1;
    public static final int MAX_AUTO_STOP_MINUTES = 1440;

    private static final String CHANNEL_ID = "recording";
    private static final String SILENT_CHANNEL_ID = "recording_silent";
    private static final String DOWNLOAD_ROOT = Environment.DIRECTORY_DOWNLOADS + "/RecorderLong";
    private static final int NOTIFICATION_ID = 10;
    private static final long SEGMENT_MS = 60L * 1000L;
    private static final int SEGMENTS_PER_HOUR_FILE = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateRunnable = this::rotateSegment;
    private final List<OutputTarget> completedSegments = new ArrayList<>();

    private MediaRecorder recorder;
    private OutputTarget currentTarget;
    private PowerManager.WakeLock wakeLock;
    private String sessionName;
    private String sessionPath;
    private int partIndex;
    private int previousInterruptionFilter = -1;
    private long startedAtElapsed;
    private boolean recording;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            setRecordingRequested(false);
            stopSession("Stopped", true);
            return START_NOT_STICKY;
        }

        boolean shouldStart = ACTION_START.equals(action) || (action == null && isRecordingRequested());
        if (shouldStart && !recording) {
            setRecordingRequested(true);
            startForegroundCompat(buildNotification("Starting", DOWNLOAD_ROOT));
            startSession();
        } else if (recording) {
            sendStatus("Recording part " + partIndex, currentPath(), true);
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (recording) {
            startForegroundCompat(buildNotification("Recording continues", currentTarget == null ? "" : currentTarget.name));
            sendStatus("Recording in background", currentPath(), true);
        } else {
            super.onTaskRemoved(rootIntent);
        }
    }

    @Override
    public void onDestroy() {
        if (recording || recorder != null) {
            rememberFinishedSegment(finishCurrentSegment());
            releaseWakeLock();
            restoreDndIfNeeded();
            recording = false;
        }
        super.onDestroy();
    }

    private void startSession() {
        startedAtElapsed = SystemClock.elapsedRealtime();
        partIndex = 0;
        completedSegments.clear();
        sessionName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        sessionPath = DOWNLOAD_ROOT + "/session-" + sessionName;

        acquireWakeLock();
        applyDndIfEnabled();
        startNextSegment();
    }

    private void startNextSegment() {
        int autoStopMinutes = getAutoStopMinutes();
        if (SystemClock.elapsedRealtime() - startedAtElapsed >= RecordingPolicy.minutesToMillis(autoStopMinutes)) {
            stopSession("Finished " + autoStopMinutes + " minutes", true);
            return;
        }

        partIndex++;
        String fileName = String.format(Locale.US, "rec_%s_part%03d.m4a", sessionName, partIndex);

        try {
            currentTarget = createOutputTarget(fileName);
            recorder = createRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            currentTarget.applyTo(recorder);
            recorder.prepare();
            recorder.start();
            recording = true;
            handler.postDelayed(rotateRunnable, SEGMENT_MS);

            String status = "Recording part " + partIndex;
            startForegroundCompat(buildNotification(status, currentTarget.name));
            sendStatus(status, currentTarget.displayPath, true);
        } catch (IOException | RuntimeException e) {
            discardCurrentTarget();
            releaseRecorderOnly();
            sendStatus("Record failed: " + e.getMessage(), sessionPath, false);
            stopSession("Record failed", true);
        }
    }

    private void rotateSegment() {
        if (!recording) {
            return;
        }
        rememberFinishedSegment(finishCurrentSegment());
        startNextSegment();
    }

    private OutputTarget finishCurrentSegment() {
        handler.removeCallbacks(rotateRunnable);
        MediaRecorder active = recorder;
        OutputTarget target = currentTarget;
        recorder = null;
        currentTarget = null;

        boolean success = false;
        if (active != null) {
            try {
                active.stop();
                success = true;
            } catch (RuntimeException ignored) {
                success = false;
            } finally {
                active.reset();
                active.release();
            }
        }

        if (target != null) {
            if (success) {
                target.finish(this);
                return target;
            } else {
                target.discard(this);
            }
        }
        return null;
    }

    private void discardCurrentTarget() {
        OutputTarget target = currentTarget;
        currentTarget = null;
        if (target != null) {
            target.discard(this);
        }
    }

    private void releaseRecorderOnly() {
        handler.removeCallbacks(rotateRunnable);
        if (recorder != null) {
            try {
                recorder.reset();
            } catch (RuntimeException ignored) {
            }
            recorder.release();
            recorder = null;
        }
    }

    @SuppressWarnings("deprecation")
    private MediaRecorder createRecorder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new MediaRecorder(this);
        }
        return new MediaRecorder();
    }

    private OutputTarget createOutputTarget(String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, sessionPath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Cannot create Downloads item");
            }
            ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "w");
            if (fd == null) {
                getContentResolver().delete(uri, null, null);
                throw new IOException("Cannot open Downloads item");
            }
            return OutputTarget.forUri(uri, fd, fileName, sessionPath + "/" + fileName);
        }

        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File sessionDir = new File(downloads, "RecorderLong/session-" + sessionName);
        if (!sessionDir.mkdirs() && !sessionDir.isDirectory()) {
            throw new IOException("Cannot create " + sessionDir.getAbsolutePath());
        }
        File file = new File(sessionDir, fileName);
        return OutputTarget.forFile(file, fileName);
    }

    private void stopSession(String reason, boolean clearRequested) {
        if (clearRequested) {
            setRecordingRequested(false);
        }

        if (!recording && recorder == null) {
            restoreDndIfNeeded();
            createHourlyFilesIfNeeded();
            releaseWakeLock();
            sendStatus(reason, sessionPath == null ? DOWNLOAD_ROOT : sessionPath, false);
            stopForegroundCompat();
            stopSelf();
            return;
        }

        rememberFinishedSegment(finishCurrentSegment());
        recording = false;
        restoreDndIfNeeded();
        createHourlyFilesIfNeeded();
        releaseWakeLock();
        sendStatus(reason, sessionPath == null ? DOWNLOAD_ROOT : sessionPath, false);
        stopForegroundCompat();
        stopSelf();
    }

    private void rememberFinishedSegment(OutputTarget target) {
        if (target != null) {
            completedSegments.add(target);
        }
    }

    private void createHourlyFilesIfNeeded() {
        if (completedSegments.isEmpty() || sessionName == null) {
            return;
        }

        int totalHours = (completedSegments.size() + SEGMENTS_PER_HOUR_FILE - 1) / SEGMENTS_PER_HOUR_FILE;
        for (int hour = 0; hour < totalHours; hour++) {
            int from = hour * SEGMENTS_PER_HOUR_FILE;
            int to = Math.min(completedSegments.size(), from + SEGMENTS_PER_HOUR_FILE);
            List<OutputTarget> group = completedSegments.subList(from, to);
            sendStatus(
                    "Creating hour file " + (hour + 1) + "/" + totalHours,
                    sessionPath == null ? DOWNLOAD_ROOT : sessionPath,
                    false
            );
            createHourlyFile(group, hour + 1);
        }
        completedSegments.clear();
    }

    private void createHourlyFile(List<OutputTarget> sources, int hourNumber) {
        if (sources.isEmpty()) {
            return;
        }

        OutputTarget output = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean outputFinished = false;
        try {
            String fileName = String.format(Locale.US, "hour_%s_h%02d.m4a", sessionName, hourNumber);
            output = createOutputTarget(fileName);
            muxer = output.createMuxer();

            int outputTrackIndex = -1;
            long nextPresentationTimeUs = 0L;
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            for (OutputTarget source : sources) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    source.setExtractorDataSource(this, extractor);
                    int inputTrackIndex = selectAudioTrack(extractor);
                    if (inputTrackIndex < 0) {
                        continue;
                    }
                    extractor.selectTrack(inputTrackIndex);
                    MediaFormat format = extractor.getTrackFormat(inputTrackIndex);
                    if (outputTrackIndex < 0) {
                        outputTrackIndex = muxer.addTrack(format);
                        muxer.start();
                        muxerStarted = true;
                    }

                    long lastSampleTimeUs = 0L;
                    boolean wroteSample = false;
                    while (true) {
                        buffer.clear();
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            break;
                        }

                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleTimeUs < 0) {
                            sampleTimeUs = lastSampleTimeUs;
                        }
                        int extractorFlags = extractor.getSampleFlags();
                        int codecFlags = 0;
                        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            codecFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                        }
                        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                            codecFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                        }
                        bufferInfo.set(
                                0,
                                sampleSize,
                                nextPresentationTimeUs + sampleTimeUs,
                                codecFlags
                        );
                        muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo);
                        lastSampleTimeUs = sampleTimeUs;
                        wroteSample = true;
                        extractor.advance();
                    }

                    if (wroteSample) {
                        nextPresentationTimeUs += segmentDurationUs(format, lastSampleTimeUs);
                    }
                } finally {
                    extractor.release();
                }
            }

            if (!muxerStarted) {
                throw new IOException("No audio samples to concatenate");
            }

            muxer.stop();
            muxer.release();
            muxer = null;
            output.finish(this);
            outputFinished = true;
            sendStatus("Created hour file " + hourNumber, output.displayPath, false);
        } catch (IOException | RuntimeException e) {
            sendStatus("Hour concat failed: " + e.getMessage(), sessionPath == null ? DOWNLOAD_ROOT : sessionPath, false);
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                }
            }
            if (output != null && !outputFinished) {
                output.discard(this);
            }
        }
    }

    private int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private long segmentDurationUs(MediaFormat format, long lastSampleTimeUs) {
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            long durationUs = format.getLong(MediaFormat.KEY_DURATION);
            if (durationUs > 0L) {
                return durationUs;
            }
        }
        return lastSampleTimeUs + 50_000L;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecorderLong:recording");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(RecordingPolicy.minutesToMillis(getAutoStopMinutes()) + SEGMENT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void applyDndIfEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !settings().getBoolean(KEY_DND_WHILE_RECORDING, false)) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.isNotificationPolicyAccessGranted()) {
            return;
        }
        previousInterruptionFilter = manager.getCurrentInterruptionFilter();
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
    }

    private void restoreDndIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || previousInterruptionFilter < 0) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.isNotificationPolicyAccessGranted()) {
            restoreInterruptionFilter(manager, previousInterruptionFilter);
        }
        previousInterruptionFilter = -1;
    }

    private void restoreInterruptionFilter(NotificationManager manager, int filter) {
        switch (filter) {
            case NotificationManager.INTERRUPTION_FILTER_NONE:
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
                break;
            case NotificationManager.INTERRUPTION_FILTER_PRIORITY:
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                break;
            case NotificationManager.INTERRUPTION_FILTER_ALARMS:
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
                break;
            case NotificationManager.INTERRUPTION_FILTER_ALL:
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                break;
            default:
                break;
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String title, String detail) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        boolean silent = settings().getBoolean(KEY_SILENT_NOTIFICATION, false);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, silent ? SILENT_CHANNEL_ID : CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(R.drawable.ic_mic_24)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setLocalOnly(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (silent) {
            builder.setPriority(Notification.PRIORITY_MIN);
            builder.setSound(null);
            builder.setVibrate(null);
        } else {
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        return builder.build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel normal = new NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(normal);

        NotificationChannel silent = new NotificationChannel(
                SILENT_CHANNEL_ID,
                "Recording silent",
                NotificationManager.IMPORTANCE_MIN
        );
        silent.setSound(null, null);
        silent.enableVibration(false);
        silent.setShowBadge(false);
        manager.createNotificationChannel(silent);
    }

    private SharedPreferences settings() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean isRecordingRequested() {
        return settings().getBoolean(KEY_RECORDING_REQUESTED, false);
    }

    private void setRecordingRequested(boolean requested) {
        settings().edit().putBoolean(KEY_RECORDING_REQUESTED, requested).apply();
    }

    private int getAutoStopMinutes() {
        int minutes = settings().getInt(KEY_AUTO_STOP_MINUTES, DEFAULT_AUTO_STOP_MINUTES);
        return RecordingPolicy.clampAutoStopMinutes(minutes);
    }

    private String currentPath() {
        return currentTarget == null ? (sessionPath == null ? DOWNLOAD_ROOT : sessionPath) : currentTarget.displayPath;
    }

    private void sendStatus(String status, String path, boolean isRecording) {
        settings().edit()
                .putString(KEY_LAST_STATUS, status == null ? "" : status)
                .putString(KEY_LAST_PATH, path == null ? "" : path)
                .putBoolean(KEY_LAST_RECORDING, isRecording)
                .apply();

        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_PATH, path);
        intent.putExtra(EXTRA_RECORDING, isRecording);
        sendBroadcast(intent);
    }

    private static final class OutputTarget {
        private final Uri uri;
        private final ParcelFileDescriptor descriptor;
        private final File file;
        private final String name;
        private final String displayPath;

        private OutputTarget(Uri uri, ParcelFileDescriptor descriptor, File file, String name, String displayPath) {
            this.uri = uri;
            this.descriptor = descriptor;
            this.file = file;
            this.name = name;
            this.displayPath = displayPath;
        }

        static OutputTarget forUri(Uri uri, ParcelFileDescriptor descriptor, String name, String displayPath) {
            return new OutputTarget(uri, descriptor, null, name, displayPath);
        }

        static OutputTarget forFile(File file, String name) {
            return new OutputTarget(null, null, file, name, file.getAbsolutePath());
        }

        void applyTo(MediaRecorder recorder) {
            if (descriptor != null) {
                FileDescriptor fd = descriptor.getFileDescriptor();
                recorder.setOutputFile(fd);
            } else {
                recorder.setOutputFile(file.getAbsolutePath());
            }
        }

        MediaMuxer createMuxer() throws IOException {
            if (descriptor != null) {
                return new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
            return new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        void setExtractorDataSource(Context context, MediaExtractor extractor) throws IOException {
            if (uri != null) {
                extractor.setDataSource(context, uri, null);
            } else {
                extractor.setDataSource(file.getAbsolutePath());
            }
        }

        void finish(Context context) {
            closeDescriptor();
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            }
            if (file != null) {
                MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, new String[]{"audio/mp4"}, null);
            }
        }

        void discard(Context context) {
            closeDescriptor();
            ContentResolver resolver = context.getContentResolver();
            if (uri != null) {
                resolver.delete(uri, null, null);
            }
            if (file != null && file.length() == 0L) {
                file.delete();
            }
        }

        private void closeDescriptor() {
            if (descriptor == null) {
                return;
            }
            try {
                descriptor.close();
            } catch (IOException ignored) {
            }
        }
    }
}

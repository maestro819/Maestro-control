package com.maestro.devicecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Foreground service perekam suara TRANSPARAN.
 *  - Merekam maksimal 30 detik lalu berhenti otomatis.
 *  - Selama merekam, Android WAJIB menampilkan notifikasi "mikrofon aktif" + indikator
 *    mikrofon di layar. Tidak ada mode senyap; ini memang disengaja.
 *  - Hasil rekaman diunggah ke Supabase Storage (bucket device-clips).
 */
public class MicService extends Service {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_DEVICE = "device";
    public static final String EXTRA_COMMAND = "command";

    private static final String CHANNEL_ID = "maestro_mic";
    private static final int NOTIF_ID = 7302;
    private static final String BUCKET = "device-clips";
    private static final long MAX_MS = 30000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private File file;
    private String supabaseUrl, anonKey, token, userId, deviceId, commandId;
    private boolean finished = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        supabaseUrl = intent.getStringExtra(EXTRA_URL);
        anonKey = intent.getStringExtra(EXTRA_KEY);
        token = intent.getStringExtra(EXTRA_TOKEN);
        userId = intent.getStringExtra(EXTRA_USER);
        deviceId = intent.getStringExtra(EXTRA_DEVICE);
        commandId = intent.getStringExtra(EXTRA_COMMAND);

        startForegroundWithNotification();

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            finishWithStatus("denied");
            return START_NOT_STICKY;
        }

        try {
            file = new File(getCacheDir(), "clip_" + UUID.randomUUID() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            handler.postDelayed(this::stopAndUpload, MAX_MS);
        } catch (Exception e) {
            finishWithStatus("failed");
        }
        return START_NOT_STICKY;
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Maestro Microphone", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Ditampilkan saat mikrofon dipakai dari dashboard.");
            nm.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Maestro: mikrofon aktif")
                .setContentText("Merekam maksimal 30 detik, lalu berhenti otomatis.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notification);
    }

    private void stopAndUpload() {
        if (finished) return;
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
        } catch (Exception e) {
            finishWithStatus("failed");
            return;
        }
        uploadClip();
    }

    private void uploadClip() {
        new Thread(() -> {
            try {
                byte[] bytes = new byte[(int) file.length()];
                try (FileInputStream in = new FileInputStream(file)) {
                    int read = 0;
                    while (read < bytes.length) {
                        int r = in.read(bytes, read, bytes.length - read);
                        if (r < 0) break;
                        read += r;
                    }
                }
                String path = userId + "/" + deviceId + "/" + UUID.randomUUID() + ".m4a";
                URL url = new URL(supabaseUrl + "/storage/v1/object/" + BUCKET + "/" + path);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "audio/mp4");
                connection.setRequestProperty("apikey", anonKey);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("x-upsert", "true");
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                int code = connection.getResponseCode();
                if (code >= 300) { finishWithStatus("failed"); return; }

                JSONObject result = new JSONObject();
                result.put("bucket", BUCKET);
                result.put("path", path);
                updateCommandStatus("completed", result);
            } catch (Exception e) {
                finishWithStatus("failed");
            } finally {
                if (file != null) file.delete();
            }
        }).start();
    }

    private void finishWithStatus(String status) {
        if (finished) return;
        finished = true;
        updateCommandStatus(status, null);
    }

    private void updateCommandStatus(String status, JSONObject result) {
        try {
            JSONObject body = new JSONObject();
            body.put("status", status);
            if ("completed".equals(status)) {
                body.put("completed_at", java.time.Instant.now().toString());
                if (result != null) body.put("result", result);
            }
            URL url = new URL(supabaseUrl + "/rest/v1/device_commands?id=eq." + commandId);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PATCH");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("apikey", anonKey);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Prefer", "return=minimal");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            releaseAndStop();
        }
    }

    private void releaseAndStop() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        releaseAndStop();
        super.onDestroy();
    }
}

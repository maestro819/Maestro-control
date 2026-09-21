package com.maestro.devicecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Size;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/**
 * Foreground service yang mengambil SATU foto saat dashboard meminta, lalu mengunggahnya
 * ke Supabase Storage.
 *
 * PENTING (transparansi): selama service ini berjalan, Android WAJIB menampilkan notifikasi
 * tetap ("Kamera aktif") dan indikator privasi kamera. Ini tidak bisa disembunyikan pada
 * aplikasi biasa — dan memang sengaja demikian, agar perangkat yang difoto selalu punya tanda.
 */
public class CameraService extends Service {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_DEVICE = "device";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_FACING = "facing";

    private static final String CHANNEL_ID = "maestro_camera";
    private static final int NOTIF_ID = 7301;
    private static final String BUCKET = "device-photos";

    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private ImageReader reader;
    private String supabaseUrl, anonKey, token, userId, deviceId, commandId, facing;
    private boolean finished = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        supabaseUrl = intent.getStringExtra(EXTRA_URL);
        anonKey = intent.getStringExtra(EXTRA_KEY);
        token = intent.getStringExtra(EXTRA_TOKEN);
        userId = intent.getStringExtra(EXTRA_USER);
        deviceId = intent.getStringExtra(EXTRA_DEVICE);
        commandId = intent.getStringExtra(EXTRA_COMMAND);
        facing = intent.getStringExtra(EXTRA_FACING) == null ? "back" : intent.getStringExtra(EXTRA_FACING);

        startForegroundWithNotification();

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            finishWithStatus("denied");
            return START_NOT_STICKY;
        }

        thread = new HandlerThread("maestro-camera");
        thread.start();
        bg = new Handler(thread.getLooper());
        openCamera();
        return START_NOT_STICKY;
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Maestro Camera", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Ditampilkan saat kamera dipakai dari dashboard.");
            nm.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Maestro: kamera aktif")
                .setContentText("Dashboard meminta satu foto. Kamera akan segera mati setelah selesai.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notification);
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String chosen = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer lens = c.get(CameraCharacteristics.LENS_FACING);
                boolean wantFront = "front".equalsIgnoreCase(facing);
                if (lens != null) {
                    if (wantFront && lens == CameraCharacteristics.LENS_FACING_FRONT) { chosen = id; break; }
                    if (!wantFront && lens == CameraCharacteristics.LENS_FACING_BACK) { chosen = id; break; }
                }
            }
            if (chosen == null && manager.getCameraIdList().length > 0) {
                chosen = manager.getCameraIdList()[0];
            }
            if (chosen == null) { finishWithStatus("failed"); return; }
            manager.openCamera(chosen, deviceCallback, bg);
        } catch (Exception e) {
            finishWithStatus("failed");
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) {
            camera = device;
            try {
                CameraCharacteristics c = ((CameraManager) getSystemService(Context.CAMERA_SERVICE))
                        .getCameraCharacteristics(device.getId());
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size size = chooseSize(map);
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
                reader.setOnImageAvailableListener(imageListener, bg);
                device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                        sessionCallback, bg);
            } catch (Exception e) {
                finishWithStatus("failed");
            }
        }
        @Override public void onDisconnected(CameraDevice device) { finishWithStatus("failed"); }
        @Override public void onError(CameraDevice device, int error) { finishWithStatus("failed"); }
    };

    private Size chooseSize(StreamConfigurationMap map) {
        Size best = null;
        for (Size s : map.getOutputSizes(ImageFormat.JPEG)) {
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        if (best == null) best = new Size(1280, 720);
        // Batasi ukuran agar unggahan cepat.
        if (best.getWidth() > 1920) best = new Size(1920, (best.getHeight() * 1920) / best.getWidth());
        return best;
    }

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession session) {
            try {
                CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                builder.addTarget(reader.getSurface());
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                session.capture(builder.build(), null, bg);
            } catch (Exception e) {
                finishWithStatus("failed");
            }
        }
        @Override public void onConfigureFailed(CameraCaptureSession session) { finishWithStatus("failed"); }
    };

    private final ImageReader.OnImageAvailableListener imageListener = new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                uploadPhoto(bytes);
            } catch (Exception e) {
                finishWithStatus("failed");
            } finally {
                image.close();
            }
        }
    };

    private void uploadPhoto(final byte[] jpeg) {
        new Thread(() -> {
            try {
                String path = userId + "/" + deviceId + "/" + UUID.randomUUID() + ".jpg";
                URL url = new URL(supabaseUrl + "/storage/v1/object/" + BUCKET + "/" + path);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "image/jpeg");
                connection.setRequestProperty("apikey", anonKey);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("x-upsert", "true");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(jpeg);
                }
                int code = connection.getResponseCode();
                if (code >= 300) {
                    String err = readStream(connection.getErrorStream());
                    android.util.Log.e("MaestroCamera", "upload HTTP " + code + " " + err);
                    finishWithStatus("failed");
                    return;
                }
                JSONObject result = new JSONObject();
                result.put("bucket", BUCKET);
                result.put("path", path);
                updateCommandStatus("completed", result);
            } catch (Exception e) {
                finishWithStatus("failed");
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
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        if (thread != null) thread.quitSafely();
        stopForeground(true);
        stopSelf();
    }

    private String readStream(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    @Override public void onDestroy() {
        releaseAndStop();
        super.onDestroy();
    }
}

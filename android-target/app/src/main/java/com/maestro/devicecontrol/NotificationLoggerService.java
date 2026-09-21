package com.maestro.devicecontrol;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Mencatat aktivitas notifikasi — HANYA metadata (nama aplikasi + waktu).
 *
 * Isi/tulisan notifikasi (isi pesan, judul chat, dsb.) TIDAK dibaca dan TIDAK disimpan.
 * Service ini hanya aktif setelah pengguna perangkat memberi izin "Akses notifikasi"
 * secara manual di Pengaturan Android — jadi bersifat transparan dan disetujui.
 */
public class NotificationLoggerService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        final String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;
        final long postedAt = sbn.getPostTime();

        final SharedPreferences prefs = getSharedPreferences("maestro", MODE_PRIVATE);
        final String base = prefs.getString("url", "").replaceAll("/$", "");
        final String key = prefs.getString("key", "");
        final String token = prefs.getString("token", "");
        final String deviceId = prefs.getString("internal_id", "");
        if (base.isEmpty() || key.isEmpty() || token.isEmpty() || deviceId.isEmpty()) return;

        String label;
        try {
            PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            label = pkg;
        }
        final String appLabel = label;

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_id", deviceId);
                body.put("package_name", pkg);
                body.put("app_label", appLabel);
                body.put("posted_at", Instant.ofEpochMilli(postedAt).toString());

                URL url = new URL(base + "/rest/v1/device_notifications");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("apikey", key);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Prefer", "return=minimal");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                connection.getResponseCode();
            } catch (Exception ignored) {
                // Metadata saja; kegagalan sesaat diabaikan.
            }
        }).start();
    }
}

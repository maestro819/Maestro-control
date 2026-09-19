package com.maestro.devicecontrol;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Target-side app.
 *
 * What this does:
 *  - Registers this device against the owner's Supabase project (upsert on device_token).
 *  - Reports the CURRENT OS permission status (granted/not granted) for location, camera,
 *    microphone, and notifications. It never turns the camera or microphone on by itself.
 *  - When the controller sends a "location" command, this app takes ONE last-known-location
 *    reading (only if location permission is already granted) and reports it back - similar
 *    to a "share my location" button in a family-locator app. There is no continuous/live
 *    tracking loop and no background service.
 *
 * Camera, microphone, and notification-mirroring commands are intentionally NOT implemented
 * here. Wiring those up to actually capture photo/video/audio or read notification content
 * would turn this into a remote surveillance tool, which is out of scope.
 */
public class MainActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 42;
    private static final long POLL_INTERVAL_MS = 15000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            syncDeviceStatus();
            pollPendingCommands();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private SharedPreferences prefs;
    private TextView status;
    private EditText urlInput;
    private EditText keyInput;
    private EditText deviceInput;
    private EditText tokenInput;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("maestro", MODE_PRIVATE);
        buildUi();
    }

    private void buildUi() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Maestro Target");
        title.setTextSize(28);
        box.addView(title);

        TextView note = new TextView(this);
        note.setText("Perangkat ini hanya melaporkan status izin dan lokasi saat diminta. Kamera dan mikrofon tidak pernah diaktifkan dari jarak jauh.");
        box.addView(note);

        urlInput = field("Supabase URL", prefs.getString("url", ""));
        keyInput = field("Supabase anon key", prefs.getString("key", ""));
        deviceInput = field("Device token (bebas, unik)", prefs.getString("device", UUID.randomUUID().toString()));
        tokenInput = field("User access token (dari web-controller)", prefs.getString("token", ""));
        box.addView(urlInput);
        box.addView(keyInput);
        box.addView(deviceInput);
        box.addView(tokenInput);

        Button save = new Button(this);
        save.setText("Simpan dan minta izin");
        save.setOnClickListener(view -> saveAndRequestPermissions());
        box.addView(save);

        status = new TextView(this);
        status.setText("Belum terhubung");
        box.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
    }

    private EditText field(String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(false);
        return input;
    }

    private void saveAndRequestPermissions() {
        prefs.edit()
                .putString("url", urlInput.getText().toString().trim())
                .putString("key", keyInput.getText().toString().trim())
                .putString("device", deviceInput.getText().toString().trim())
                .putString("token", tokenInput.getText().toString().trim())
                .apply();

        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            }, PERMISSIONS_REQUEST);
        } else {
            onPermissionsUpdated();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST) {
            onPermissionsUpdated();
        }
    }

    private void onPermissionsUpdated() {
        status.setText("Izin diperbarui. Menghubungkan ke Supabase...");
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean configReady() {
        return !prefs.getString("url", "").isEmpty()
                && !prefs.getString("key", "").isEmpty()
                && !prefs.getString("device", "").isEmpty()
                && !prefs.getString("token", "").isEmpty();
    }

    // ---- Device registration / heartbeat -------------------------------------------------

    private void syncDeviceStatus() {
        if (!configReady()) {
            handler.post(() -> status.setText("Isi Supabase URL, anon key, device token, dan access token."));
            return;
        }
        final String base = prefs.getString("url", "").replaceAll("/$", "");
        final String deviceToken = prefs.getString("device", "");
        final String key = prefs.getString("key", "");
        final String token = prefs.getString("token", "");

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("device_token", deviceToken);
                body.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
                body.put("is_online", true);
                body.put("location_permission",
                        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION));
                body.put("camera_permission", granted(Manifest.permission.CAMERA));
                body.put("microphone_permission", granted(Manifest.permission.RECORD_AUDIO));
                body.put("notification_permission",
                        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS));
                body.put("last_seen_at", Instant.now().toString());

                HttpURLConnection connection = (HttpURLConnection) new URL(
                        base + "/rest/v1/devices?on_conflict=device_token").openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("apikey", key);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Prefer", "resolution=merge-duplicates,return=representation");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();
                String raw = readStream(code < 300 ? connection.getInputStream() : connection.getErrorStream());

                if (code < 300) {
                    JSONArray rows = new JSONArray(raw);
                    if (rows.length() > 0) {
                        String internalId = rows.getJSONObject(0).getString("id");
                        prefs.edit().putString("internal_id", internalId).apply();
                    }
                    handler.post(() -> status.setText("Tersinkron ke Supabase. Menunggu perintah lokasi (jika ada)."));
                } else {
                    final String errRaw = raw;
                    handler.post(() -> status.setText("Gagal sinkron (HTTP " + code + "): " + errRaw));
                }
            } catch (Exception error) {
                handler.post(() -> status.setText("Sinkron gagal: " + error.getMessage()));
            }
        });
    }

    // ---- Commands: only "location" is executed, and only as a one-shot read --------------

    private void pollPendingCommands() {
        String internalId = prefs.getString("internal_id", "");
        if (internalId.isEmpty()) return; // not registered yet, syncDeviceStatus() runs first each cycle

        final String base = prefs.getString("url", "").replaceAll("/$", "");
        final String key = prefs.getString("key", "");
        final String token = prefs.getString("token", "");

        executor.execute(() -> {
            try {
                String endpoint = base + "/rest/v1/device_commands?device_id=eq."
                        + URLEncoder.encode(internalId, "UTF-8")
                        + "&status=eq.pending&command=eq.location&select=id&limit=1";
                HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestProperty("apikey", key);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                String raw = readStream(connection.getInputStream());
                JSONArray rows = new JSONArray(raw);
                if (rows.length() > 0) {
                    handleLocationCommand(rows.getJSONObject(0).getString("id"));
                }
            } catch (Exception ignored) {
                // Transient network errors here are fine; the next heartbeat cycle retries.
            }
        });
    }

    private void handleLocationCommand(String commandId) {
        boolean hasPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!hasPermission) {
            updateCommandStatus(commandId, "denied");
            return;
        }

        Location location = null;
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null) {
                location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (SecurityException ignored) {
            // permission was revoked between the check above and this call
        }

        if (location == null) {
            updateCommandStatus(commandId, "failed");
            handler.post(() -> status.setText("Lokasi terakhir belum tersedia di perangkat ini."));
            return;
        }

        updateDeviceLocation(location.getLatitude(), location.getLongitude());
        updateCommandStatus(commandId, "completed");
        handler.post(() -> status.setText("Lokasi dibagikan ke controller."));
    }

    private void updateDeviceLocation(double lat, double lng) {
        String internalId = prefs.getString("internal_id", "");
        final String base = prefs.getString("url", "").replaceAll("/$", "");
        final String key = prefs.getString("key", "");
        final String token = prefs.getString("token", "");

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("last_lat", lat);
                body.put("last_lng", lng);
                body.put("location_updated_at", Instant.now().toString());

                HttpURLConnection connection = (HttpURLConnection) new URL(
                        base + "/rest/v1/devices?id=eq." + URLEncoder.encode(internalId, "UTF-8")).openConnection();
                connection.setRequestMethod("PATCH");
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
            }
        });
    }

    private void updateCommandStatus(String commandId, String newStatus) {
        final String base = prefs.getString("url", "").replaceAll("/$", "");
        final String key = prefs.getString("key", "");
        final String token = prefs.getString("token", "");

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("status", newStatus);
                if ("completed".equals(newStatus)) {
                    body.put("completed_at", Instant.now().toString());
                }

                HttpURLConnection connection = (HttpURLConnection) new URL(
                        base + "/rest/v1/device_commands?id=eq." + URLEncoder.encode(commandId, "UTF-8")).openConnection();
                connection.setRequestMethod("PATCH");
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
            }
        });
    }

    private String readStream(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            result.append(line);
        }
        reader.close();
        return result.toString();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}

package com.maestro.devicecontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.app.NotificationManagerCompat;
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
 * Target-side app (semua fitur konsep aktif).
 *
 *  - Lokasi: satu pembacaan saat diminta.
 *  - Kamera: CameraService (foreground, notifikasi + indikator terlihat).
 *  - Mikrofon: MicService (foreground, notifikasi + indikator, maks 30 detik).
 *  - Notifikasi: NotificationLoggerService mencatat METADATA (aplikasi + waktu) saja.
 *  - Auto-reconnect: NetworkCallback + onResume.
 *
 * Semua akses sensor bersifat TRANSPARAN (ada tanda terlihat di perangkat) dan hanya jalan
 * setelah izin diberikan pengguna perangkat.
 */
public class MainActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 42;
    private static final long POLL_INTERVAL_MS = 8000;

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
    private EditText emailInput;
    private EditText passwordInput;
    private EditText tokenInput;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("maestro", MODE_PRIVATE);
        buildUi();
        registerNetworkCallback();
        if (configReady()) {
            handler.post(() -> {
                status.setText("Menghubungkan otomatis\u2026");
                restartHeartbeat();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (configReady()) restartHeartbeat();
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (configReady()) restartHeartbeat();
            }
            @Override public void onLost(Network network) {
                handler.post(() -> status.setText("Koneksi terputus. Akan menyambung otomatis saat online."));
            }
        };
        try { connectivityManager.registerNetworkCallback(request, networkCallback); } catch (Exception ignored) {}
    }

    private void restartHeartbeat() {
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
    }

    private boolean hasNetwork() {
        if (connectivityManager == null) return true;
        Network active = connectivityManager.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
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
        note.setText("Aplikasi ini melayani permintaan dashboard: lokasi, foto, rekaman suara, dan "
                + "ringkasan notifikasi. Setiap akses kamera/mikrofon menampilkan notifikasi + indikator "
                + "di layar ini. Saat jaringan kembali, perangkat menyambung otomatis.");
        box.addView(note);

        urlInput = field("Supabase URL", prefs.getString("url", ""));
        keyInput = field("Supabase anon key", prefs.getString("key", ""));
        deviceInput = field("Device token (unik, biarkan apa adanya)", prefs.getString("device", UUID.randomUUID().toString()));
        emailInput = field("Email akun Supabase", prefs.getString("email", ""));
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        passwordInput = field("Password (tidak disimpan)", "");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput = field("User access token (opsional bila pakai email/password)", prefs.getString("token", ""));
        box.addView(urlInput);
        box.addView(keyInput);
        box.addView(deviceInput);
        box.addView(emailInput);
        box.addView(passwordInput);
        box.addView(tokenInput);

        Button save = new Button(this);
        save.setText("Simpan dan minta izin");
        save.setOnClickListener(view -> saveAndRequestPermissions());
        box.addView(save);

        Button notifAccess = new Button(this);
        notifAccess.setText("Aktifkan akses notifikasi (buka Pengaturan)");
        notifAccess.setOnClickListener(view -> openNotificationAccessSettings());
        box.addView(notifAccess);

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

    private void openNotificationAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            handler.post(() -> status.setText("Buka Pengaturan → Notifikasi → Akses notifikasi untuk mengizinkan."));
        }
    }

    private void saveAndRequestPermissions() {
        final String newToken = tokenInput.getText().toString().trim();
        final boolean tokenChangedByHand = !newToken.equals(prefs.getString("token", ""));
        SharedPreferences.Editor editor = prefs.edit()
                .putString("url", urlInput.getText().toString().trim())
                .putString("key", keyInput.getText().toString().trim())
                .putString("device", deviceInput.getText().toString().trim())
                .putString("email", emailInput.getText().toString().trim())
                .putString("token", newToken);
        if (tokenChangedByHand) editor.remove("refresh").remove("expires_at");
        editor.apply();

        final String email = emailInput.getText().toString().trim();
        final String password = passwordInput.getText().toString();
        if (!email.isEmpty() && !password.isEmpty()) {
            status.setText("Masuk ke Supabase...");
            executor.execute(() -> {
                JSONObject body = new JSONObject();
                String error;
                try {
                    body.put("email", email);
                    body.put("password", password);
                    error = requestSession("password", body);
                } catch (Exception failure) {
                    error = failure.getMessage();
                }
                final String result = error;
                handler.post(() -> {
                    if (result == null) {
                        passwordInput.setText("");
                        requestRuntimePermissions();
                    } else {
                        status.setText("Gagal masuk: " + result);
                    }
                });
            });
        } else {
            requestRuntimePermissions();
        }
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            java.util.List<String> wanted = new java.util.ArrayList<>();
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
            wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            wanted.add(Manifest.permission.CAMERA);
            wanted.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(wanted.toArray(new String[0]), PERMISSIONS_REQUEST);
        } else {
            onPermissionsUpdated();
        }
    }

    // ---- Session handling -----------------------------------------------------------

    private String requestSession(String grantType, JSONObject body) {
        try {
            final String base = prefs.getString("url", "").replaceAll("/$", "");
            final String key = prefs.getString("key", "");
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    base + "/auth/v1/token?grant_type=" + grantType).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("apikey", key);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = connection.getResponseCode();
            String raw = readStream(code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code >= 300) {
                String message = raw;
                try {
                    JSONObject err = new JSONObject(raw);
                    message = err.optString("msg", err.optString("error_description", err.optString("message", raw)));
                } catch (Exception notJson) {
                }
                return "HTTP " + code + ": " + message;
            }

            JSONObject session = new JSONObject(raw);
            prefs.edit()
                    .putString("token", session.getString("access_token"))
                    .putString("refresh", session.optString("refresh_token", ""))
                    .putString("user_id", session.optJSONObject("user") != null ? session.getJSONObject("user").optString("id", "") : "")
                    .putLong("expires_at", System.currentTimeMillis() / 1000L + session.optLong("expires_in", 3600L))
                    .apply();
            return null;
        } catch (Exception error) {
            return error.getMessage();
        }
    }

    private synchronized String currentToken() {
        String token = prefs.getString("token", "");
        String refresh = prefs.getString("refresh", "");
        long expiresAt = prefs.getLong("expires_at", 0L);
        long now = System.currentTimeMillis() / 1000L;
        if (!refresh.isEmpty() && (token.isEmpty() || now >= expiresAt - 60)) {
            try {
                JSONObject body = new JSONObject();
                body.put("refresh_token", refresh);
                String error = requestSession("refresh_token", body);
                if (error != null) {
                    handler.post(() -> status.setText("Sesi habis, masuk ulang dengan email/password. (" + error + ")"));
                } else {
                    token = prefs.getString("token", token);
                }
            } catch (Exception ignored) {
            }
        }
        return token;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST) onPermissionsUpdated();
    }

    private void onPermissionsUpdated() {
        status.setText("Izin diperbarui. Menghubungkan ke Supabase...");
        restartHeartbeat();
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationAccessEnabled() {
        try {
            return NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean configReady() {
        return !prefs.getString("url", "").isEmpty()
                && !prefs.getString("key", "").isEmpty()
                && !prefs.getString("device", "").isEmpty()
                && (!prefs.getString("token", "").isEmpty() || !prefs.getString("refresh", "").isEmpty());
    }

    // ---- Device registration / heartbeat -------------------------------------------------

    private void syncDeviceStatus() {
        if (!configReady()) {
            handler.post(() -> status.setText("Isi Supabase URL, anon key, device token, lalu email + password (atau access token)."));
            return;
        }
        if (!hasNetwork()) {
            handler.post(() -> status.setText("Tidak ada koneksi. Menunggu jaringan kembali (akan menyambung otomatis)\u2026"));
            return;
        }
        final String base = prefs.getString("url", "").replaceAll("/$", "");
        final String deviceToken = prefs.getString("device", "");
        final String key = prefs.getString("key", "");
        executor.execute(() -> {
            final String token = currentToken();
            try {
                JSONObject body = new JSONObject();
                body.put("device_token", deviceToken);
                body.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
                body.put("is_online", true);
                body.put("location_permission",
                        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION));
                body.put("camera_permission", granted(Manifest.permission.CAMERA));
                body.put("microphone_permission", granted(Manifest.permission.RECORD_AUDIO));
                body.put("notification_permission", notificationAccessEnabled());
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
                    handler.post(() -> status.setText("Tersinkron ke Supabase. Siap menerima perintah dashboard."));
                } else {
                    final String errRaw = raw;
                    handler.post(() -> status.setText("Gagal sinkron (HTTP " + code + "): " + errRaw));
                }
            } catch (Exception error) {
                handler.post(() -> status.setText("Sinkron gagal: " + error.getMessage()));
            }
        });
    }

    // ---- Command dispatcher --------------------------------------------------------

    private void pollPendingCommands() {
        String internalId = prefs.getString("internal_id", "");
        if (internalId.isEmpty()) return;

        final String base = prefs.getString("url", "").replaceAll("/$", "");
        final String key = prefs.getString("key", "");
        executor.execute(() -> {
            final String token = currentToken();
            try {
                String endpoint = base + "/rest/v1/device_commands?device_id=eq."
                        + URLEncoder.encode(internalId, "UTF-8")
                        + "&status=eq.pending&command=in.(location,take_photo,microphone)&select=id,command,params&limit=1";
                HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestProperty("apikey", key);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                String raw = readStream(connection.getInputStream());
                JSONArray rows = new JSONArray(raw);
                if (rows.length() == 0) return;
                JSONObject row = rows.getJSONObject(0);
                String command = row.getString("command");
                String commandId = row.getString("id");
                if ("location".equals(command)) {
                    handleLocationCommand(commandId);
                } else if ("take_photo".equals(command)) {
                    handleTakePhotoCommand(commandId, row.optJSONObject("params"));
                } else if ("microphone".equals(command)) {
                    handleMicrophoneCommand(commandId);
                }
            } catch (Exception error) {
                final String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                handler.post(() -> status.setText("Gagal membaca command: " + message));
            }
        });
    }

    private void handleLocationCommand(String commandId) {
        boolean hasPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!hasPermission) { updateCommandStatus(commandId, "denied"); return; }

        Location location = null;
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null) location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException ignored) {
        }

        if (location == null) {
            updateCommandStatus(commandId, "failed");
            handler.post(() -> status.setText("Lokasi terakhir belum tersedia di perangkat ini."));
            return;
        }
        updateDeviceLocation(location.getLatitude(), location.getLongitude());
        updateCommandStatus(commandId, "completed");
        handler.post(() -> status.setText("Lokasi dibagikan ke dashboard."));
    }

    private void handleTakePhotoCommand(String commandId, JSONObject params) {
        if (!granted(Manifest.permission.CAMERA)) {
            updateCommandStatus(commandId, "denied");
            handler.post(() -> status.setText("Izin kamera belum diberikan; perintah foto ditolak."));
            return;
        }
        String facing = params != null ? params.optString("facing", "back") : "back";
        updateCommandStatus(commandId, "processing");

        Intent intent = new Intent(this, CameraService.class);
        intent.putExtra(CameraService.EXTRA_URL, prefs.getString("url", "").replaceAll("/$", ""));
        intent.putExtra(CameraService.EXTRA_KEY, prefs.getString("key", ""));
        intent.putExtra(CameraService.EXTRA_TOKEN, currentToken());
        intent.putExtra(CameraService.EXTRA_USER, prefs.getString("user_id", ""));
        intent.putExtra(CameraService.EXTRA_DEVICE, prefs.getString("internal_id", ""));
        intent.putExtra(CameraService.EXTRA_COMMAND, commandId);
        intent.putExtra(CameraService.EXTRA_FACING, facing);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);

        handler.post(() -> status.setText("Mengambil foto (notifikasi kamera muncul sebentar)\u2026"));
    }

    private void handleMicrophoneCommand(String commandId) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            updateCommandStatus(commandId, "denied");
            handler.post(() -> status.setText("Izin mikrofon belum diberikan; perintah rekam ditolak."));
            return;
        }
        updateCommandStatus(commandId, "processing");

        Intent intent = new Intent(this, MicService.class);
        intent.putExtra(MicService.EXTRA_URL, prefs.getString("url", "").replaceAll("/$", ""));
        intent.putExtra(MicService.EXTRA_KEY, prefs.getString("key", ""));
        intent.putExtra(MicService.EXTRA_TOKEN, currentToken());
        intent.putExtra(MicService.EXTRA_USER, prefs.getString("user_id", ""));
        intent.putExtra(MicService.EXTRA_DEVICE, prefs.getString("internal_id", ""));
        intent.putExtra(MicService.EXTRA_COMMAND, commandId);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);

        handler.post(() -> status.setText("Merekam suara maks 30 detik (notifikasi mikrofon muncul)\u2026"));
    }

    private void updateDeviceLocation(double lat, double lng) {
        String internalId = prefs.getString("internal_id", "");
        final String base = prefs.getString("url", "").replaceAll("/$", "");
        final String key = prefs.getString("key", "");
        executor.execute(() -> {
            final String token = currentToken();
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
        executor.execute(() -> {
            final String token = currentToken();
            try {
                JSONObject body = new JSONObject();
                body.put("status", newStatus);
                if ("completed".equals(newStatus)) body.put("completed_at", Instant.now().toString());

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
        while ((line = reader.readLine()) != null) result.append(line);
        reader.close();
        return result.toString();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}

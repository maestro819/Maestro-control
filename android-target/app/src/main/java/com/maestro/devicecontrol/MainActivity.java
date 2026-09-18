package com.maestro.devicecontrol;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText urlInput, keyInput, deviceInput, tokenInput;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("maestro", MODE_PRIVATE);
        buildUi();
    }

    private void buildUi() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("Maestro Target"); title.setTextSize(28); box.addView(title);
        TextView note = new TextView(this); note.setText("Perangkat ini hanya menjalankan perintah setelah izin Android diberikan."); box.addView(note);
        urlInput = field("Supabase URL", prefs.getString("url", "")); box.addView(urlInput);
        keyInput = field("Supabase anon key", prefs.getString("key", "")); box.addView(keyInput);
        deviceInput = field("Device ID", prefs.getString("device", UUID.randomUUID().toString())); box.addView(deviceInput);
        tokenInput = field("User access token", prefs.getString("token", "")); box.addView(tokenInput);
        Button save = new Button(this); save.setText("Simpan dan minta izin"); box.addView(save);
        status = new TextView(this); status.setText("Belum terhubung"); box.addView(status);
        save.setOnClickListener(v -> saveAndRequestPermissions());
        setContentView(new ScrollView(this) {{ addView(box); }});
    }

    private EditText field(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(false); return e; }

    private void saveAndRequestPermissions() {
        prefs.edit().putString("url", urlInput.getText().toString().trim()).putString("key", keyInput.getText().toString().trim()).putString("device", deviceInput.getText().toString().trim()).putString("token", tokenInput.getText().toString().trim()).apply();
        if (android.os.Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, PERMISSIONS_REQUEST);
        status.setText("Izin diminta. Perintah kamera/mikrofon selalu memerlukan persetujuan Android.");
        startPolling();
    }

    private void startPolling() {
        handler.postDelayed(new Runnable() { @Override public void run() { poll(); handler.postDelayed(this, 15000); } }, 1000);
    }

    private void poll() {
        String base = prefs.getString("url", ""); String device = prefs.getString("device", ""); String key = prefs.getString("key", ""); String token = prefs.getString("token", "");
        if (base.isEmpty() || device.isEmpty() || key.isEmpty() || token.isEmpty()) { status.setText("Isi Supabase URL, anon key, Device ID, dan access token."); return; }
        executor.execute(() -> { try {
            URL u = new URL(base.replaceAll("/$", "") + "/rest/v1/device_commands?device_id=eq." + device + "&status=eq.pending&select=id,command&limit=1");
            HttpURLConnection c = (HttpURLConnection) u.openConnection(); c.setRequestProperty("apikey", key); c.setRequestProperty("Authorization", "Bearer " + token);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream())); StringBuilder out = new StringBuilder(); String line; while ((line = r.readLine()) != null) out.append(line);
            handler.post(() -> status.setText("Terhubung. Perintah pending: " + out));
        } catch (Exception e) { handler.post(() -> status.setText("Koneksi gagal: " + e.getMessage())); } });
    }

    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); executor.shutdownNow(); super.onDestroy(); }

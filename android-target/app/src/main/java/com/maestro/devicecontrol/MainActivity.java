package com.maestro.devicecontrol;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
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
        note.setText("Perangkat ini hanya menjalankan perintah setelah izin Android diberikan.");
        box.addView(note);

        urlInput = field("Supabase URL", prefs.getString("url", ""));
        keyInput = field("Supabase anon key", prefs.getString("key", ""));
        deviceInput = field("Device ID", prefs.getString("device", UUID.randomUUID().toString()));
        tokenInput = field("User access token", prefs.getString("token", ""));
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

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            }, PERMISSIONS_REQUEST);
        }
        status.setText("Izin diminta. Kamera dan mikrofon tidak dijalankan diam-diam.");
        startPolling();
    }

    private void startPolling() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                poll();
                handler.postDelayed(this, 15000);
            }
        }, 1000);
    }

    private void poll() {
        final String base = prefs.getString("url", "");
        final String device = prefs.getString("device", "");
        final String key = prefs.getString("key", "");
        final String token = prefs.getString("token", "");
        if (base.isEmpty() || device.isEmpty() || key.isEmpty() || token.isEmpty()) {
            status.setText("Isi Supabase URL, anon key, Device ID, dan access token.");
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String endpoint = base.replaceAll("/$", "")
                            + "/rest/v1/device_commands?device_id=eq."
                            + device + "&status=eq.pending&select=id,command&limit=1";
                    URL url = new URL(endpoint);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestProperty("apikey", key);
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }
                    reader.close();
                    final String message = "Terhubung. Perintah pending: " + result;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText(message);
                        }
                    });
                } catch (final Exception error) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("Koneksi gagal: " + error.getMessage());
                        }
                    });
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}

# Maestro Control — Panduan Setup (semua fitur)

Panduan dari nol sampai lokasi, kamera, suara, dan notifikasi berfungsi.
**Tidak perlu Vercel.**

---

## 0. Ringkas fitur

| Fitur | Status | Cara kerja |
|---|---|---|
| Lokasi (find-my-phone) | ✅ | Tombol "Refresh Lokasi" → peta. |
| Kamera (foto) | ✅ | Transparan: notifikasi + indikator kamera muncul saat memotret. |
| Suara (rekam) | ✅ | Transparan: notifikasi + indikator mikrofon; **maks 30 detik** per rekaman. |
| Notifikasi | ✅ | **Metadata saja**: nama aplikasi + waktu (isi pesan TIDAK diambil). |
| Status izin | ✅ | Lokasi, kamera, mikrofon, notifikasi. |
| Online/offline + auto-reconnect | ✅ | Sambung ulang otomatis setelah jaringan hilang. |

**Prinsip:** semua akses sensor **transparan** — selalu ada tanda terlihat di HP target, dan
hanya jalan setelah izin diberikan. Mikrofon dibatasi 30 detik, notifikasi hanya metadata.

---

## 1. Supabase (sekali saja)

1. Project Supabase → **SQL Editor**.
2. Salin **seluruh isi** `supabase/schema.sql` → tempel → **Run**.
   Skrip ini otomatis membuat: tabel `device_commands` (dgn `params`/`result`), tabel
   `device_notifications`, bucket `device-photos` & `device-clips`, kebijakan akses, dan realtime.

---

## 2. Dashboard (tanpa Vercel)

1. Buka `web-controller/standalone.html` di browser (klik dua kali).
2. Tempel **Supabase URL** + **anon key** → **Simpan koneksi**.
3. Login dengan email + password Supabase.

Panel tersedia: pilih perangkat, Status Akses (4 izin), **Lokasi** (peta), **Kamera**,
**Suara**, dan **Notifikasi**.

---

## 3. Build APK (GitHub Actions)

1. Unggah project ke repository GitHub.
2. Tab **Actions** → **Build Android APK** → **Run workflow**.
3. Unduh artifact `maestro-target-debug-apk` → berisi `app-debug.apk`.

---

## 4. Konfigurasi di HP target

1. Pasang APK, buka **Maestro Target**.
2. Isi: Supabase URL, anon key, Email, Password (Device token biarkan apa adanya).
3. **"Simpan dan minta izin"** → setujui **lokasi + kamera + mikrofon + notifikasi**.
4. **"Aktifkan akses notifikasi"** → akan membuka Pengaturan Android → aktifkan
   **Maestro Target** pada daftar akses notifikasi. (Langkah ini memang harus manual —
   Android mewajibkannya, jadi tidak bisa diam-diam.)
5. Status "Tersinkron ke Supabase…" = siap.

**Auto-reconnect:** jaringan hilang → pesan "Koneksi terputus…" → begitu online kembali,
sinkronisasi tersambung **sendiri** tanpa tekan tombol.

---

## 5. Uji coba

- **Lokasi:** dashboard → **↻ Refresh Lokasi** → koordinat muncul di peta (~8 detik).
- **Kamera:** pilih depan/belakang → **📷 Ambil Foto** → notifikasi kamera di HP target → foto muncul.
- **Suara:** **🎙 Rekam Suara** → notifikasi mikrofon di HP target → rekaman siap diputar (maks 30 detik).
- **Notifikasi:** begitu akses notifikasi aktif, daftar "aplikasi + waktu" muncul di dashboard.
- **Auto-reconnect:** matikan data HP target ±1 menit, nyalakan lagi → badge ONLINE kembali sendiri.

---

## 6. Catatan tanggung jawab

Aplikasi ini transparan dan berbasis persetujuan. Gunakan hanya pada perangkat milikmu atau
perangkat yang pemakainya mengetahui & menyetujui (mis. HP anak dengan sepengetahuannya).
Ini bukan alat untuk memata-matai orang tanpa sepengetahuannya.

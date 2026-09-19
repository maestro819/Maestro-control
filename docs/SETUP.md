# Panduan Setup (versi yang sudah diperbaiki)

## Apa yang diperbaiki
1. **`web-controller` sekarang punya login.** Sebelumnya tidak ada proses masuk sama
   sekali, padahal semua tabel Supabase memakai Row Level Security berbasis
   `auth.uid()`. Tanpa sesi login, semua query otomatis ditolak — makanya
   sebelumnya "tidak bisa konek".
2. **Android target sekarang benar-benar mendaftarkan dirinya** ke tabel `devices`
   (sebelumnya cuma polling perintah, tapi tidak pernah membuat baris device sama
   sekali, sehingga perangkat tidak pernah muncul di dashboard).
3. **Bug pencocokan ID diperbaiki.** `device_commands.device_id` merujuk ke `id`
   internal Supabase (UUID otomatis), bukan ke token yang kamu ketik manual di
   layar Android. Sekarang aplikasi Android menyimpan `id` hasil pendaftaran dan
   memakainya untuk polling perintah.
4. **Perintah "Lokasi"** sekarang benar-benar mengambil satu titik lokasi terakhir
   (last known location) dan mengirim hasilnya ke tabel `devices`, lalu tampil di
   layar Lokasi controller.
5. **Kamera, mikrofon, dan notifikasi** sengaja masih placeholder (tombol ada,
   tapi tidak mengaktifkan sensor/menarik notifikasi sungguhan) — lihat catatan
   di README soal batasan ini.

## Langkah setup

### 1. Supabase
1. Buat project di supabase.com (kalau belum).
2. Buka **SQL Editor**, jalankan isi `supabase/schema.sql` (aman dijalankan
   ulang meskipun sudah pernah dijalankan sebagian).
3. Buka **Authentication -> Users**, buat satu user (email + password) — ini
   akun *owner* yang akan dipakai untuk login di web-controller sekaligus
   sebagai identitas yang dipasangkan ke perangkat target.
4. Catat **Project URL** dan **anon public key** dari **Project Settings -> API**.

### 2. Web controller
1. Masuk ke folder `web-controller/`, jalankan `npm install`.
2. Copy `.env.example` menjadi `.env.local`, isi `VITE_SUPABASE_URL` dan
   `VITE_SUPABASE_ANON_KEY` dengan nilai dari langkah Supabase di atas.
3. Jalankan `npm run dev` untuk coba lokal, lalu login pakai email/password
   user yang dibuat di langkah 1.3.
4. Untuk deploy: hubungkan repo ke Vercel, isi kedua env var yang sama di
   **Project Settings -> Environment Variables** pada Vercel.

### 3. Android target
1. Buka `android-target/` di Android Studio, biarkan Gradle sync.
2. Build & install ke HP target.
3. Di app: isi **Supabase URL** dan **anon key** yang sama seperti di atas.
4. Isi **Device token** bebas (boleh biarkan default, harus unik per perangkat).
5. Isi **User access token** — ambil dari sesi login web-controller. Cara
   tercepat untuk sekarang: setelah login di web-controller, buka DevTools
   browser -> Console, jalankan:
   ```js
   (await supabase.auth.getSession()).data.session.access_token
   ```
   Salin hasilnya ke kolom "User access token" di app Android. (Token ini
   akan kedaluwarsa setelah beberapa waktu — untuk pemakaian jangka panjang,
   ini bagian yang sebaiknya diganti dengan refresh-token otomatis di
   aplikasi Android, belum termasuk di build ini.)
6. Tekan **"Simpan dan minta izin"** — akan muncul dialog izin Android standar
   untuk lokasi, kamera, mikrofon, dan notifikasi.
7. Setelah izin diberikan/ditolak, perangkat otomatis mendaftar ke Supabase
   setiap 15 detik dan status akses akan muncul di dashboard web.

### 4. Uji coba
- Di web-controller, pilih perangkat di dropdown, buka **STATUS AKSES** —
  harus menampilkan izin yang sudah diberikan di HP target.
- Buka menu **Lokasi**, tekan **Refresh Lokasi** — dalam ~15 detik koordinat
  terakhir HP target akan muncul (butuh izin lokasi dan GPS/jaringan aktif).

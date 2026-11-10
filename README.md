# WebDev Studio

**Local Web Development Environment untuk Android** — impor proyek web dari penyimpanan HP,
jalankan lewat HTTP server / Node.js bawaan aplikasi, lalu uji di browser internal (WebView)
atau browser eksternal. Tanpa Termux, tanpa upload ke internet.

- Kotlin · Jetpack Compose · Material 3 (dark theme utama) · Coroutines · Storage Access Framework
- Output: `app-debug.apk` dari GitHub Actions (tidak perlu Android Studio)

> **Status jujur (baca ini dulu):** kode ditulis lengkap, tetapi lingkungan penulisnya tidak memiliki
> Android SDK / akses ke Google Maven, sehingga **APK belum pernah dibangun dan dijalankan di perangkat**.
> Yang sudah diuji nyata: HTTP server statis (18 pengecekan: Range, HEAD, traversal, MIME wasm, keep-alive)
> dan logika skrip penyedia runtime. Build pertama di GitHub Actions kemungkinan masih menampilkan
> beberapa error kompilasi kecil; kirim log-nya dan akan diperbaiki. Lihat [Keterbatasan](#keterbatasan).

## Fitur

| Area | Implementasi |
|---|---|
| Import folder | `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` (read-only) |
| Import ZIP | Ringkasan arsip, ekstraksi ke penyimpanan aplikasi, proteksi **ZIP Slip**, batas ukuran/jumlah file |
| Analisis proyek | Membaca isi `package.json` (deps, scripts), `vite.config.*`, `index.html`, file `.wasm`, manifest/service worker → HTML, JS, TS, React, Vue, Vite, Three.js, Node/Express, PWA, WebAssembly |
| Mode hosting | **Static** (tanpa Node), **Development** (Vite/framework dev server), **Backend** (Node/Express), atau "tidak didukung" dengan penjelasan |
| Static HTTP server | GET/HEAD, Range (206/416), MIME benar (ES module, `application/wasm`, glb, font, mp4/mp3…), ETag/304, redirect folder, bind `127.0.0.1`, anti directory-traversal, SPA fallback (opsional) |
| Node.js runtime | Binary `node` + `npm` Android arm64 sungguhan (lihat bawah), `npm install`, `npm run <script>`, Vite, Express, stdout/stderr langsung ke Server Console |
| Browser internal | WebView + address bar, back/forward/reload, fullscreen (termasuk video), file picker, tab **PREVIEW · CONSOLE · NETWORK · SERVER · ERRORS** |
| Developer tools | Console (console.*, exception, resource error), Network Monitor (server + fetch/XHR), Error Viewer (tipe, pesan, file, baris, stack) |
| Server Manager | START / STOP / RESTART / PREVIEW, status **RUNNING hanya setelah port benar-benar menerima koneksi TCP** |
| Lifecycle | Foreground Service + notifikasi + tombol "Stop semua"; semua proses anak (mis. esbuild) dihentikan saat Stop / app di-swipe |
| Multi-proyek | SQLite; port tetap per proyek (origin WebView konsisten → IndexedDB / Cache API / Service Worker bertahan); maks. 2 server Node bersamaan |
| Compatibility Test | Halaman uji bawaan: JS, ES Modules, WebGL, WebAssembly (+MIME), Canvas, IndexedDB, Cache API, Service Worker, WebSocket, Web Audio, video, pointer, multitouch, fullscreen, file picker |

## Arsitektur

```
app/src/main/java/id/webdev/studio/
├── MainActivity.kt, WebDevApp.kt
├── ui/            Theme, Screens (Home, Project Manager, Servers, Settings, dialog import), AppViewModel
├── explorer/      File Explorer (pohon lazy, ukuran file, viewer teks, refresh) — read-only
├── browser/       BrowserScreen (WebView + DevTools panels)
├── projects/      ProjectAnalyzer, ZipImporter, WorkspaceSync
├── fs/            ProjectFs (SafFs, FileFs, SubFs, AssetFs), MimeTypes
├── server/        StaticHttpServer, ServerManager
├── runtime/       NodeRuntime, ScriptResolver, ProcessRunner (+ pembunuh pohon proses)
├── developer/     DevToolsBus + skrip monitor yang disuntikkan ke halaman
├── data/          Model, SQLite (ProjectDb), SettingsStore
└── services/      ServerService (foreground)
```

### Cara kerja

1. **Import** → folder (SAF) atau ZIP. Aplikasi hanya *membaca*; file proyek tidak pernah diubah.
2. **Analisis** → deteksi teknologi dari isi file, tentukan mode hosting.
3. **Static**: `StaticHttpServer` membaca file lewat `ProjectFs` (SAF `DocumentsContract`, bukan path biasa).
4. **Development/Backend**: Node.js tidak bisa membaca URI SAF, jadi proyek **disalin inkremental** ke
   `filesDir/workspaces/<id>` (tanpa `node_modules`/`.git`), lalu `npm install` dan server dijalankan di sana.
   Folder asli Anda tetap utuh. Perubahan di folder asli ikut tersalin setiap kali START.
5. Script dari `package.json` **dibaca**, bukan diasumsikan bernama `dev`. Karena Android tidak punya
   `/usr/bin/env` (shebang `#!/usr/bin/env node` gagal), `ScriptResolver` menerjemahkan `vite` →
   `node node_modules/vite/bin/vite.js` dan memecah `a && b`. Argumen `--host 127.0.0.1 --port N --strictPort`
   ditambahkan untuk Vite/webpack-dev-server/Next/Astro; framework lain menerima env `PORT`/`HOST`.
6. Status menjadi RUNNING hanya setelah koneksi TCP ke port berhasil; port dibaca juga dari log server.

## Cara memakai

1. **Home → IMPORT PROJECT → Select Project Folder** (atau Import ZIP).
2. Di **Projects**, ketuk **START HOSTING**.
   - Dependency belum ada? Muncul `DEPENDENCIES NOT INSTALLED` → **INSTALL DEPENDENCIES** (output di tab SERVER).
3. **PREVIEW** membuka WebView internal; atau set *Default browser mode = Eksternal* di Settings, lalu
   PREVIEW membuka Chrome ke `http://127.0.0.1:<port>/`. Server tetap hidup di Foreground Service.
4. **STOP** menghentikan server dan seluruh proses turunannya.
5. **Remove** hanya menghapus proyek dari daftar; folder asli tidak dihapus.

## Runtime Node.js

Node harus berupa binary **bionic (Android) arm64** — build Linux biasa (glibc) tidak bisa jalan di Android.
Pendekatan yang dipakai: binary `node` + `npm` dari repositori paket Termux, dijalankan sebagai proses
terpisah (bukan `nodejs-mobile`). Alasannya: `nodejs-mobile` hanya satu instance in-process dan tidak
menyediakan npm/`child_process`, sehingga tidak bisa menjalankan `npm install` + Vite sungguhan.

- **CI** menjalankan `scripts/fetch-node-runtime.py` (resolve dependensi, verifikasi SHA256, cek ELF AArch64)
  → `runtime-bundle/assets/node-runtime.zip` → masuk ke APK sebagai aset.
- Di **Settings → Runtime Node.js**: *Pasang dari APK*, atau *Impor ZIP* (ZIP berisi `bin/node`, `lib/…`).
  Setelah dipasang, aplikasi menjalankan `node --version` dan `npm --version`; baru dianggap **SIAP** jika berhasil.
- `targetSdk = 28` **disengaja**: Android 10+ (targetSdk ≥ 29) melarang `exec` file di direktori data aplikasi,
  padahal npm memerlukan eksekusi binary native (mis. esbuild). Ini pendekatan Termux. Konsekuensi: APK ini
  untuk sideload, bukan Play Store.

## Keterbatasan

- **Belum diuji di perangkat** (lihat catatan status di atas). Runtime Node khususnya adalah bagian berisiko tertinggi:
  jalur unduh Termux di CI, `LD_LIBRARY_PATH`, DNS/npm di Android semuanya baru dapat dibuktikan dengan menjalankan APK.
- **Paket npm native**: hanya paket yang menyediakan binary Android (`android-arm64`) atau pure-JS/WASM yang jalan.
  Aplikasi mendeteksi beberapa yang bermasalah (sharp, bcrypt, sqlite3, canvas, node-sass, puppeteer, …) dan
  menjelaskannya, tetapi tidak menjanjikan kompatibilitas universal. Vite 8 memakai binding native (rolldown);
  tersedia tidaknya build Android-nya harus dicek saat `npm install` pada proyek GameCube 4Player.
- `npm install` memakai `--ignore-scripts` secara default (dapat dimatikan di Settings).
- Script npm yang memakai pipa/redirect/subshell (`|`, `>`, `;`, `$(…)`) belum didukung dan ditolak dengan pesan jelas.
- Backend yang mengikat `0.0.0.0` sendiri tidak dapat dipaksa ke `127.0.0.1`.
- PHP/Python/Composer tidak didukung (ditandai "tidak didukung" beserta alasannya).
- Perubahan yang dibuat Node di workspace (mis. hasil build) tidak ditulis balik ke folder asli.
- Ukuran APK bertambah puluhan MB bila runtime Node disertakan.
- Network Monitor: request ke server Node tidak terlihat sisi-server; yang tampil adalah fetch/XHR dan error dari halaman.

## Build lewat GitHub Actions

1. Push seluruh isi folder ini ke repository GitHub (branch `main`).
2. Tab **Actions → Android Build** berjalan otomatis (atau **Run workflow**; centang/uncentang *bundle_node*).
3. Setelah selesai, unduh artifact **`app-debug.apk`** dari halaman run.

Workflow (`.github/workflows/android-build.yml`): checkout → JDK 17 → (opsional) siapkan runtime Node →
Android SDK → Gradle → `assembleDebug` → verifikasi APK ada → upload artifact. Langkah runtime Node
`continue-on-error`: bila gagal, APK tetap dibuat dan peringatan tampil di log; runtime bisa diimpor manual.
Tidak ada file lokal yang dibutuhkan; Gradle Wrapper (8.14.3) disertakan.

### Build lokal (opsional)

```bash
./gradlew assembleDebug        # hasil: app/build/outputs/apk/debug/app-debug.apk
```

## Proyek uji yang dituju

GameCube 4Player (React 19 + Vite 8 + TS + Three.js → Development), YSMParser Web 0.3.6 (HTML+WASM → Static),
RPG 3D Prototype (Three.js; Static atau Development), NES Mario v1.1.1 (PWA → Static). Isi proyek tidak diubah.

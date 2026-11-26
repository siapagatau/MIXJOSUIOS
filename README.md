# MIXJOS (mix.jos.android.lwbdgt)

APK **DTX** asli yang pad-nya diganti kontrol **mixer** (mic → reverb, echo, EQ, gate → speaker, EQ audio global, panel floating).
Tampilan, palet, panel, strip tombol, tema, background, visual sentuhan, pemilih skin, dan Skin Engine = DTX; yang diganti hanya isinya.

## Susunan layar
Bila skin aktif di APK, panel FLOATING ikut menampilkan skin yang sama (real time, proporsi asli, tinggi jendela menyesuaikan sekali per skin). Tanpa skin, layar utama dan panel FLOATING memakai **satu panel yang sama** (`MixerGrid` di `MixerPanel.kt`): kartu, urutan, warna, huruf, saklar, dan meter identik.
Yang menyesuaikan hanya susunannya terhadap lebar ruang, dari data `MixGroups` yang sama. Tampilan datar (tanpa garis tepi/bayangan).
Dua BAGIAN dengan pita judul berwarna TEMA (hanya judul, tanpa keterangan):
1. **MIKROFON**: kartu MIC (2 knob), REVERB (6), ECHO (3), EQ MIC (3), LEVEL (meter mic). Tiap kartu punya saklar ON/OFF sendiri (ketuk seluruh baris judul kartu).
2. **EQUALIZER AUDIO** (musik / audio HP, bukan mic): GAIN, SUB, LOW, MID, HIGH + meter OUT. Saklar ON/OFF di pita judul (ketuk seluruh pita).
- **LEBAR** (layar utama / floating dilebarkan ≥ 640dp): dua bagian berdampingan, tanpa gulir. MIKROFON 8 kolom `[MIC 2][REVERB 6]` / `[ECHO 3][EQ MIC 3][LEVEL 2]`; EQ AUDIO 3 kolom `[GAIN SUB LOW]` / `[MID HIGH OUT]`.
- **TEGAK** (floating sempit): bagian ditumpuk, 3 knob per baris, digulir: `[MIC 2][LEVEL 1]` / `[REVERB 3+3]` / `[ECHO 3]` / `[EQ MIC 3]`, lalu EQ AUDIO sama dengan susunan lebar.
Saklar/tombol hanya berubah saat DIKETUK (jari diangkat di tempat yang sama); menggeser/scroll tidak mengubahnya. Kelompok yang OFF tampil redup. Strip bawah (layar utama): **FLOATING · TEMA · SKIN**; tab tipis kanan = sembunyikan strip. Pegangan resize floating ada di baris bawah sendiri (tidak menutupi knob).
Knob: DIPUTAR dengan jari mengelilingi pusat knob (searah jarum jam = naik), halus, tidak loncat; ketuk dua kali = nilai awal. Knob EQ terisi dari tengah (0 dB).
Teks tombol (strip bawah, pemilih skin) putih netral, tidak ikut tema. Warna: INDIKATOR (busur knob, meter, LED saklar, FLOATING aktif) = hijau tetap (`IndicatorGreen`). Warna TEMA = pita judul bagian, judul kartu, teks tombol strip.

## Equalizer 31 band (v1.7.0)
Mode yang menyatu di BAWAH mixer (gulir ke bawah): tombol **EQUALIZER** (strip bawah layar utama) / **EQ** (baris bawah floating). L (atas) dan R (bawah) masing-masing 31 fader (20 Hz-20 kHz, ±12 dB) + knob GAIN; ketuk dua kali = 0 dB. Bekerja di suara semua app (efek yang sama dengan EQ AUDIO, dijumlahkan). Skin equalizer bertipe `eq` (repo `ALL-TYPE-SKIN-MIXJOS`, tab EQUALIZER di pemilih skin; lihat `PROMPT_SKIN_EQUALIZER.md`); mode skin: EQUALIZER menyambung tanpa celah di bawah skin mixer.

## Kompresor (v1.8.0)
Mode yang menyatu di bawah mixer dan DI ATAS equalizer: tombol **KOMPRESOR** (strip bawah) / **KOMP** (baris bawah floating). Knob Attack, Release, Rasio, Frekuensi, Threshold, Gain + Keseimbangan (fokus L/R) + Volume + Setel ulang, bekerja di suara semua app (efek yang sama dengan EQ). Skin bertipe `comp` (repo `ALL-TYPE-SKIN-MIXJOS`, tab KOMPRESOR di pemilih skin; lihat `PROMPT_SKIN_KOMPRESOR.md`).

## FX (v1.9.0, gaya taGunner)
Mode ke-3: tombol **FX** (strip bawah) / **FX** (baris bawah floating). Bagian FX di bawah KOMPRESOR, di atas EQUALIZER: FX RACK (SUBSONIC, SUB BASS, TUBE DRIVE, 8D PAN), PARAMETRIC (gaya Poweramp: band bebas 0..16, tipe filter, Frekuensi/Gain/Q, kanal L+R/L/R, Preamp). Efek yang sama dengan EQ/kompresor (session 0). Kode: `TaFx.kt`, `TaFxPanel.kt`, `GlobalEqEngine.kt`.

## Skin mixer
Skin Engine DTX + 3 tambahan: `toggle`, `slider`, lampu `meter`. Katalog function dan aturan lengkap: `PROMPT_SKIN_MIXER.md` (ada di repo `ALL-TYPE-SKIN-MIXJOS`, folder `prompts/`).
Tidak ada skin bawaan di APK: semua skin diunduh dari repo (cache offline setelah diunduh). Repo skin: SATU repo untuk semua jenis, `https://github.com/siapagatau/ALL-TYPE-SKIN-MIXJOS` (branch master). `index.json` memuat field `type` per skin (`mixer` | `comp` | `eq`); tiap tab pemilih skin hanya menampilkan tipenya. `assets/config/skin-repo.json`, `eq-skin-repo.json`, dan `comp-skin-repo.json` menunjuk index yang SAMA.

## Struktur kode (package com.kendang.realpads)
- `MainActivity.kt` — activity + `MixerScreen`, `MixActionStrip`, daftar kelompok `MixGroups`.
- `MixerPanel.kt` — `MixerGrid`: SATU-SATUNYA panel mixer (susunan lebar & tegak), dipakai layar utama dan floating.
- `MixerTiles.kt` — `GroupCard`, `MixSection`, `KnobTile`, `Knob`, `MeterTile` (mendatar/tegak otomatis), `VerticalSlider`.
- `DtxPalette.kt`, `ThemeState.kt`, `BackgroundState.kt`, `TouchFx.kt`, `ColorPickerDialog.kt` — dari DTX.
- `FloatingBody.kt`, `FloatingWindowService.kt`, `GlobalEq*`, `SettingsStore/Bus`, `MusicMeter`, `AudioEngine.kt` + `cpp/` (Oboe `KaraokeEngine`) — mesin mixer.
- `skin/` — Skin Engine DTX + `MixerBridge` (satu-satunya penghubung skin/UI → engine; nilai berkunci nama function).

Menambah kontrol mixer baru: tambah function di `FunctionRegistry` + `MixerBridge.values/pushValue`, lalu daftarkan di `MixGroups`. Bila kontrol baru perlu tempat khusus, atur di `MixerWide`/`MixerNarrow` (`MixerPanel.kt`); layar & floating ikut.

## Build
GitHub Actions → **Build Debug APK** (JDK 17, SDK 36, NDK 28.2, CMake 3.22.1, Gradle 9.3.1). Artefak `apk-mixjos-debug`; bila gagal unduh `build-reports`.

## Status verifikasi (jujur)
Kode BELUM dikompilasi dan BELUM dijalankan di perangkat (sandbox tanpa Android SDK/jaringan ke Google Maven); hanya diperiksa statis
(impor, simbol, kurung seimbang). Kirim log Actions bila ada error kompilasi.

## Zoom floating (skin) - v1.3.0
Saat skin aktif, panel floating bisa di-zoom (1x-4x): cubit dua jari, geser dua jari untuk pindah, atau tombol **- / 1x / +** di tengah baris bawah. Zoom hanya memperbesar gambar (tanpa render ulang), jadi instan tapi agak buram di zoom besar. Satu jari tetap mengoperasikan kontrol skin.

## Panggung (live stage)
Tombol **PANGGUNG** di strip bawah menampilkan panggung di atas mixer (skin bertipe `stage` dari repo `ALL-TYPE-SKIN-MIXJOS`, diunduh otomatis). Scene mengikuti jam HP (cerah / sunset / malam) atau dikunci manual. Layar green screen bisa diisi foto, GIF, atau video (diputar berulang, tanpa suara) dengan ukuran & posisi bebas. Mode **EDIT**: tambah lampu sorot (gerak diam/ayun/sapu, atur sudut & waktu) dan lampu biasa, warna bebas, kedip/denyut sesuai tempo, geser lampu ke mana saja.

## Mode CHANNEL (v1.10.0)
Tombol **CHANNEL** (strip bawah) menampilkan bagian **MIXER CHANNEL** di atas mixer klasik: deretan strip seperti mixer sungguhan. Tiap strip: SEND (knob) -> INSERT (tombol ON/OFF) -> FADER vertikal + METER -> tombol ON. Bawaan hanya channel **MIC**; tile **TAMBAH** menawarkan **AUDIO** (gain EQ audio, EQ 31 band, kompresor) dan **YOUTUBE** (volume pemutar, reverb, delay); tombol **x** di judul strip menghapus channel. Satu sumber = satu channel. Strip hanya papan kontrol untuk fungsi yang sudah ada, jadi nilainya sinkron dengan panel klasik, skin, dan floating. Kode: `ChannelMixer.kt`.

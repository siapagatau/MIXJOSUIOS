package com.kendang.realpads

import android.content.Context

// ---------------------------------------------------------------------------------------------------------------------
// RESET SEMUA SETELAN AUDIO (tombol ikon di pojok kanan strip bawah). Mengembalikan ke nilai awal:
//   - MIXER      : mic volume, gate, reverb, echo, EQ mic 3 band, EQ AUDIO (ReverbSettings -> SettingsStore)
//   - REVERB AUDIO (pemutar YouTube) : semua knob + kirim per band (SysReverbUi)
//   - KOMPRESOR  : semua knob (CompUi)
//   - EQUALIZER 31 BAND : semua band + gain (Eq31Ui)
//   - FX         : subsonic, sub bass, tube, 8D pan, parametrik + band (FxUi)
// Saklar ON/OFF tiap efek ikut kembali ke bawaan (EQ 31 / kompresor / FX / EQ AUDIO / echo = OFF; reverb mic & EQ mic = ON).
// TIDAK diubah: mode tampilan bagian (PANGGUNG/KOMPRESOR/EQUALIZER/FX), mic yang sedang menyala, saklar REVERB AUDIO yang
// sedang menyala (hanya setelannya yang direset), mode YOUTUBE, skin, tema, dan isi PANGGUNG.
// ---------------------------------------------------------------------------------------------------------------------
fun resetAllAudioSettings(context: Context) {
    val app = context.applicationContext

    // 1) Mixer: simpan dulu (service EQ global membaca status ON/OFF dari SettingsStore), lalu kirim ke engine yang sedang jalan.
    val d = SettingsStore.defaults()
    SettingsStore.save(app, d)
    if (AudioEngine.nativeIsRunning()) pushAllSettingsToEngine(d)
    GlobalEqEngine.applySettings(d)

    // 2) Efek global + reverb sistem. Mode tampilan dipertahankan.
    Eq31Ui.update(app) { Eq31Settings(mode = it.mode) }
    CompUi.update(app) { CompSettings(mode = it.mode) }
    FxUi.update(app) { FxSettings(mode = it.mode) }
    FxUi.selected = 0
    SysReverbUi.update(app) { SysReverbSettings() }

    // 3) Service EQ global: dimatikan bila tidak ada efek yang ON lagi.
    syncGlobalEqService(app)

    // 4) Beritahu layar utama (skin/klasik) & panel floating agar tampilan knob langsung ikut.
    SettingsBus.publish(d)
}

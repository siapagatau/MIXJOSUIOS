package com.kendang.realpads

import android.content.Context

// REVERB + DELAY UNTUK PEMUTAR YOUTUBE DI DALAM APP, dengan kirim (send) per band.
// DSP-nya sekarang Web Audio di halaman (YtScripts.AUDIO, dikendalikan YtReverb.kt) - TANPA rekam layar.
// File ini cuma pengaturan + penyimpanan. (C++ SysReverb.* lama tidak lagi dipanggil dari Kotlin.)
//
// Semua knob 0..1 (sama seperti knob lain di app). Skala ke satuan asli ada di push().
data class SysReverbSettings(
    val reverbOn: Boolean = true,
    val echoOn: Boolean = false,
    // Reverb
    val wet: Float = 0.5f,
    val room: Float = 0.6f,
    val damp: Float = 0.4f,
    val width: Float = 1.0f,
    val cess: Float = 0.4f,
    val preDelay: Float = 0.2f,
    // Echo / delay
    val echoDelay: Float = 0.45f,
    val echoFeedback: Float = 0.45f,
    val echoMix: Float = 0.4f,
    // Output efek (0.5 = apa adanya)
    val gain: Float = 0.5f,
    // Kirim per band: [Sub <90Hz, Low 90-400Hz, Mid 400-4kHz, High >4kHz]
    val revSend: List<Float> = listOf(0.0f, 0.3f, 0.8f, 1.0f),
    val echoSend: List<Float> = listOf(0.0f, 0.0f, 0.7f, 0.7f),
)

object SysReverbStore {
    private const val PREFS = "sys_reverb_settings"
    private val BANDS = listOf("sub", "low", "mid", "high")

    fun load(context: Context): SysReverbSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = SysReverbSettings()
        return SysReverbSettings(
            reverbOn = p.getBoolean("reverbOn", d.reverbOn),
            echoOn = p.getBoolean("echoOn", d.echoOn),
            wet = p.getFloat("wet", d.wet),
            room = p.getFloat("room", d.room),
            damp = p.getFloat("damp", d.damp),
            width = p.getFloat("width", d.width),
            cess = p.getFloat("cess", d.cess),
            preDelay = p.getFloat("preDelay", d.preDelay),
            echoDelay = p.getFloat("echoDelay", d.echoDelay),
            echoFeedback = p.getFloat("echoFeedback", d.echoFeedback),
            echoMix = p.getFloat("echoMix", d.echoMix),
            gain = p.getFloat("gain", d.gain),
            revSend = BANDS.mapIndexed { i, b -> p.getFloat("revSend_$b", d.revSend[i]) },
            echoSend = BANDS.mapIndexed { i, b -> p.getFloat("echoSend_$b", d.echoSend[i]) },
        )
    }

    fun save(context: Context, s: SysReverbSettings) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("reverbOn", s.reverbOn)
            .putBoolean("echoOn", s.echoOn)
            .putFloat("wet", s.wet)
            .putFloat("room", s.room)
            .putFloat("damp", s.damp)
            .putFloat("width", s.width)
            .putFloat("cess", s.cess)
            .putFloat("preDelay", s.preDelay)
            .putFloat("echoDelay", s.echoDelay)
            .putFloat("echoFeedback", s.echoFeedback)
            .putFloat("echoMix", s.echoMix)
            .putFloat("gain", s.gain)
        BANDS.forEachIndexed { i, b ->
            e.putFloat("revSend_$b", s.revSend[i])
            e.putFloat("echoSend_$b", s.echoSend[i])
        }
        e.apply()
    }
}

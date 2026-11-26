package com.kendang.realpads

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

// ---------------------------------------------------------------------------------------------------------------------
// REVERB AUDIO (versi baru): reverb + delay untuk suara pemutar YouTube di dalam app, memakai Web Audio API di halaman
// (lihat YtScripts.AUDIO). TIDAK ADA lagi rekam layar / MediaProjection / AudioPlaybackCapture / foreground service:
// suara diambil langsung dari elemen video di WebView, jadi tanpa dialog izin dan tanpa suara aplikasi lain ikut tertangkap.
//
// Konsekuensi: efek hanya berlaku untuk pemutar YouTube di app ini (bukan YouTube/TikTok app lain).
// Selain reverb, setelan EQ/kompresor/FX juga dikirim lewat pesan yang sama (kunci "fx", lihat YtFx.kt).
// Pengaturan (SysReverbSettings / SysReverbUi) dan semua knob/skin tidak berubah - hanya jalur suaranya.
// ---------------------------------------------------------------------------------------------------------------------
object YtReverb {
    private val _running = MutableStateFlow(false)

    /** Saklar REVERB AUDIO (judul bagian). Menggantikan SysReverbService.running. */
    val running = _running.asStateFlow()

    /** Rata-rata kuadrat keluaran efek (diisi dari halaman ~20x/dtk) -> meter OUT. */
    @Volatile var meanSquare = 0f

    /** Rata-rata kuadrat sinyal pemutar YouTube sesudah fader volume channel YOUTUBE (diisi dari halaman) -> meter channel. */
    @Volatile var inMeanSquare = 0f

    fun toggle(context: Context) = setEnabled(context, !_running.value)

    fun setEnabled(context: Context, on: Boolean) {
        if (on == _running.value) return
        val app = context.applicationContext
        // Reverb Audio hidup di pemutar YouTube app; saat floating aktif pemutar itu sengaja dimatikan (efek jadi global).
        if (on && FloatingWindowService.running.value) {
            Toast.makeText(app, "Reverb Audio hanya untuk YouTube di app; dimatikan otomatis saat floating aktif", Toast.LENGTH_SHORT).show()
            return
        }
        _running.value = on
        if (on) {
            YtUi.ensureLoaded(app)
            // Efek hidup di pemutar YouTube -> pastikan pemutarnya ada.
            if (!YtUi.mode) YtUi.setMode(app, true)
            Toast.makeText(app, "Reverb Audio aktif untuk pemutar YouTube di app ini", Toast.LENGTH_SHORT).show()
        } else {
            meanSquare = 0f
        }
        pushNow()
    }

    /** Dipanggil SysReverbUi.update() tiap knob berubah. */
    fun push(@Suppress("UNUSED_PARAMETER") s: SysReverbSettings) = pushNow()

    /** Kirim semua setelan + status ON ke halaman (aman dipanggil kapan saja; diabaikan bila pemutar belum ada). */
    fun pushNow() {
        YtWeb.eval("window.__mixjos&&window.__mixjos.set(" + json(SysReverbUi.s, _running.value) + ")")
    }

    private fun f(x: Float) = String.format(Locale.US, "%.4f", x)

    private fun json(s: SysReverbSettings, on: Boolean): String = buildString {
        append('{')
        append("\"on\":").append(on)
        append(",\"reverbOn\":").append(s.reverbOn)
        append(",\"echoOn\":").append(s.echoOn)
        append(",\"wet\":").append(f(s.wet))
        append(",\"room\":").append(f(s.room))
        append(",\"damp\":").append(f(s.damp))
        append(",\"width\":").append(f(s.width))
        append(",\"cess\":").append(f(s.cess))
        append(",\"preDelay\":").append(f(s.preDelay))
        append(",\"echoDelay\":").append(f(s.echoDelay))
        append(",\"echoFeedback\":").append(f(s.echoFeedback))
        append(",\"echoMix\":").append(f(s.echoMix))
        append(",\"gain\":").append(f(s.gain))
        // Fader volume channel YOUTUBE (gain linear, 1 = 0 dB, maks +6 dB) dan penanda mode CHANNEL (halaman mengukur level untuk meter).
        append(",\"vol\":").append(f(ChUi.ytGain()))
        append(",\"ch\":").append(ChUi.mode && YtUi.mode)
        append(",\"revSend\":[").append(s.revSend.joinToString(",") { f(it) }).append(']')
        append(",\"echoSend\":[").append(s.echoSend.joinToString(",") { f(it) }).append(']')
        // Efek lain (EQ AUDIO, EQ 31 band, kompresor, FX): ikut diproses di halaman, lihat YtFx.
        append(",\"fx\":").append(YtFx.json())
        append('}')
    }
}

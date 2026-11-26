package com.kendang.realpads

import java.util.Locale

// ---------------------------------------------------------------------------------------------------------------------
// EFEK AUDIO UNTUK PEMUTAR YOUTUBE (mode YOUTUBE): EQ AUDIO, EQ 31 band, kompresor, FX (subsonic, sub bass, tube, 8D pan,
// parametrik), keseimbangan + volume. Semuanya dikerjakan di dalam halaman lewat Web Audio (YtScripts.AUDIO), BUKAN di efek
// session 0, supaya ikut terekam saat rekam layar. File ini hanya menyusun JSON setelan yang dikirim ke halaman (lewat
// YtReverb.pushNow); angka dB per band dihitung oleh GlobalEqEngine.eqPlan - rumus yang SAMA dengan efek session 0.
// ---------------------------------------------------------------------------------------------------------------------
object YtFx {
    private fun f(x: Float) = String.format(Locale.US, "%.3f", x)
    private fun arr(a: FloatArray) = a.joinToString(",", "[", "]") { f(it) }

    /** Objek JSON "fx" untuk halaman. Selalu lengkap; "on" = false bila efek tidak boleh jalan di halaman. */
    fun json(): String {
        val s = GlobalEqEngine.lastSettings()
        val eq = Eq31Ui.s
        val c = CompUi.s
        val fx = FxUi.s
        val anyOn = (s?.gEqEnabled == true) || eq.enabled || c.enabled || fx.enabled || ChPeq.anyOn()
        // Halaman hanya memproses bila mode YOUTUBE nyala (bypass) DAN layanan EQ sedang aktif; selain itu efek session 0 yang bekerja.
        val on = anyOn && GlobalEqEngine.bypass && GlobalEqEngine.active.value
        val plan = GlobalEqEngine.eqPlan(s)
        return buildString {
            append("{\"on\":").append(on)
            append(",\"inDb\":").append(f(plan.inputGainDb))
            append(",\"pre\":[").append(arr(plan.preDb[0])).append(',').append(arr(plan.preDb[1])).append(']')
            append(",\"comp\":{\"on\":").append(c.enabled)
            append(",\"freq\":").append(f(Comp.freqHz(c.freq)))
            append(",\"atk\":").append(f(Comp.attackMs(c.attack)))
            append(",\"rel\":").append(f(Comp.releaseMs(c.release)))
            append(",\"ratio\":").append(f(Comp.ratio(c.ratio)))
            append(",\"thr\":").append(f(Comp.thresholdDb(c.threshold)))
            append(",\"mk\":").append(f(Comp.gainDb(c.gain)))
            val pl = if (c.enabled) Comp.sideAttenDb(c, Eq31.LEFT) else 0f
            val pr = if (c.enabled) Comp.sideAttenDb(c, Eq31.RIGHT) else 0f
            append(",\"post\":[").append(f(pl)).append(',').append(f(pr)).append("]}")
            val peqBands = ChPeq.combined(fx)   // band FX + channel parametrik mode CHANNEL
            val peqOn = peqBands.isNotEmpty()
            append(",\"peq\":{\"on\":").append(peqOn).append(",\"bands\":[")
            append(peqBands.joinToString(",") { "[${it.type},${f(it.hz)},${f(it.db)},${f(it.q)},${it.ch}]" })
            append("]}")
            append(",\"tube\":{\"on\":").append(fx.enabled && fx.tubeOn).append(",\"db\":").append(f(Fx.tubeDb(fx.tube))).append('}')
            append(",\"pan\":{\"on\":").append(fx.enabled && fx.panOn)
            append(",\"depth\":").append(f(Fx.panDepthDb(fx.panDepth)))
            append(",\"period\":").append(f(Fx.panPeriodSec(fx.panCycle))).append('}')
            append('}')
        }
    }
}

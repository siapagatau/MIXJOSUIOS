package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

// ---------------------------------------------------------------------------------------------------------------------
// CHANNEL PARAMETRIK (mode CHANNEL): tiap channel = satu band PEAK pada frekuensi pilihan pengguna (20 Hz - 20 kHz).
// Fader = menaikkan / menurunkan frekuensi itu, 0 dB di tengah, +-12 dB. Tiap channel punya tombol ON sendiri.
// Band-band ini dijumlahkan dengan band parametrik FX (lihat [combined]) dan ikut dikerjakan di jalur yang SAMA dengan EQ lain:
// efek session 0 (GlobalEqEngine.applyParametric) atau, saat mode YOUTUBE, di dalam halaman (YtFx.json).
// ---------------------------------------------------------------------------------------------------------------------
data class ChPeqBand(val id: Int, val hz: Float, val db: Float, val on: Boolean)

object ChPeq {
    const val RANGE_DB = 12f
    const val MAX = 8
    const val Q = 1.0f
    private const val PREFS = "channel_peq"

    var bands: List<ChPeqBand> by mutableStateOf(emptyList())
        private set
    private var nextId = 1
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        nextId = p.getInt("next", 1)
        bands = (p.getString("bands", null) ?: "").split(';').mapNotNull { part ->
            val v = part.split(',')
            if (v.size != 4) return@mapNotNull null
            val id = v[0].toIntOrNull() ?: return@mapNotNull null
            val hz = v[1].toFloatOrNull() ?: return@mapNotNull null
            val db = v[2].toFloatOrNull() ?: return@mapNotNull null
            ChPeqBand(id, hz.coerceIn(Fx.HZ_MIN, Fx.HZ_MAX), db.coerceIn(-RANGE_DB, RANGE_DB), v[3] == "1")
        }.take(MAX)
        nextId = maxOf(nextId, (bands.maxOfOrNull { it.id } ?: 0) + 1)
        loaded = true
    }

    private fun save(context: Context) {
        val s = bands.joinToString(";") { String.format(Locale.US, "%d,%.2f,%.3f,%d", it.id, it.hz, it.db, if (it.on) 1 else 0) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("bands", s).putInt("next", nextId).apply()
    }

    /** Terapkan perubahan: simpan, kirim ke efek yang sedang jalan; [service] = nyalakan/matikan layanan EQ sesuai kebutuhan. */
    private fun update(context: Context, service: Boolean, f: (List<ChPeqBand>) -> List<ChPeqBand>) {
        ensureLoaded(context)
        bands = f(bands)
        save(context)
        GlobalEqEngine.refresh()
        if (service) syncGlobalEqService(context)
    }

    /** Frekuensi dirapikan supaya angkanya enak dibaca: <100 -> 1 Hz, <1k -> 5 Hz, <10k -> 10 Hz, selebihnya 100 Hz. */
    fun niceHz(hz: Float): Float {
        val x = hz.coerceIn(Fx.HZ_MIN, Fx.HZ_MAX)
        val step = when {
            x < 100f -> 1f
            x < 1000f -> 5f
            x < 10000f -> 10f
            else -> 100f
        }
        return (Math.round(x / step) * step).coerceIn(Fx.HZ_MIN, Fx.HZ_MAX)
    }

    /** Tambah channel; mengembalikan id channel baru, atau -1 bila sudah penuh. Channel baru langsung ON (fader di 0 dB, jadi belum mengubah suara). */
    fun add(context: Context, hz: Float): Int {
        ensureLoaded(context)
        if (bands.size >= MAX) return -1
        val id = nextId++
        update(context, true) { it + ChPeqBand(id, niceHz(hz), 0f, true) }
        return id
    }

    fun remove(context: Context, id: Int) = update(context, true) { l -> l.filter { it.id != id } }

    fun setDb(context: Context, id: Int, db: Float) {
        val x = db.coerceIn(-RANGE_DB, RANGE_DB)
        update(context, false) { l -> l.map { if (it.id == id) it.copy(db = x) else it } }
    }

    fun setHz(context: Context, id: Int, hz: Float) {
        val x = niceHz(hz)
        update(context, false) { l -> l.map { if (it.id == id) it.copy(hz = x) else it } }
    }

    fun setOn(context: Context, id: Int, on: Boolean) = update(context, true) { l -> l.map { if (it.id == id) it.copy(on = on) else it } }

    /** Dipanggil GlobalEqService bila efek gagal dibuat -> semua channel parametrik kembali OFF. */
    fun forceDisabled(context: Context) = update(context, false) { l -> l.map { it.copy(on = false) } }

    fun anyOn(): Boolean = bands.any { it.on }

    /** Band channel yang sedang ON, dalam bentuk band parametrik FX (tipe PEAK, kanal L+R). */
    fun activeBands(): List<PeqBand> = bands.filter { it.on }.map { PeqBand(Fx.T_PEAK, it.hz, it.db, Q, 0) }

    /** Semua band parametrik yang harus dikerjakan: band FX (bila FX + parametrik ON) ditambah band channel yang ON. */
    fun combined(fx: FxSettings): List<PeqBand> = (if (fx.enabled && fx.peqOn) fx.bands else emptyList()) + activeBands()
}

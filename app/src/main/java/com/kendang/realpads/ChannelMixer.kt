package com.kendang.realpads

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kendang.realpads.skin.MixerBridge
import com.kendang.realpads.skin.SkinUiState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

// ---------------------------------------------------------------------------------------------------------------------
// MODE CHANNEL (mixer channel ala GP Mix / mixer tablet): satu strip per channel, tiap strip = judul -> keterangan ->
//   FADER vertikal + METER (indikator) -> nilai dB -> tombol ON. TANPA knob SEND dan TANPA tombol efek di dalam strip.
//
// Channel yang tersedia: MASTER (volume sistem), MIC (volume), YOUTUBE (volume pemutar), EQ AUDIO (gain), REVERB AUDIO (gain,
// hanya bila mode YOUTUBE nyala), KOMPRESOR (gain), EQ KANAN (gain), EQ KIRI (gain), dan PARAMETRIK (satu channel = satu
// frekuensi pilihan, fader menaikkan / menurunkan frekuensi itu, ChPeq.kt).
// DEFAULT saat pertama dibuka hanya MASTER; sisanya ditambah lewat tombol + (dialog pilih channel) dan bisa dihapus (X).
// Channel baru selalu di paling kanan; tekan lama pada judul channel lalu geser = pindah urutan (animasi geser).
// FLOATING: tidak bisa menambah / menghapus channel dan tidak bisa mengatur frekuensi parametrik (dialog butuh jendela Activity,
// di jendela floating (service) dialog membuat aplikasi force close) - semua itu hanya di mode normal. Channel YOUTUBE tidak tampil di floating.
//
// SEMUA fader memakai skala yang sama: 0 dB tepat di TENGAH, mentok atas = batas atas, mentok bawah = batas bawah, garis skala
// tiap 1/8 panjang fader (9 angka rata). Dua jenis skala:
//   - VOLUME (MASTER, MIC, YOUTUBE, REVERB AUDIO): -inf .. 0 dB .. +6 dB (gain 0 .. 1x .. 2x), bagian bawah menurun cepat seperti fader asli.
//   - GAIN (EQ AUDIO, KOMPRESOR, EQ KANAN/KIRI, PARAMETRIK): linear, batas bawah .. 0 dB .. batas atas (+-12 / +-20 dB).
// Strip TIDAK punya mesin audio sendiri: ia papan kontrol untuk fungsi yang SUDAH ada (mic.volume, eqaudio.gain, comp.gain,
// eq31.l/r.gain, sysreverb.gain), jadi mengubah di sini = mengubah di panel klasik, skin, dan floating.
// Skala fader: satu garis per tingkat, kiri & kanan alur, TANPA angka (angka sering terpotong); tinggi strip 320 dp.
// Gaya = DTX (palet DtxPalette, kartu datar, judul warna TEMA, indikator hijau tetap, tombol 4dp).
// ---------------------------------------------------------------------------------------------------------------------

/** Keadaan mode CHANNEL (dipakai bersama layar utama & floating, tersimpan). */
object ChUi {
    private const val PREFS = "channel_mixer"

    var mode by mutableStateOf(false)
        private set

    /** Fader volume YouTube sebagai knob 0..1: gain = 2 x knob (0,5 = 0 dB, 1,0 = +6 dB) - skala sama dengan MIC. */
    var ytKnob by mutableStateOf(0.5f)
        private set

    /** Fader MASTER sebagai knob 0..1 (skala sama dengan MIC: 0,5 = 0 dB, 1,0 = volume sistem penuh = +6 dB). Halus; volume sistem dibulatkan ke langkahnya. */
    var masterLevel by mutableStateOf(0.5f)
        private set
    var masterMax by mutableIntStateOf(15)
        private set
    var masterIdx by mutableIntStateOf(0)
        private set
    var masterMuted by mutableStateOf(false)
        private set

    /** Kunci channel tetap yang bisa ditambah (parametrik dikelola terpisah oleh ChPeq). */
    const val K_MASTER = "master"
    const val K_MIC = "mic"
    const val K_YT = "yt"
    const val K_EQAUDIO = "eqaudio"
    const val K_REVERB = "reverb"
    const val K_COMP = "comp"
    const val K_EQR = "eqr"
    const val K_EQL = "eql"

    /** Semua channel tetap beserta namanya di dialog tambah. */
    val CATALOG: List<Pair<String, String>> = listOf(
        K_MIC to "MIC", K_YT to "YOUTUBE", K_EQAUDIO to "EQ AUDIO", K_REVERB to "REVERB",
        K_COMP to "KOMPRESOR", K_EQR to "EQ KANAN", K_EQL to "EQ KIRI"
    )

    /**
     * URUTAN channel yang tampil, kiri ke kanan. Isinya kunci channel tetap ("mic", ...) DAN channel parametrik ("p<id>").
     * Channel baru SELALU ditambahkan di paling kanan; urutan bisa diubah dengan tekan-lama lalu geser. DEFAULT: hanya MASTER.
     */
    var shown: List<String> by mutableStateOf(listOf(K_MASTER))
        private set

    private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saveShown(context: Context) {
        prefs(context).edit().putString("shown", shown.joinToString(",")).apply()
    }

    fun peqToken(id: Int): String = "p$id"

    fun isPeqToken(t: String): Boolean = t.length > 1 && t[0] == 'p' && t.substring(1).all { it.isDigit() }

    fun peqId(t: String): Int = t.substring(1).toIntOrNull() ?: -1

    /** Tambah ke paling kanan (kalau belum ada). */
    fun addToken(context: Context, token: String) {
        ensureLoaded(context)
        if (token in shown) return
        shown = shown + token
        saveShown(context)
    }

    fun addChannel(context: Context, key: String) {
        if (key == K_MASTER || CATALOG.none { it.first == key }) return
        addToken(context, key)
    }

    /** Hapus channel (kunci tetap atau "p<id>"). MASTER tidak bisa dihapus. */
    fun removeChannel(context: Context, token: String) {
        ensureLoaded(context)
        if (token == K_MASTER || token !in shown) return
        shown = shown.filter { it != token }
        saveShown(context)
    }

    /** Simpan urutan baru (hasil geser / sinkron dengan channel parametrik). */
    fun setOrder(context: Context, list: List<String>) {
        ensureLoaded(context)
        val n = list.distinct()
        if (n == shown) return
        shown = n
        saveShown(context)
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val p = prefs(context)
        mode = p.getBoolean("mode", false)
        ytKnob = p.getFloat("yt_knob", 0.5f).coerceIn(0f, 1f)
        val known = CATALOG.map { it.first }.toSet()
        val saved = (p.getString("shown", K_MASTER) ?: K_MASTER).split(',').filter { it in known || isPeqToken(it) }.distinct()
        shown = if (K_MASTER in saved) saved else listOf(K_MASTER) + saved
        loaded = true
        if (ytKnob != 0.5f) YtReverb.pushNow()   // volume tersimpan -> kirim ke pemutar
    }

    fun setMode(context: Context, on: Boolean) {
        ensureLoaded(context)
        mode = on
        prefs(context).edit().putBoolean("mode", on).apply()
        YtReverb.pushNow()   // halaman YouTube hanya mengukur level (meter) selama mode CHANNEL nyala
    }

    /** Gain linear volume YouTube (1 = 0 dB, maks 2 = +6 dB). Dikirim ke halaman lewat YtReverb. */
    fun ytGain(): Float = ytKnob * 2f

    fun setYtKnob(context: Context, v: Float) {
        ensureLoaded(context)
        ytKnob = v.coerceIn(0f, 1f)
        prefs(context).edit().putFloat("yt_knob", ytKnob).apply()
        YtReverb.pushNow()
    }

    // ---- MASTER = volume sistem (STREAM_MUSIC) ----

    private fun audio(context: Context): AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Baca volume sistem (tombol fisik volume bisa mengubahnya kapan saja) -> panggil berkala selama strip tampil. */
    fun refreshMaster(context: Context) {
        val am = audio(context) ?: return
        val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15).coerceAtLeast(1)
        val idx = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(masterIdx).coerceIn(0, max)
        masterMax = max
        masterIdx = idx
        if (Math.round(masterLevel * max) != idx) masterLevel = idx.toFloat() / max
        masterMuted = runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
    }

    fun setMasterKnob(context: Context, k: Float) {
        val am = audio(context) ?: return
        val x = k.coerceIn(0f, 1f)
        masterLevel = x
        val idx = Math.round(x * masterMax).coerceIn(0, masterMax)
        if (idx != masterIdx) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
            masterIdx = idx
        }
    }

    fun setMasterMuted(context: Context, mute: Boolean) {
        val am = audio(context) ?: return
        runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0) }
        refreshMaster(context)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// FADER: posisi 0..1 (bawah..atas) <-> dB. 0 dB SELALU di posisi 0,5.
// ---------------------------------------------------------------------------------------------------------------------

private class ChTaper(private val pos: FloatArray, private val db: FloatArray) {
    fun posToDb(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        if (x <= 0.005f) return Float.NEGATIVE_INFINITY
        for (i in 1 until pos.size) {
            if (x <= pos[i]) {
                val t = (x - pos[i - 1]) / (pos[i] - pos[i - 1])
                return db[i - 1] + (db[i] - db[i - 1]) * t
            }
        }
        return db[db.size - 1]
    }

    fun dbToPos(d: Float): Float {
        if (d <= db[0]) return 0f
        for (i in 1 until db.size) {
            if (d <= db[i]) {
                val t = (d - db[i - 1]) / (db[i] - db[i - 1])
                return pos[i - 1] + (pos[i] - pos[i - 1]) * t
            }
        }
        return 1f
    }
}

// VOLUME: knob 0..1 = gain 0..2x (0,5 = 0 dB, 1,0 = +6 dB). Dipakai MASTER, MIC, YOUTUBE, REVERB AUDIO.
private val ChVolTaper = ChTaper(
    floatArrayOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 1f),
    floatArrayOf(-60f, -24f, -12f, -6f, 0f, 6f)
)

// Skala fader: 9 garis angka rata, tiap 1/8 panjang fader, 0 dB tepat di tengah.
private val ChVolTicks = listOf(
    1f to "6", 0.875f to "4.5", 0.75f to "3", 0.625f to "1.5", 0.5f to "0",
    0.375f to "-6", 0.25f to "-12", 0.125f to "-24", 0f to "-inf"
)

private fun chTickLabel(db: Float): String {
    val r = Math.round(db * 10f) / 10f
    if (r == 0f) return "0"
    return if (r % 1f == 0f) r.toInt().toString() else String.format(Locale.US, "%.1f", r)
}

/** Skala GAIN linear: batas bawah .. 0 dB (tengah) .. batas atas, 9 garis rata. */
private fun chGainTicks(rangeDb: Float): List<Pair<Float, String>> = (0..8).map { i ->
    val pos = 1f - i / 8f
    pos to chTickLabel((pos - 0.5f) * 2f * rangeDb)
}

private fun chDbToGain(db: Float): Float = if (db.isInfinite() || db <= -59f) 0f else 10f.pow(db / 20f)

private fun chGainToDb(g: Float): Float = if (g <= 0.0005f) Float.NEGATIVE_INFINITY else 20f * log10(g)

private fun chFmtDb(db: Float): String =
    if (db.isInfinite()) "-inf" else String.format(Locale.US, "%+.1f dB", if (kotlin.math.abs(db) < 0.05f) 0f else db)

/** Knob 0..1 (gain = 2 x knob) <-> posisi fader volume. */
private fun volKnobFromPos(p: Float): Float = (chDbToGain(ChVolTaper.posToDb(p)) / 2f).coerceIn(0f, 1f)

private fun volPosFromKnob(k: Float): Float = ChVolTaper.dbToPos(chGainToDb(k.coerceIn(0f, 1f) * 2f))

private fun volDbFromKnob(k: Float): Float = chGainToDb(k.coerceIn(0f, 1f) * 2f)

// ---------------------------------------------------------------------------------------------------------------------
// INDIKATOR (meter). Skala sama untuk semua: 0..1 = -60 dB .. 0 dB di mesin (sama dengan meter MIC / OUT di mixer); di strip dilihat pada jendela -40..0 dB lalu dikalikan faktor fader.
//   - MASTER   : level campuran akhir (suara sistem + mic) sesudah volume sistem.
//   - MIC      : level mic sesudah gate & fader volume mic (dari mesin mic).
//   - YOUTUBE  : level pemutar YouTube sesudah fader volume YouTube (diukur di halaman).
//   - REVERB   : level keluaran reverb/delay sesudah fader gain reverb.
//   - EQ AUDIO / KOMPRESOR / EQ KANAN / EQ KIRI / PARAMETRIK: level program (YouTube bila mode YOUTUBE nyala, selain itu campuran akhir)
//     (hanya sebagai sumber irama; tinggi meter ditentukan faktor fader, lihat bawah).
// ---------------------------------------------------------------------------------------------------------------------

// METER STRIP = (posisi FADER) x (keaktifan MUSIK):
//   - keaktifan musik: level mesin (0..1 = -60..0 dB) dilihat pada jendela -40..0 dB. Di bawah -40 dB (musik berhenti / hanya derau) = 0,
//     jadi tanpa musik meter MATI (tidak ada lagi indikator menggantung).
//   - faktor fader: makin tinggi fader makin tinggi meter; fader mentok bawah (-inf / batas bawah) = 0 = meter tidak menyala.
//   Irama naik-turun tetap dari musik; tinggi maksimumnya mengikuti fader.
private const val CH_ACTIVITY_FLOOR_DB = 40f

/** Level mesin (0..1 = -60..0 dB) -> keaktifan 0..1 pada jendela -40..0 dB; di bawah -40 dB = 0. */
private fun chActivity(level: Float): Float =
    ((level * 60f - 60f + CH_ACTIVITY_FLOOR_DB) / CH_ACTIVITY_FLOOR_DB).coerceIn(0f, 1f)

/** Faktor tinggi meter dari posisi fader 0..1: 0 di dasar (meter mati), 1 di puncak, naik halus. */
private fun chFaderFactor(pos: Float): Float {
    val p = pos.coerceIn(0f, 1f)
    return if (p <= 0.02f) 0f else p.pow(0.6f)
}

private fun chProgram(ui: SkinUiState, ytOn: Boolean): Float =
    if (ytOn) (ui.meters["meter.yt"] ?: 0f) else (ui.meters["meter.output"] ?: 0f)

// ---------------------------------------------------------------------------------------------------------------------
// MODEL strip: apa yang ditampilkan & dikerjakan tiap channel. Dibangun dari state yang sudah ada.
// ---------------------------------------------------------------------------------------------------------------------

private data class StripModel(
    val title: String,
    val caption: String,
    val onCaption: (() -> Unit)?,       // PARAMETRIK: ketuk keterangan = ubah frekuensi
    val pos: Float,
    val display: String,
    val ticks: List<Pair<Float, String>>,
    val onFader: (Float) -> Unit,
    val onFaderReset: () -> Unit,
    val level: () -> Float,
    val powerOn: Boolean,
    val onPower: () -> Unit,
    val locked: Boolean = false,        // true = channel tidak bisa dipakai sekarang (diredupkan)
    val onRemove: (() -> Unit)? = null  // hanya channel parametrik yang bisa dihapus
)

@Composable
private fun chMasterModel(ctx: Context, ui: SkinUiState): StripModel {
    val k = if (ChUi.masterMax > 0) ChUi.masterIdx.toFloat() / ChUi.masterMax else 0f
    val muted = ChUi.masterMuted
    return StripModel(
        title = "MASTER", caption = "VOLUME SISTEM", onCaption = null,
        pos = volPosFromKnob(ChUi.masterLevel),
        display = if (muted) "MUTE" else chFmtDb(volDbFromKnob(k)),
        ticks = ChVolTicks,
        onFader = { ChUi.setMasterKnob(ctx, volKnobFromPos(it)) },
        onFaderReset = { ChUi.setMasterKnob(ctx, 0.5f) },
        level = { if (ChUi.masterMuted) 0f else chActivity(ui.meters["meter.output"] ?: 0f) },
        powerOn = !muted,
        onPower = { ChUi.setMasterMuted(ctx, !ChUi.masterMuted) }
    )
}

@Composable
private fun chMicModel(ui: SkinUiState, bridge: MixerBridge): StripModel {
    val vol = ui.values["mic.volume"] ?: 0.5f
    return StripModel(
        title = "MIC", caption = "VOLUME", onCaption = null,
        pos = volPosFromKnob(vol),
        display = chFmtDb(volDbFromKnob(vol)),
        ticks = ChVolTicks,
        onFader = { bridge.setValue("mic.volume", volKnobFromPos(it)) },
        onFaderReset = { bridge.resetValues(listOf("mic.volume")) },
        level = { chActivity(ui.meters["meter.mic"] ?: 0f) },
        powerOn = ui.toggles["mic.toggle"] == true,
        onPower = { bridge.setToggle("mic.toggle", ui.toggles["mic.toggle"] != true) }
    )
}

@Composable
private fun chYoutubeModel(ctx: Context, ui: SkinUiState): StripModel {
    val ytOn = YtUi.mode
    val k = ChUi.ytKnob
    return StripModel(
        title = "YOUTUBE", caption = "VOLUME", onCaption = null,
        pos = volPosFromKnob(k),
        display = chFmtDb(volDbFromKnob(k)),
        ticks = ChVolTicks,
        onFader = { ChUi.setYtKnob(ctx, volKnobFromPos(it)) },
        onFaderReset = { ChUi.setYtKnob(ctx, 0.5f) },
        level = { if (YtUi.mode) chActivity(ui.meters["meter.yt"] ?: 0f) else 0f },
        powerOn = ytOn,
        onPower = { YtUi.setMode(ctx, !YtUi.mode) }
    )
}

@Composable
private fun chEqAudioModel(ui: SkinUiState, bridge: MixerBridge): StripModel {
    val g = ui.values["eqaudio.gain"] ?: 0.5f
    val db = GlobalEqEngine.gainKnobToDb(g)          // +-12 dB, 0,5 = 0 dB
    val on = ui.toggles["eqaudio.toggle"] == true
    return StripModel(
        title = "EQ AUDIO", caption = "GAIN", onCaption = null,
        pos = g.coerceIn(0f, 1f),
        display = chFmtDb(db),
        ticks = chGainTicks(12f),
        onFader = { bridge.setValue("eqaudio.gain", it) },
        onFaderReset = { bridge.resetValues(listOf("eqaudio.gain")) },
        level = { chActivity(chProgram(ui, YtUi.mode)) },
        powerOn = on,
        onPower = { bridge.setToggle("eqaudio.toggle", !on) }
    )
}

@Composable
private fun chReverbModel(ctx: Context, ui: SkinUiState, onSysReverbToggle: () -> Unit): StripModel {
    val s = SysReverbUi.s
    val running by YtReverb.running.collectAsState()
    val ytOn = YtUi.mode
    val d = SysReverbSettings()
    return StripModel(
        title = "REVERB", caption = if (ytOn) "GAIN" else "BUTUH YT", onCaption = null,
        pos = volPosFromKnob(s.gain),
        display = chFmtDb(volDbFromKnob(s.gain)),
        ticks = ChVolTicks,
        onFader = { p: Float -> if (YtUi.mode) SysReverbUi.update(ctx) { it.copy(gain = volKnobFromPos(p)) } },
        onFaderReset = { if (YtUi.mode) SysReverbUi.update(ctx) { it.copy(gain = d.gain) } },
        level = { if (YtUi.mode && YtReverb.running.value) chActivity(ui.meters["meter.sysreverb"] ?: 0f) else 0f },
        powerOn = ytOn && running,
        onPower = {
            if (YtUi.mode) onSysReverbToggle()
            else Toast.makeText(ctx, "REVERB AUDIO hanya bisa dipakai saat mode YOUTUBE nyala", Toast.LENGTH_SHORT).show()
        },
        locked = !ytOn
    )
}

@Composable
private fun chCompModel(ctx: Context, ui: SkinUiState, bridge: MixerBridge): StripModel {
    val k = ui.values[Comp.GAIN] ?: Comp.DEFAULTS.getValue(Comp.GAIN)
    val db = Comp.gainDb(k)                           // +-20 dB, 0,5 = 0 dB
    val on = ui.toggles[Comp.TOGGLE] == true
    return StripModel(
        title = "KOMPRESOR", caption = "GAIN", onCaption = null,
        pos = k.coerceIn(0f, 1f),
        display = chFmtDb(db),
        ticks = chGainTicks(Comp.GAIN_RANGE_DB),
        onFader = { bridge.setValue(Comp.GAIN, it) },
        onFaderReset = { bridge.resetValues(listOf(Comp.GAIN)) },
        level = { chActivity(chProgram(ui, YtUi.mode)) },
        powerOn = on,
        // ON = nyalakan efek DAN tampilkan bagian KOMPRESOR di bawah + tombol KOMPRESOR di navigasi jadi aktif (CompUi.setMode melakukan ketiganya).
        onPower = { if (on) bridge.setToggle(Comp.TOGGLE, false) else CompUi.setMode(ctx, true) }
    )
}

@Composable
private fun chEqSideModel(ctx: Context, side: Int, ui: SkinUiState, bridge: MixerBridge): StripModel {
    val fn = Eq31.gainFn(side)
    val k = ui.values[fn] ?: 0.5f
    val db = Eq31.knobToDb(k)                         // +-12 dB, 0,5 = 0 dB
    val on = ui.toggles[Eq31.TOGGLE] == true
    return StripModel(
        title = if (side == Eq31.RIGHT) "EQ KANAN" else "EQ KIRI", caption = "GAIN", onCaption = null,
        pos = k.coerceIn(0f, 1f),
        display = chFmtDb(db),
        ticks = chGainTicks(Eq31.RANGE_DB),
        onFader = { bridge.setValue(fn, it) },
        onFaderReset = { bridge.resetValues(listOf(fn)) },
        level = { chActivity(chProgram(ui, YtUi.mode)) },
        powerOn = on,
        // ON = nyalakan efek DAN tampilkan bagian EQUALIZER di bawah + tombol EQ di navigasi jadi aktif (Eq31Ui.setMode melakukan ketiganya).
        onPower = { if (on) bridge.setToggle(Eq31.TOGGLE, false) else Eq31Ui.setMode(ctx, true) }
    )
}

@Composable
private fun chPeqModel(ctx: Context, b: ChPeqBand, ui: SkinUiState, onEditFreq: ((Int) -> Unit)?): StripModel {
    val id = b.id
    return StripModel(
        title = "PARAMETRIK", caption = Fx.hzText(b.hz),
        onCaption = if (onEditFreq != null) ({ onEditFreq(id) }) else null,   // floating: null = frekuensi hanya tampil, tidak bisa diubah
        pos = (b.db / (2f * ChPeq.RANGE_DB) + 0.5f).coerceIn(0f, 1f),
        display = chFmtDb(b.db),
        ticks = chGainTicks(ChPeq.RANGE_DB),
        onFader = { ChPeq.setDb(ctx, id, (it - 0.5f) * 2f * ChPeq.RANGE_DB) },
        onFaderReset = { ChPeq.setDb(ctx, id, 0f) },
        level = { chActivity(chProgram(ui, YtUi.mode)) },
        powerOn = b.on,
        onPower = { ChPeq.setOn(ctx, id, !b.on) }
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// KOMPONEN
// ---------------------------------------------------------------------------------------------------------------------

private val ChCardBg = Color(0xFF2F2F32)
private val ChTileShape = RoundedCornerShape(8.dp)
private val ChStripWidth = 88.dp
private const val CH_BODY_DP = 320   // tinggi deretan strip (dulu 440: terlalu panjang)

private fun chBg(c: Color, opacity: Float): Color = c.copy(alpha = c.alpha * opacity.coerceIn(0f, 1f))

/** Tombol ON gaya iOS: penuh warna TEMA saat nyala, abu (SurfaceRaised) saat mati. Sudut 9 dp. */
@Composable
private fun ChButton(label: String, on: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val latest by rememberUpdatedState(onTap)
    Box(
        modifier = modifier
            .background(if (on) ThemeState.accentColor else SurfaceRaised, ButtonShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { latest() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (on) ThemeState.accentTextColor else Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1
        )
    }
}

/**
 * Fader vertikal model mixer fisik: kenop persegi bergaris-garis horizontal dengan sisi sedikit melengkung ke dalam (pinggang),
 * bayangan di kanan, alur gelap tipis, dan skala dB di kedua sisi (). Warna kenop mengikuti TEMA.
 * Seret naik/turun (relatif, tidak loncat), ketuk dua kali = nilai awal.
 */
@Composable
private fun ChannelFader(
    pos: Float,
    ticks: List<Pair<Float, String>>,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    var active by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onChange)
    val latestReset by rememberUpdatedState(onReset)
    val latestPos by rememberUpdatedState(pos.coerceIn(0f, 1f))
    val accent = ThemeState.accentColor
    val thumbH = 52.dp
    val capW = 28.dp
    BoxWithConstraints(
        modifier = modifier
            .background(chBg(ChCardBg, 1f), ChTileShape)
            .pointerInput(Unit) {
                var lastTapUp = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    active = true
                    val travel = (size.height - thumbH.toPx()).coerceAtLeast(1f)
                    val slop = viewConfiguration.touchSlop
                    var raw = latestPos
                    var lastY = down.position.y
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (!moved && ch.uptimeMillis - down.uptimeMillis < 250L) {
                                if (down.uptimeMillis - lastTapUp < 350L) {
                                    latestReset()
                                    lastTapUp = 0L
                                } else {
                                    lastTapUp = ch.uptimeMillis
                                }
                            }
                            break
                        }
                        if ((ch.position - down.position).getDistance() > slop) moved = true
                        val dy = ch.position.y - lastY
                        lastY = ch.position.y
                        if (moved) {
                            raw = (raw - dy / travel).coerceIn(0f, 1f)
                            latest(raw)
                        }
                        ch.consume()
                    }
                    active = false
                }
            }
    ) {
        val cxFrac = 0.5f   // tanpa angka: alur & kenop di tengah kartu fader
        Canvas(Modifier.fillMaxSize()) {
            val thumbPx = thumbH.toPx()
            val travelPx = size.height - thumbPx
            val cx = size.width * cxFrac
            val p = pos.coerceIn(0f, 1f)
            val ty = thumbPx / 2f + travelPx * (1f - p)
            val top = thumbPx / 2f
            fun yOf(tp: Float) = top + travelPx * (1f - tp)

            // --- Alur (slot): batang gelap membulat, memanjang melewati jangkauan kenop, sorot silinder tipis ---
            val slotW = 7.dp.toPx()
            val slotTop = 2.dp.toPx()
            val slotH = size.height - 4.dp.toPx()
            drawRoundRect(
                Brush.horizontalGradient(
                    listOf(Color(0xFF121213), Color(0xFF3F3F42), Color(0xFF19191A)),
                    startX = cx - slotW / 2f, endX = cx + slotW / 2f
                ),
                Offset(cx - slotW / 2f, slotTop), Size(slotW, slotH), CornerRadius(slotW / 2f)
            )

            // --- Skala: satu garis per tingkat (tanpa garis kecil dan tanpa angka), kiri & kanan alur. Garis 0 dB lebih terang & tebal. ---
            val scaleColor = TextDim.copy(alpha = 0.7f)
            val zeroPos = ticks.firstOrNull { it.second == "0" }?.first
            val gapL = 9.dp.toPx()           // jarak garis skala dari tengah alur
            val tickLen = 6.dp.toPx()
            for ((tp, _) in ticks) {
                val y = yOf(tp)
                val isZero = zeroPos != null && tp == zeroPos
                val len = if (isZero) tickLen + 2.dp.toPx() else tickLen
                val col = if (isZero) Color(0xFFE6E6EA) else scaleColor
                val sw = if (isZero) 1.8.dp.toPx() else 1.dp.toPx()
                drawLine(col, Offset(cx - gapL - len, y), Offset(cx - gapL, y), strokeWidth = sw)
                drawLine(col, Offset(cx + gapL, y), Offset(cx + gapL + len, y), strokeWidth = sw)
            }
            // --- Kenop (meniru fader fisik referensi): badan PERSEGI PANJANG lurus, tinggi:lebar sekitar 2:1 ---
            //   bibir polos di atas -> pita-pita yang nadanya naik bertahap (gelap di atas, terang di bawah) dipisah garis tipis
            //   -> satu garis terang tebal tepat di tengah -> bibir gelap membulat di bawah.
            //   "Pinggang" yang tampak di gambar adalah BAYANGAN di kanan (cekung di tengah), bukan bentuk kenop.
            val w = capW.toPx()
            val left = cx - w / 2f
            val right = left + w
            val capTop = ty - thumbPx / 2f
            val capBot = ty + thumbPx / 2f
            val h = capBot - capTop
            val capShape = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left, capTop, right, capBot,
                        topLeftCornerRadius = CornerRadius(2.dp.toPx()), topRightCornerRadius = CornerRadius(2.dp.toPx()),
                        bottomLeftCornerRadius = CornerRadius(4.dp.toPx()), bottomRightCornerRadius = CornerRadius(4.dp.toPx())
                    )
                )
            }

            // Bayangan kanan: lembut, lebar di atas & bawah, menyempit di tengah (bentuk dasi kupu-kupu)
            fun shadow(sh: Float, a: Float) {
                val sp = Path().apply {
                    moveTo(right - 1f, capTop + h * 0.05f)
                    lineTo(right + sh, capTop + h * 0.10f)
                    quadraticBezierTo(right - sh * 0.45f, ty, right + sh, capBot - h * 0.02f)
                    lineTo(right - 1f, capBot)
                    close()
                }
                drawPath(sp, Brush.horizontalGradient(listOf(Color.Black.copy(alpha = a), Color.Transparent), startX = right, endX = right + sh))
            }
            shadow(13.dp.toPx(), 0.30f)
            shadow(8.dp.toPx(), 0.45f)

            // Nada abu referensi (0 = hitam, 1 = putih) dipetakan ke WARNA TEMA: 0.5 = warna tema persis.
            fun tone(t: Float): Color {
                val k = (t * 1.1f + (if (active) 0.06f else 0f)).coerceIn(0f, 1f)
                return if (k < 0.5f) lerp(Color.Black, accent, k * 2f) else lerp(accent, Color.White, (k - 0.5f) * 2f)
            }
            // (awal, akhir, nada atas, nada bawah) sebagai pecahan tinggi kenop; hasil ukur dari gambar referensi
            val bands = listOf(
                floatArrayOf(0.000f, 0.150f, 0.52f, 0.42f),   // bibir atas polos
                floatArrayOf(0.150f, 0.200f, 0.00f, 0.02f),   // hitam
                floatArrayOf(0.200f, 0.205f, 0.58f, 0.58f),   // pemisah terang
                floatArrayOf(0.205f, 0.290f, 0.10f, 0.14f),
                floatArrayOf(0.290f, 0.295f, 0.62f, 0.62f),
                floatArrayOf(0.295f, 0.390f, 0.17f, 0.21f),
                floatArrayOf(0.390f, 0.400f, 0.66f, 0.66f),
                floatArrayOf(0.400f, 0.485f, 0.27f, 0.31f),
                floatArrayOf(0.488f, 0.515f, 0.92f, 0.88f),   // garis terang tebal di tengah
                floatArrayOf(0.520f, 0.605f, 0.35f, 0.39f),
                floatArrayOf(0.605f, 0.617f, 0.03f, 0.03f),   // pemisah gelap
                floatArrayOf(0.617f, 0.700f, 0.43f, 0.47f),
                floatArrayOf(0.700f, 0.710f, 0.03f, 0.03f),
                floatArrayOf(0.710f, 0.800f, 0.53f, 0.57f),
                floatArrayOf(0.800f, 0.815f, 0.03f, 0.03f),
                floatArrayOf(0.815f, 0.870f, 0.61f, 0.65f),   // pita paling terang
                floatArrayOf(0.870f, 1.000f, 0.02f, 0.16f)    // bibir bawah gelap
            )
            clipPath(capShape) {
                drawRect(tone(0.03f), Offset(left, capTop), Size(w, h))
                for (b in bands) {
                    val y0 = capTop + h * b[0]
                    val y1 = capTop + h * b[1]
                    drawRect(
                        Brush.verticalGradient(listOf(tone(b[2]), tone(b[3])), startY = y0, endY = y1),
                        Offset(left, y0), Size(w, (y1 - y0) + 0.5f)
                    )
                }
                // tepi kiri-kanan sedikit lebih gelap (kesan volume), kilau tipis di tepi atas
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent), startX = left, endX = left + w * 0.07f),
                    Offset(left, capTop), Size(w, h)
                )
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)), startX = right - w * 0.07f, endX = right),
                    Offset(left, capTop), Size(w, h)
                )
                drawRect(Color.White.copy(alpha = 0.30f), Offset(left, capTop), Size(w, 0.8.dp.toPx()))
            }
            drawPath(capShape, Color.Black.copy(alpha = 0.55f), style = Stroke(width = 0.7.dp.toPx()))
        }
    }
}

@Composable
private fun ChannelStrip(m: StripModel, opacity: Float, modifier: Modifier = Modifier, dragHandle: Modifier = Modifier) {
    val accent = ThemeState.accentColor
    val latestRemove by rememberUpdatedState(m.onRemove)
    val latestCaption by rememberUpdatedState(m.onCaption)
    Column(
        modifier = modifier
            .width(ChStripWidth)
            .alpha(if (m.locked) 0.5f else 1f)
            .background(chBg(SurfaceColor, opacity), CardShape)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Judul channel (+ hapus, hanya channel parametrik)
        Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            // Judul = pegangan geser urutan (tekan lama lalu geser).
            Row(Modifier.weight(1f).fillMaxHeight().then(dragHandle), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sentenceCase(m.title), color = LabelPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp).weight(1f)
                )
            }
            if (m.onRemove != null) {
                Box(
                    Modifier.size(24.dp).pointerInput(Unit) { detectTapGestures(onTap = { latestRemove?.invoke() }) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Hapus channel", tint = TextDim, modifier = Modifier.size(16.dp))
                }
            }
        }
        // Keterangan (jenis fader). Parametrik: tombol frekuensi, ketuk = ubah.
        if (m.onCaption != null) {
            ChButton(m.caption, false, Modifier.fillMaxWidth().height(24.dp)) { latestCaption?.invoke() }
        } else {
            Box(Modifier.fillMaxWidth().height(24.dp).then(dragHandle), contentAlignment = Alignment.Center) {
                Text(sentenceCase(m.caption), color = LabelSecondary, fontWeight = FontWeight.Normal, fontSize = 10.sp, maxLines = 1)
            }
        }
        // FADER + METER
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChannelFader(
                m.pos, m.ticks,
                Modifier.weight(1f).fillMaxHeight().background(FaderWell, RoundedCornerShape(10.dp)),
                m.onFader, m.onFaderReset
            )
            MeterTile("", { chFaderFactor(m.pos) * m.level() }, opacity, Modifier.width(17.dp).fillMaxHeight())
        }
        Text(
            m.display, color = ValueColorCh, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        // Tombol ON per channel
        ChButton("ON", m.powerOn, Modifier.fillMaxWidth().height(30.dp), m.onPower)  // area ketuk diperluas lewat padding strip
    }
}

private val ValueColorCh = Color(0xFFFFFFFF)

/** Tile "+" di ujung deretan strip: tambah channel (hanya mode normal). */
@Composable
private fun AddChannelTile(opacity: Float, onTap: () -> Unit) {
    val latest by rememberUpdatedState(onTap)
    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(chBg(SurfaceColor, opacity), CardShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { latest() }) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Tambah channel", tint = ThemeState.accentColor, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text("Tambah", color = LabelSecondary, fontWeight = FontWeight.Medium, fontSize = 10.sp, maxLines = 1)
    }
}

private val PeqPresetHz = listOf(60f, 100f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 12000f)

/** Tombol pilihan di dalam dialog: gaya sama dengan tombol dialog Warna Tema (tombol Material biasa, warna TEMA saat terpilih). */
@Composable
private fun DlgChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = ThemeState.accentColor
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = ButtonShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) accent else SurfaceRaised,
            contentColor = if (selected) bestTextColorFor(accent) else LabelPrimary
        )
    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

/**
 * Pilih frekuensi channel parametrik: geser (skala log 20 Hz - 20 kHz) atau pilih angka siap pakai.
 * Gayanya SAMA dengan dialog Warna Tema (ColorPickerDialog): kartu PanelColor, judul PadTextColor tebal, tombol Material biasa,
 * tombol utama berwarna TEMA. HANYA dipanggil dari mode normal (di floating dialog membuat aplikasi force close).
 */
@Composable
private fun PeqFreqDialog(isNew: Boolean, initialHz: Float, onConfirm: (Float) -> Unit, onDismiss: () -> Unit) {
    val accent = ThemeState.accentColor
    var knob by remember { mutableStateOf(Fx.hzToKnob(initialHz)) }
    val hz = ChPeq.niceHz(Fx.hzOf(knob))
    AppDialog(
        title = if (isNew) "Frekuensi parametrik" else "Ubah frekuensi",
        onDismiss = onDismiss,
        buttons = {
            DialogButton("Batal", onDismiss)
            DialogButton(
                if (isNew) "Tambah" else "Simpan", { onConfirm(hz) },
                container = accent, contentColor = bestTextColorFor(accent)
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pilih frekuensi yang dinaikkan / diturunkan oleh fader channel ini.", color = TextDim, fontSize = 12.sp)
            Text(
                Fx.hzText(hz), color = accent, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Slider(
                value = knob,
                onValueChange = { knob = it },
                colors = SliderDefaults.colors(
                    thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = KnobTrack
                )
            )
            for (row in PeqPresetHz.chunked(3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in row) {
                        DlgChip(Fx.hzText(f), hz == f, Modifier.weight(1f)) { knob = Fx.hzToKnob(f) }
                    }
                }
            }
        }
    }
}

/** Pilih channel yang mau ditambahkan (hanya yang belum tampil) + PARAMETRIK. Hanya mode normal. */
@Composable
private fun AddChannelDialog(
    available: List<Pair<String, String>>,
    peqFull: Boolean,
    onPickFixed: (String) -> Unit,
    onPickPeq: () -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        title = "Tambah channel",
        onDismiss = onDismiss,
        buttons = { DialogButton("Batal", onDismiss) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pilih channel yang mau ditampilkan.", color = TextDim, fontSize = 12.sp)
            val items = ArrayList<Pair<String, () -> Unit>>()
            for ((k, name) in available) items.add(name to { onPickFixed(k) })
            items.add((if (peqFull) "PARAMETRIK (penuh)" else "PARAMETRIK") to { if (!peqFull) onPickPeq() })
            for (row in items.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, act) in row) DlgChip(label, false, Modifier.weight(1f), act)
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Keadaan geser-urutan: satu channel diangkat (tekan lama), digeser, lalu dilepas di slot baru dengan animasi. */
private class ChReorder {
    var dragKey by mutableStateOf<String?>(null)
    var from by mutableIntStateOf(-1)
    var dragX by mutableFloatStateOf(0f)           // geser kenop channel yang diangkat (px, relatif ke slot awalnya)
    var settleKey by mutableStateOf<String?>(null)  // channel yang sedang meluncur ke slot barunya
    val settleX = Animatable(0f)
    var slotPx = 1f
    var count = 0
    var viewportW = 0f

    fun targetIdx(): Int =
        if (dragKey == null || count <= 0) -1 else (from + (dragX / slotPx).roundToInt()).coerceIn(0, count - 1)
}

/**
 * Bagian MIXER CHANNEL (tampil di ATAS mixer saat mode CHANNEL nyala). Dipakai sama oleh layar utama dan floating.
 * Default hanya MASTER; channel lain ditambah lewat tombol + (dialog pilih channel) dan dihapus lewat X. Channel baru SELALU masuk
 * di paling kanan. Tekan lama pada judul channel lalu geser kiri / kanan = memindah urutannya (channel lain bergeser menyingkir
 * dengan animasi, lepas = meluncur ke slot baru). Deretan strip bisa digulir ke samping bila tidak muat.
 *
 * [floating] = true (jendela floating): TIDAK bisa tambah / hapus / pindah channel, TIDAK bisa ubah frekuensi parametrik (dialog di jendela
 * service = force close), dan channel YOUTUBE tidak ditampilkan. Semua itu hanya di mode normal.
 */
@Composable
fun ChannelSection(
    ui: SkinUiState,
    bridge: MixerBridge,
    opacity: Float,
    onSysReverbToggle: () -> Unit,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    /** Tinggi deretan strip. Layar Channel (AppShell) mengisi tinggi layar; floating memakai bawaan. */
    bodyHeight: androidx.compose.ui.unit.Dp = CH_BODY_DP.dp
) {
    val ctx = LocalContext.current.applicationContext
    remember {
        ChUi.ensureLoaded(ctx); ChPeq.ensureLoaded(ctx); SysReverbUi.ensureLoaded(ctx)
        Eq31Ui.ensureLoaded(ctx); CompUi.ensureLoaded(ctx); YtUi.ensureLoaded(ctx)
        true
    }
    // MASTER mengikuti volume sistem (tombol fisik volume juga mengubahnya).
    LaunchedEffect(Unit) {
        while (true) {
            ChUi.refreshMaster(ctx)
            delay(350)
        }
    }
    val peq = ChPeq.bands
    // Urutan tampil = urutan tersimpan, dirapikan: parametrik yang sudah tidak ada dibuang, parametrik yang belum tercatat ditaruh di paling kanan.
    val display: List<String> = run {
        val ids = peq.map { it.id }.toSet()
        val cleaned = ChUi.shown.filter { !ChUi.isPeqToken(it) || ChUi.peqId(it) in ids }
        cleaned + peq.map { ChUi.peqToken(it.id) }.filter { it !in cleaned }
    }
    LaunchedEffect(display) { if (display != ChUi.shown) ChUi.setOrder(ctx, display) }
    // Penangan gestur dibuat sekali per channel (pointerInput berkunci k) -> harus membaca daftar TERBARU, bukan salinan lama.
    val displayState = rememberUpdatedState(display)

    var chooseOpen by remember { mutableStateOf(false) }
    var freqId by remember { mutableStateOf(-1) }   // -1 = tutup, 0 = tambah parametrik baru, >0 = ubah frekuensi channel itu
    val canEdit = !floating

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val rowScroll = rememberScrollState()
    val st = remember { ChReorder() }
    st.slotPx = with(density) { (ChStripWidth + 6.dp).toPx() }
    // Daftar yang dipakai hitungan geser = daftar yang TAMPIL (tanpa channel YouTube di floating; di floating tidak ada geser).
    st.count = display.size
    val target by remember { derivedStateOf { st.targetIdx() } }

    // Geser di tepi: jari menahan channel dekat ujung -> deretan ikut menggulir.
    LaunchedEffect(st.dragKey) {
        val edge = with(density) { 36.dp.toPx() }
        val itemW = with(density) { ChStripWidth.toPx() }
        while (st.dragKey != null) {
            val left = st.from * st.slotPx + st.dragX - rowScroll.value
            val v = when {
                left < edge -> -14f
                left + itemW > st.viewportW - edge -> 14f
                else -> 0f
            }
            if (v != 0f) st.dragX += rowScroll.scrollBy(v)
            delay(16)
        }
    }

    fun endDrag() {
        val k = st.dragKey ?: return
        val from = st.from
        val t = st.targetIdx().coerceAtLeast(0)
        val startX = st.dragX - (t - from) * st.slotPx    // posisi kenop terhadap slot BARU (layout berpindah sejauh (t-from)*slot)
        val newList = displayState.value.toMutableList().apply { removeAt(from); add(t, k) }
        scope.launch {
            st.settleX.snapTo(startX)
            st.settleKey = k
            st.dragKey = null
            st.dragX = 0f
            ChUi.setOrder(ctx, newList)
            st.settleX.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
            st.settleKey = null
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(CardGap)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(bodyHeight)
                .onSizeChanged { st.viewportW = it.width.toFloat() }
                .horizontalScroll(rowScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            display.forEachIndexed { idx, k ->
                // floating: tanpa channel YouTube. Tanpa early-return di dalam lambda inline
                // (non-local return memicu D8 "$$$$$NON_LOCAL_RETURN$$$$$" di Kotlin 2.2).
                val rawModel: StripModel? = if (floating && k == ChUi.K_YT) null else when {
                        ChUi.isPeqToken(k) -> {
                            val b = peq.firstOrNull { it.id == ChUi.peqId(k) }
                            if (b == null) null else chPeqModel(ctx, b, ui, if (canEdit) ({ id -> freqId = id }) else null)
                        }
                        k == ChUi.K_MASTER -> chMasterModel(ctx, ui)
                        k == ChUi.K_MIC -> chMicModel(ui, bridge)
                        k == ChUi.K_YT -> chYoutubeModel(ctx, ui)
                        k == ChUi.K_EQAUDIO -> chEqAudioModel(ui, bridge)
                        k == ChUi.K_REVERB -> chReverbModel(ctx, ui, onSysReverbToggle)
                        k == ChUi.K_COMP -> chCompModel(ctx, ui, bridge)
                        k == ChUi.K_EQR -> chEqSideModel(ctx, Eq31.RIGHT, ui, bridge)
                        else -> chEqSideModel(ctx, Eq31.LEFT, ui, bridge)
                    }
                if (rawModel != null) key(k) {
                    val model: StripModel = rawModel
                    val removable = canEdit && k != ChUi.K_MASTER
                    val shown = if (removable) {
                        model.copy(onRemove = {
                            ChUi.removeChannel(ctx, k)
                            if (ChUi.isPeqToken(k)) ChPeq.remove(ctx, ChUi.peqId(k))
                        })
                    } else model

                    // ---- animasi geser ----
                    val isDrag = st.dragKey == k
                    val isSettle = st.settleKey == k
                    val from = st.from
                    val shiftTarget = when {
                        st.dragKey == null || isDrag -> 0f
                        target > from && idx in (from + 1)..target -> -st.slotPx    // channel di antara menyingkir ke kiri
                        target < from && idx in target until from -> st.slotPx      // ... atau ke kanan
                        else -> 0f
                    }
                    val shift by animateFloatAsState(
                        shiftTarget,
                        if (st.dragKey != null) tween(190, easing = FastOutSlowInEasing) else snap(),   // lepas: layout sudah pindah, jadi langsung 0
                        label = "chShift"
                    )
                    val lift by animateFloatAsState(if (isDrag) 1.06f else 1f, tween(140), label = "chLift")

                    val handle = if (!canEdit) Modifier else Modifier.pointerInput(k) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (st.dragKey == null && st.settleKey == null) {
                                    st.from = displayState.value.indexOf(k)
                                    st.dragX = 0f
                                    st.dragKey = k
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onDrag = { change, amount ->
                                // kenop diperbesar 1,06x -> gerak jari yang terbaca di dalamnya 1/1,06; dikembalikan supaya tepat di bawah jari
                                if (st.dragKey == k) { change.consume(); st.dragX += amount.x * 1.06f }
                            },
                            onDragEnd = { if (st.dragKey == k) endDrag() },
                            onDragCancel = { if (st.dragKey == k) endDrag() }
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxHeight()
                            .zIndex(if (isDrag || isSettle) 1f else 0f)
                            .graphicsLayer {
                                translationX = when {
                                    isDrag -> st.dragX
                                    isSettle -> st.settleX.value
                                    else -> shift
                                }
                                scaleX = lift; scaleY = lift
                                alpha = if (isDrag) 0.94f else 1f
                            }
                    ) {
                        ChannelStrip(shown, opacity, Modifier.fillMaxHeight(), handle)
                    }
                }
            }
            if (canEdit) {
                AddChannelTile(opacity) { chooseOpen = true }
            }
        }
    }
    if (canEdit && chooseOpen) {
        AddChannelDialog(
            available = ChUi.CATALOG.filter { it.first !in display },
            peqFull = peq.size >= ChPeq.MAX,
            onPickFixed = { ChUi.addChannel(ctx, it); chooseOpen = false },
            onPickPeq = { chooseOpen = false; freqId = 0 },
            onDismiss = { chooseOpen = false }
        )
    }
    if (canEdit && freqId >= 0) {
        val editing = peq.firstOrNull { it.id == freqId }
        PeqFreqDialog(
            isNew = freqId == 0,
            initialHz = editing?.hz ?: 1000f,
            onConfirm = { hz ->
                if (freqId == 0) {
                    val id = ChPeq.add(ctx, hz)
                    if (id < 0) Toast.makeText(ctx, "Channel parametrik maksimal ${ChPeq.MAX}", Toast.LENGTH_SHORT).show()
                    else ChUi.addToken(ctx, ChUi.peqToken(id))   // paling kanan
                } else ChPeq.setHz(ctx, freqId, hz)
                freqId = -1
            },
            onDismiss = { freqId = -1 }
        )
    }
}

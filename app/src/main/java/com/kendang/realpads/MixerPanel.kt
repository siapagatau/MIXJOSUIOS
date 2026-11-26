package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kendang.realpads.skin.MixerBridge
import com.kendang.realpads.skin.SkinUiState

// ---------------------------------------------------------------------------------------------------------------------
// PANEL MIXER TUNGGAL. Layar utama DAN panel floating memanggil MixerGrid() yang sama -> kartu, urutan, warna, ukuran
// huruf, dan perilaku sentuh IDENTIK. Satu-satunya perbedaan: susunan menyesuaikan bentuk ruang yang tersedia
//   - LEBAR (layar utama landscape / floating dilebarkan): dua bagian berdampingan, semua muat tanpa gulir.
//   - TEGAK (floating sempit): dua bagian ditumpuk, 3 knob per baris, digulir ke bawah.
// Bagian ketiga REVERB AUDIO (SysReverbPanel.kt) memakai komponen yang sama: pita di bawah (lebar) / kartu bertumpuk (tegak).
// Bagian keempat (mode equalizer) = EQUALIZER 31 BAND (EqualizerPanel.kt), selalu paling bawah, menyatu dalam gulir yang sama.
// Isi kartu diambil dari MixGroups (MainActivity.kt) -> menambah kontrol baru cukup di sana, kedua tampilan ikut.
//
// Peta kolom (satu kolom = satu knob):
//   LEBAR  MIKROFON (8 kolom): [MIC 2][REVERB 6]  /  [ECHO 3][EQ MIC 3][LEVEL 2]      EQUALIZER AUDIO (3 kolom): [GAIN SUB LOW] / [MID HIGH OUT]
//   TEGAK  MIKROFON (3 kolom): [MIC 2][LEVEL 1] / [REVERB 3+3] / [ECHO 3] / [EQ MIC 3]  EQUALIZER AUDIO (3 kolom): sama dengan LEBAR
// ---------------------------------------------------------------------------------------------------------------------

internal const val KNOB_ROW_DP = 84        // tinggi satu baris knob pada susunan tegak
private const val WIDE_MIN_WIDTH_DP = 640 // lebih sempit dari ini -> susunan tegak
private const val WIDE_MIN_HEIGHT_DP = 260 // lebih pendek dari ini (susunan lebar) -> digulir, knob tidak menciut lagi

private fun groupOf(toggleFn: String): MixGroup = MixGroups.first { it.toggleFn == toggleFn }

// tinggi kartu = padding 8 + judul 30 + jarak 4 + baris knob (+ jarak antar baris 4)
internal fun cardHeight(rows: Int): Dp = (20 + 26 + 4 + rows * KNOB_ROW_DP + (rows - 1) * 4).dp

@Composable
private fun MixKnob(k: MixControl, groupOn: Boolean, ui: SkinUiState, bridge: MixerBridge, opacity: Float, modifier: Modifier) {
    val v = ui.values[k.fn] ?: 0f
    KnobTile(
        k.label, v, formatMixValue(k.fn, v), opacity, modifier,
        bipolar = k.bipolar,
        dim = !groupOn,
        onReset = { bridge.resetValues(listOf(k.fn)) }
    ) { bridge.setValue(k.fn, it) }
}

/** Kartu satu kelompok dengan saklar ON/OFF sendiri. perRow = jumlah knob per baris (lebih banyak dari itu -> baris baru). */
@Composable
private fun MixGroupCard(
    g: MixGroup,
    ui: SkinUiState,
    bridge: MixerBridge,
    opacity: Float,
    modifier: Modifier,
    perRow: Int = g.controls.size
) {
    val on = ui.toggles[g.toggleFn] == true
    GroupCard(g.title, on, { bridge.setToggle(g.toggleFn, !on) }, opacity, modifier) {
        for (row in g.controls.chunked(perRow)) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (k in row) MixKnob(k, on, ui, bridge, opacity, Modifier.weight(1f).fillMaxHeight())
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Kartu meter level mic (tanpa saklar). */
@Composable
private fun LevelCard(ui: SkinUiState, opacity: Float, modifier: Modifier) {
    GroupCard("Level", false, null, opacity, modifier) {
        MeterTile("Mic", { ui.meters["meter.mic"] ?: 0f }, opacity, Modifier.weight(1f).fillMaxWidth())
        MeterTile("Output", { ui.meters["meter.output"] ?: 0f }, opacity, Modifier.weight(1f).fillMaxWidth())
    }
}

/** Isi bagian EQUALIZER AUDIO: knob 3 per baris, sisa kotak kosong diisi meter OUT. rowH = null -> baris mengisi tinggi (weight). */
@Composable
private fun ColumnScope.EqAudioRows(ui: SkinUiState, bridge: MixerBridge, opacity: Float, rowH: Dp?) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    for (chunk in audio.controls.chunked(3)) {
        val rowMod = if (rowH != null) Modifier.fillMaxWidth().height(rowH) else Modifier.fillMaxWidth().weight(1f)
        Row(modifier = rowMod, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in chunk) MixKnob(k, audioOn, ui, bridge, opacity, Modifier.weight(1f).fillMaxHeight())
            if (chunk.size < 3) {
                MeterTile("OUT", { ui.meters["meter.output"] ?: 0f }, opacity, Modifier.weight((3 - chunk.size).toFloat()).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun MixerWide(ui: SkinUiState, bridge: MixerBridge, opacity: Float, modifier: Modifier) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MixSection("MIKROFON", false, null, opacity, Modifier.weight(8f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MixGroupCard(groupOf("mic.toggle"), ui, bridge, opacity, Modifier.weight(2f).fillMaxHeight())
                MixGroupCard(groupOf("reverb.toggle"), ui, bridge, opacity, Modifier.weight(6f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MixGroupCard(groupOf("echo.toggle"), ui, bridge, opacity, Modifier.weight(3f).fillMaxHeight())
                MixGroupCard(groupOf("eqmic.toggle"), ui, bridge, opacity, Modifier.weight(3f).fillMaxHeight())
                LevelCard(ui, opacity, Modifier.weight(2f).fillMaxHeight())
            }
        }
        MixSection(
            "EQUALIZER AUDIO",
            audioOn, { bridge.setToggle(audio.toggleFn, !audioOn) }, opacity,
            Modifier.weight(3.2f).fillMaxHeight()
        ) {
            EqAudioRows(ui, bridge, opacity, null)
        }
    }
}

@Composable
private fun MixerNarrow(
    ui: SkinUiState, bridge: MixerBridge, opacity: Float, onSysReverbToggle: () -> Unit, scroll: ScrollState, modifier: Modifier,
    showStage: Boolean, stageEditable: Boolean, ytHost: String
) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    Column(
        modifier = modifier.verticalScroll(scroll).ytAnchor(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // PANGGUNG + YOUTUBE (potret, di samping panggung). Jendela pemegang WebView ditentukan ytHost ("main" / "float").
        AnchoredStageAndYoutube(scroll, ytHost, Modifier.fillMaxWidth(), showStage, stageEditable)
        // Mode CHANNEL: strip per channel (fader + meter) di atas mixer klasik.
        if (ChUi.mode) ChannelSection(ui, bridge, opacity, onSysReverbToggle, Modifier.fillMaxWidth(), floating = ytHost == YtHost.FLOAT)
        MixSection("MIKROFON", false, null, opacity, Modifier.fillMaxWidth(), fill = false) {
            Row(Modifier.fillMaxWidth().height(cardHeight(1)), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MixGroupCard(groupOf("mic.toggle"), ui, bridge, opacity, Modifier.weight(2f).fillMaxHeight())
                LevelCard(ui, opacity, Modifier.weight(1f).fillMaxHeight())
            }
            MixGroupCard(groupOf("reverb.toggle"), ui, bridge, opacity, Modifier.fillMaxWidth().height(cardHeight(2)), perRow = 3)
            MixGroupCard(groupOf("echo.toggle"), ui, bridge, opacity, Modifier.fillMaxWidth().height(cardHeight(1)))
            MixGroupCard(groupOf("eqmic.toggle"), ui, bridge, opacity, Modifier.fillMaxWidth().height(cardHeight(1)))
        }
        MixSection(
            "EQUALIZER AUDIO",
            audioOn, { bridge.setToggle(audio.toggleFn, !audioOn) }, opacity,
            Modifier.fillMaxWidth(), fill = false
        ) {
            EqAudioRows(ui, bridge, opacity, KNOB_ROW_DP.dp)
        }
        SysReverbNarrow(opacity, onSysReverbToggle, Modifier.fillMaxWidth())
        // Mode equalizer: bagian EQUALIZER 31 BAND tepat di bawah (gulir ke bawah).
        if (CompUi.s.mode) CompressorSection(ui, bridge, opacity, Modifier.fillMaxWidth())
        if (Eq31Ui.s.mode) EqualizerSection(ui, bridge, opacity, Modifier.fillMaxWidth())
        if (FxUi.s.mode) FxSection(opacity, Modifier.fillMaxWidth())
    }
}

/** Satu-satunya panel mixer: dipanggil layar utama (MainActivity) dan panel floating (FloatingBody). */
@Composable
fun MixerGrid(
    ui: SkinUiState,
    bridge: MixerBridge,
    hasBackgroundImage: Boolean,
    opacity: Float,
    modifier: Modifier = Modifier,
    onSysReverbToggle: () -> Unit = {},
    /** Dari pemanggil (layar utama) supaya tombol lompat ke EQUALIZER bisa menggulir panel ini. */
    scroll: ScrollState = rememberScrollState(),
    /** PANGGUNG (live stage) tampil di atas mixer bila modenya aktif. Panel floating mematikannya (false). */
    showStage: Boolean = true,
    /** false = panggung hanya tampil (floating). */
    stageEditable: Boolean = true,
    /** Jendela yang menampilkan YouTube: "main" (layar utama) atau "float" (panel floating). Panel floating WAJIB mengisi "float". */
    ytHost: String = YtHost.MAIN
) {
    val ctx = LocalContext.current
    remember { Eq31Ui.ensureLoaded(ctx); CompUi.ensureLoaded(ctx); FxUi.ensureLoaded(ctx); StageUi.ensureLoaded(ctx); YtUi.ensureLoaded(ctx); ChUi.ensureLoaded(ctx) }
    BoxWithConstraints(
        modifier = modifier
            .background(if (hasBackgroundImage) Color.Transparent else Bg)
            .padding(CardGap)
    ) {
        val boxMaxWidth = this.maxWidth
        val boxMaxHeight = this.maxHeight
        val wide = boxMaxWidth >= WIDE_MIN_WIDTH_DP.dp && boxMaxWidth >= boxMaxHeight * 1.3f
        if (!wide) {
            MixerNarrow(ui, bridge, opacity, onSysReverbToggle, scroll, Modifier.fillMaxSize(), showStage, stageEditable, ytHost)
        } else {
            // MIKROFON + EQUALIZER AUDIO mengisi layar; pita REVERB AUDIO tepat di bawahnya (gulir ke bawah),
            // lalu (mode equalizer) bagian EQUALIZER 31 BAND.
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll).ytAnchor(scroll),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AnchoredStageAndYoutube(scroll, ytHost, Modifier.fillMaxWidth(), showStage, stageEditable)
                if (ChUi.mode) ChannelSection(ui, bridge, opacity, onSysReverbToggle, Modifier.fillMaxWidth(), floating = ytHost == YtHost.FLOAT)
                MixerWide(ui, bridge, opacity, Modifier.fillMaxWidth().height(maxOf(boxMaxHeight, WIDE_MIN_HEIGHT_DP.dp)))
                SysReverbWide(opacity, onSysReverbToggle, Modifier.fillMaxWidth().height(SYS_WIDE_HEIGHT_DP.dp))
                if (CompUi.s.mode) CompressorSection(ui, bridge, opacity, Modifier.fillMaxWidth())
                if (Eq31Ui.s.mode) EqualizerSection(ui, bridge, opacity, Modifier.fillMaxWidth())
                if (FxUi.s.mode) FxSection(opacity, Modifier.fillMaxWidth())
            }
        }
    }
}


// ---------------------------------------------------------------------------------------------------------------------
// LAYAR BARU (desain iOS): satu layar = satu tugas. Dipanggil dari AppShell (MainActivity).
// ---------------------------------------------------------------------------------------------------------------------

/** Layar MIKROFON: semua yang memproses suara mic dalam satu layar tanpa gulir (landscape). Sempit -> kartu bertumpuk & digulir. */
@Composable
fun MicScreen(ui: SkinUiState, bridge: MixerBridge, modifier: Modifier = Modifier, opacity: Float = 1f) {
    BoxWithConstraints(modifier.padding(ScreenPadding)) {
        val wide = maxWidth >= 560.dp
        val h = maxOf(maxHeight, 300.dp)
        if (wide) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CardGap)
            ) {
                Column(Modifier.fillMaxWidth().height(h), verticalArrangement = Arrangement.spacedBy(CardGap)) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                        MixGroupCard(groupOf("mic.toggle"), ui, bridge, opacity, Modifier.weight(2f).fillMaxHeight())
                        MixGroupCard(groupOf("reverb.toggle"), ui, bridge, opacity, Modifier.weight(6f).fillMaxHeight())
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                        MixGroupCard(groupOf("echo.toggle"), ui, bridge, opacity, Modifier.weight(3f).fillMaxHeight())
                        MixGroupCard(groupOf("eqmic.toggle"), ui, bridge, opacity, Modifier.weight(3f).fillMaxHeight())
                        LevelCard(ui, opacity, Modifier.weight(2f).fillMaxHeight())
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CardGap)
            ) {
                Row(Modifier.fillMaxWidth().height(cardHeight(1)), horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                    MixGroupCard(groupOf("mic.toggle"), ui, bridge, opacity, Modifier.weight(2f).fillMaxHeight())
                    LevelCard(ui, opacity, Modifier.weight(1f).fillMaxHeight())
                }
                MixGroupCard(groupOf("reverb.toggle"), ui, bridge, opacity, Modifier.fillMaxWidth().height(cardHeight(2)), perRow = 3)
                MixGroupCard(groupOf("echo.toggle"), ui, bridge, opacity, Modifier.fillMaxWidth().height(cardHeight(1)))
                MixGroupCard(groupOf("eqmic.toggle"), ui, bridge, opacity, Modifier.fillMaxWidth().height(cardHeight(1)))
            }
        }
    }
}

/** Layar AUDIO: pengolahan suara dari HP (EQ audio, reverb audio, delay, kirim, output). Menggulir vertikal. */
@Composable
fun AudioScreen(ui: SkinUiState, bridge: MixerBridge, onSysReverbToggle: () -> Unit, modifier: Modifier = Modifier, opacity: Float = 1f) {
    val audio = groupOf("eqaudio.toggle")
    val audioOn = ui.toggles[audio.toggleFn] == true
    BoxWithConstraints(modifier.padding(ScreenPadding)) {
        val wide = maxWidth >= 560.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CardGap)
        ) {
            GroupCard(
                "Equalizer audio", audioOn, { bridge.setToggle(audio.toggleFn, !audioOn) }, opacity,
                Modifier.fillMaxWidth().height(cardHeight(1))
            ) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in audio.controls) MixKnob(k, audioOn, ui, bridge, opacity, Modifier.weight(1f).fillMaxHeight())
                    MeterTile("Output", { ui.meters["meter.output"] ?: 0f }, opacity, Modifier.weight(1f).fillMaxHeight())
                }
            }
            if (wide) SysReverbWide(opacity, onSysReverbToggle, Modifier.fillMaxWidth().height(SYS_WIDE_HEIGHT_DP.dp))
            else SysReverbNarrow(opacity, onSysReverbToggle, Modifier.fillMaxWidth())
        }
    }
}

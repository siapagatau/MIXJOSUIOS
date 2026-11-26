package com.kendang.realpads

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val DIALOG_MAX_WIDTH = 380.dp
private val DIALOG_EDGE_MARGIN = 16.dp      // jarak minimum kartu ke tepi layar (kiri & kanan sama)
private val DIALOG_PAD_H = 20.dp            // padding isi kiri = kanan (SIMETRIS)
private const val DIALOG_MAX_HEIGHT_FRACTION = 0.92f

/**
 * Dialog standar SEMUA dialog di app (Warna Tema, Reset Audio, Frekuensi Parametrik, Tambah Channel, Pilih Skin).
 *
 * - Lebar kartu tetap & menyesuaikan layar; isi selalu padding kiri = kanan (tidak ada lagi sisi yang lebih sempit).
 * - Tinggi kartu dibatasi ke tinggi layar (landscape pendek pun aman). Judul dan tombol bawah SELALU terlihat,
 *   hanya bagian isi di tengah yang digulir.
 * - Bar gulir terlihat saat isi lebih panjang dari ruang yang ada, dan bisa diseret (di jalur kanan kartu), jadi tetap
 *   bisa digulir walau jari sedang di atas slider / kotak warna.
 *
 * [buttons]: isi baris tombol bawah; pakai [DialogButton] (membagi lebar sama rata).
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissable: Boolean = true,
    titleTrailing: (@Composable () -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val cfg = LocalConfiguration.current
    val cardWidth = minOf(DIALOG_MAX_WIDTH.value, cfg.screenWidthDp - DIALOG_EDGE_MARGIN.value * 2).dp
    val cardMaxHeight = (cfg.screenHeightDp * DIALOG_MAX_HEIGHT_FRACTION).dp

    Dialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissable, dismissOnClickOutside = dismissable)
    ) {
        Column(
            modifier = Modifier
                .width(cardWidth)
                .heightIn(max = cardMaxHeight)
                .clip(SheetShape)
                .background(SurfaceColor)
                .padding(top = 18.dp, bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DIALOG_PAD_H),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    sentenceCase(title),
                    color = LabelPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                titleTrailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            DialogScrollBody(content)
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DIALOG_PAD_H),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { buttons() }
        }
    }
}

/** Tombol baris bawah sheet: lebar dibagi rata, tinggi 40 dp, sudut 9 dp. Bawaan: latar #2C2C2E, teks warna tema. */
@Composable
fun RowScope.DialogButton(
    text: String,
    onClick: () -> Unit,
    container: Color = SurfaceRaised,
    contentColor: Color = ThemeState.accentColor,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        modifier = Modifier.weight(1f).height(40.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = contentColor)
    ) { Text(sentenceCase(text), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1) }
}

/**
 * Alert gaya iOS: lebar 270 dp, judul + keterangan di tengah, dua tombol berdampingan dipisah garis tipis.
 * Batal = biasa (warna tema), konfirmasi = tebal; [destructive] = merah.
 */
@Composable
fun IosAlert(
    title: String,
    message: String,
    cancelText: String,
    confirmText: String,
    destructive: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)) {
        Column(
            modifier = Modifier.width(270.dp).clip(AlertShape).background(SurfaceRaised),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = LabelPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(message, color = LabelSecondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Separator))
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) { detectTapGestures(onTap = { onCancel() }) },
                    contentAlignment = Alignment.Center
                ) { Text(cancelText, color = ThemeState.accentColor, fontSize = 17.sp) }
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(Separator))
                Box(
                    Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) { detectTapGestures(onTap = { onConfirm() }) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        confirmText, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = if (destructive) Danger else ThemeState.accentColor
                    )
                }
            }
        }
    }
}

/** Area isi yang bisa digulir + bar gulir + pudar di tepi atas/bawah. Mengambil sisa tinggi kartu. */
@Composable
private fun ColumnScope.DialogScrollBody(content: @Composable ColumnScope.() -> Unit) {
    val state = rememberScrollState()
    var viewH by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .onSizeChanged { viewH = it.height }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(state)
                .padding(horizontal = DIALOG_PAD_H)
        ) { content() }
        // Pudar halus: penanda bahwa masih ada isi di atas / di bawah (dan memotong isi dengan rapi).
        if (state.canScrollBackward) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(16.dp)
                    .background(Brush.verticalGradient(listOf(SurfaceColor, Color.Transparent)))
            )
        }
        if (state.canScrollForward) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, SurfaceColor)))
            )
        }
        DialogScrollbar(state, viewH)
    }
}

private fun thumbHeight(trackH: Float, viewH: Int, max: Int, minPx: Float): Float =
    (trackH * viewH / (viewH + max)).coerceAtLeast(minPx).coerceAtMost(trackH)

/**
 * Bar gulir: tipis (3dp), tepat di TENGAH jalur padding kanan (24dp) sehingga jaraknya ke isi dan ke tepi kartu seimbang,
 * dengan jarak 6dp dari atas/bawah area gulir. Tanpa jalur latar (lebih bersih). Tampil hanya bila isi lebih panjang;
 * jalur 24dp bisa diseret untuk menggulir.
 */
@Composable
private fun BoxScope.DialogScrollbar(state: ScrollState, viewH: Int) {
    val max = state.maxValue
    if (viewH <= 0 || max <= 0 || max == Int.MAX_VALUE) return
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .width(DIALOG_PAD_H)
            .fillMaxHeight()
            .pointerInput(max, viewH) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    val trackH = size.height - 12.dp.toPx()
                    val travel = (trackH - thumbHeight(trackH, size.height, state.maxValue, 36.dp.toPx())).coerceAtLeast(1f)
                    state.dispatchRawDelta(dy * state.maxValue / travel)
                }
            }
            .drawBehind {
                val inset = 6.dp.toPx()
                val trackH = size.height - inset * 2
                val thumbH = thumbHeight(trackH, size.height.toInt(), state.maxValue, 36.dp.toPx())
                val frac = state.value.toFloat() / state.maxValue.coerceAtLeast(1)
                val w = 3.dp.toPx()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.38f),
                    topLeft = Offset((size.width - w) / 2f, inset + (trackH - thumbH) * frac),
                    size = Size(w, thumbH),
                    cornerRadius = CornerRadius(w / 2f)
                )
            }
    )
}

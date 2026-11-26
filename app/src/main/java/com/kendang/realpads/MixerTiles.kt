package com.kendang.realpads

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// Komponen mixer gaya iOS: saklar (UISwitch), knob busur, meter tipis, segmented. Satu aksen = warna TEMA; hijau hanya untuk ON dan level.

private val TileShape = RoundedCornerShape(10.dp)
private val ValueColor = Color(0xFFFFFFFF)

private fun tileBg(c: Color, opacity: Float): Color = c.copy(alpha = c.alpha * opacity.coerceIn(0f, 1f))

/** true = berada di dalam GroupCard (latar kartu sudah ada), jadi knob/meter tidak menggambar latar sendiri. */
internal val LocalInCard = compositionLocalOf { false }

private val AcronymWords = setOf("EQ", "FX", "L", "R", "L+R", "8D", "Q", "LFO", "YT", "HP", "LP")

/** Teks lama berhuruf kapital semua -> sentence case ("EQ MIC" -> "EQ mic"). Teks yang sudah campuran tidak diubah. */
fun sentenceCase(s: String): String {
    if (s.isEmpty() || s.any { it.isLowerCase() } || s.none { it.isLetter() }) return s
    val words = s.split(' ')
    return words.mapIndexed { i, w ->
        when {
            w in AcronymWords -> w
            i == 0 -> w.lowercase().replaceFirstChar { it.uppercase() }
            else -> w.lowercase()
        }
    }.joinToString(" ")
}

/**
 * Ketukan sungguhan: baru dihitung saat jari DIANGKAT di tempat yang sama. Jari yang menggeser (scroll panel, menyeret
 * jendela, lewat di atas tombol) TIDAK mengubah saklar. null = tidak bisa disentuh.
 */
@Composable
private fun Modifier.tapOnPress(onTap: (() -> Unit)?): Modifier {
    val latest by rememberUpdatedState(onTap)
    return if (onTap == null) this
    else this.pointerInput(Unit) { detectTapGestures(onTap = { latest?.invoke() }) }
}

/** Saklar gaya UISwitch 38 x 23 dp: hijau saat nyala, abu saat mati, geser 150 ms. Area sentuhnya = seluruh baris judul pemiliknya. */
@Composable
fun IosSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val thumb = 19.dp
    val pad = 2.dp
    val x by animateDpAsState(if (on) 38.dp - thumb - pad else pad, tween(150), label = "swX")
    val track by animateColorAsState(if (on) OnGreen else SwitchOff, tween(150), label = "swC")
    Box(
        modifier = modifier
            .size(width = 38.dp, height = 23.dp)
            .background(track, RoundedCornerShape(12.dp))
    ) {
        Box(
            Modifier
                .offset(x = x, y = pad)
                .size(thumb)
                .background(Color.White, CircleShape)
        )
    }
}

/**
 * Kartu kelompok (Mic, Reverb, Echo, EQ mic, Level): baris judul (SELURUH baris bisa diketuk untuk ON/OFF) lalu isinya.
 * Latar surface 14 dp, tanpa bayangan. Kartu mati diredupkan ke 38% tapi tetap terlihat.
 * onToggle = null -> tanpa saklar (mis. kartu Level). Isi diberi tinggi sisa kartu (weight), jadi kartu harus punya tinggi pasti.
 * Dipakai PERSIS sama oleh layar utama dan panel floating.
 */
@Composable
fun GroupCard(
    title: String,
    on: Boolean,
    onToggle: (() -> Unit)?,
    opacity: Float,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(tileBg(SurfaceColor, opacity), CardShape)
            .padding(CardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(26.dp).tapOnPress(onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                sentenceCase(title), color = LabelPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onToggle != null) IosSwitch(on)
        }
        CompositionLocalProvider(LocalInCard provides true) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).alpha(if (onToggle != null && !on) 0.38f else 1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content
            )
        }
    }
}

/**
 * BAGIAN besar layar ("Mikrofon", "Equalizer audio"): judul bagian (+ saklar ON/OFF di kanan, seluruh baris bisa diketuk),
 * isinya di bawahnya. fill = true: isi mengisi sisa tinggi. fill = false: tinggi mengikuti isi (bisa digulir).
 */
@Composable
fun MixSection(
    title: String,
    on: Boolean,
    onToggle: (() -> Unit)?,
    opacity: Float,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp).tapOnPress(onToggle).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(sentenceCase(title), color = LabelPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (onToggle != null) IosSwitch(on)
        }
        Column(
            modifier = (if (fill) Modifier.weight(1f) else Modifier).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CardGap),
            content = content
        )
    }
}

/** Tombol teks (Setel ulang, Edit lampu): latar #2C2C2E, teks warna tema, sudut 9 dp. */
@Composable
fun ButtonTile(label: String, opacity: Float, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val latest by rememberUpdatedState(onTap)
    Box(
        modifier = modifier
            .background(tileBg(SurfaceRaised, opacity), ButtonShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { latest() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(sentenceCase(label), color = ThemeState.accentColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun EmptyTile(opacity: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(tileBg(SurfaceColor, opacity * 0.6f), TileShape))
}

/**
 * Kontrol segmented iOS (tipe filter, kanal L/R, skenario, jenis skin, navigasi YouTube). Sudut 9 dp, item terpilih #636366.
 * Hanya berubah saat diketuk (jari diangkat di tempat yang sama).
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 32.dp,
    onSelect: (Int) -> Unit
) {
    val latest by rememberUpdatedState(onSelect)
    Row(
        modifier = modifier.height(height).background(SurfaceRaised, SegmentShape).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (sel) SegmentSelected else Color.Transparent, RoundedCornerShape(7.dp))
                    .pointerInput(i) { detectTapGestures(onTap = { latest(i) }) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, color = LabelPrimary,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Slider iOS: jalur 4 dp warna tema, jempol putih 24 dp. */
@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = ThemeState.accentColor,
            inactiveTrackColor = KnobTrack
        ),
        modifier = modifier
    )
}

private const val ROT_RANGE = 1.5f * PI.toFloat()   // satu putaran penuh knob = 270 derajat

/**
 * Gestur knob PUTAR (rotary): jari memutar mengelilingi pusat knob. Nilai berubah mengikuti SELISIH sudut jari
 * (searah jarum jam = naik, berlawanan = turun), jadi knob berputar halus dan TIDAK loncat saat disentuh -
 * 270 derajat putaran jari = 0..100%. Dekat titik pusat diabaikan (sudutnya tidak stabil).
 * Ketuk dua kali = kembali ke nilai awal (bila onDoubleTap diberikan).
 */
@Composable
private fun Modifier.knobGesture(
    value: Float,
    onChange: (Float) -> Unit,
    onActive: (Boolean) -> Unit = {},
    onDoubleTap: (() -> Unit)? = null
): Modifier {
    val latest by rememberUpdatedState(onChange)
    val latestValue by rememberUpdatedState(value.coerceIn(0f, 1f))
    val latestActive by rememberUpdatedState(onActive)
    val latestDouble by rememberUpdatedState(onDoubleTap)
    return this.pointerInput(Unit) {
        var lastTapUp = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            latestActive(true)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val dead = 12.dp.toPx()
            val slop = viewConfiguration.touchSlop
            fun angleOf(p: Offset): Float? {
                val dx = p.x - cx
                val dy = p.y - cy
                return if (hypot(dx, dy) < dead) null else atan2(dy, dx)
            }
            var raw = latestValue
            var last: Float? = angleOf(down.position)
            var moved = false
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    if (!moved && ch.uptimeMillis - down.uptimeMillis < 250L) {
                        val dbl = latestDouble
                        if (dbl != null && down.uptimeMillis - lastTapUp < 350L) {
                            dbl()
                            lastTapUp = 0L
                        } else {
                            lastTapUp = ch.uptimeMillis
                        }
                    }
                    break
                }
                if ((ch.position - down.position).getDistance() > slop) moved = true
                val a = angleOf(ch.position)
                val prev = last
                if (a != null && prev != null) {
                    var d = a - prev
                    val twoPi = 2f * PI.toFloat()
                    while (d > PI.toFloat()) d -= twoPi
                    while (d < -PI.toFloat()) d += twoPi
                    raw = (raw + d / ROT_RANGE).coerceIn(0f, 1f)
                    latest(raw)
                }
                last = a
                ch.consume()
            }
            latestActive(false)
        }
    }
}


private val KnobMaxDp = 44.dp

/**
 * Gambar knob gaya iOS: jalur abu 270 derajat, busur nilai warna TEMA (garis bulat), penunjuk putih pendek di dalam busur.
 * Diameter maksimum 44 dp. bipolar = true (knob EQ): busur terisi dari tengah (12 jam) ke kiri/kanan, tengah = 0 dB.
 */
@Composable
private fun KnobDial(value: Float, bipolar: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val v = value.coerceIn(0f, 1f)
    val accent = ThemeState.accentColor
    Canvas(modifier = modifier) {
        val d = minOf(size.width, size.height, KnobMaxDp.toPx())
        val c = Offset(size.width / 2f, size.height / 2f)
        val stroke = (d * 0.09f).coerceAtLeast(3.dp.toPx())
        val arcR = d / 2f - stroke / 2f
        val tl = Offset(c.x - arcR, c.y - arcR)
        val sz = Size(arcR * 2f, arcR * 2f)
        drawArc(KnobTrack, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        val startA = if (bipolar) 270f else 135f
        val sweep = if (bipolar) 270f * (v - 0.5f) else 270f * v
        if (kotlin.math.abs(sweep) > 0.5f) {
            drawArc(accent, startA, sweep, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val inner = arcR - stroke * 0.5f - 2.dp.toPx()
        val ang = Math.toRadians((135f + 270f * v).toDouble())
        val ux = cos(ang).toFloat()
        val uy = sin(ang).toFloat()
        drawLine(
            if (active) accent else Color.White,
            Offset(c.x + ux * inner * 0.40f, c.y + uy * inner * 0.40f),
            Offset(c.x + ux * inner * 0.95f, c.y + uy * inner * 0.95f),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

/** Knob berdiri sendiri (dipakai panel floating). Seluruh area modifier bisa disentuh & diputar. */
@Composable
fun Knob(value: Float, modifier: Modifier = Modifier, bipolar: Boolean = false, onChange: (Float) -> Unit) {
    var active by remember { mutableStateOf(false) }
    KnobDial(value, bipolar, active, modifier.knobGesture(value, onChange, { active = it }))
}

/** Kartu knob: knob di atas (area putar = seluruh bagian tengah), nilai tebal di bawah knob, nama kontrol di bawahnya. */
@Composable
fun KnobTile(
    label: String,
    value: Float,
    display: String,
    opacity: Float,
    modifier: Modifier = Modifier,
    bipolar: Boolean = false,
    dim: Boolean = false,
    onReset: (() -> Unit)? = null,
    onChange: (Float) -> Unit
) {
    var active by remember { mutableStateOf(false) }
    val inCard = LocalInCard.current
    Column(
        modifier = modifier
            .alpha(if (dim && !active) 0.38f else 1f)
            .then(if (inCard) Modifier else Modifier.background(tileBg(SurfaceColor, opacity), TileShape))
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KnobDial(
            value, bipolar, active,
            Modifier.weight(1f).fillMaxWidth()
                .knobGesture(value, onChange, { active = it }, onReset)
        )
        Text(
            display,
            color = if (active) ThemeState.accentColor else ValueColor,
            fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center
        )
        Text(
            sentenceCase(label),
            color = LabelSecondary,
            fontWeight = FontWeight.Normal, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center
        )
    }
}

/**
 * Meter level: batang tipis 9 dp, hijau (aman) -> kuning (62-86%) -> merah (puncak). level() dibaca saat menggambar saja.
 * Bentuk menyesuaikan tempat: lebar -> batang mendatar; sempit/tinggi -> batang tegak (bawah = pelan).
 * label kosong -> tanpa teks (judul sudah ada di kartu pemiliknya).
 */
@Composable
fun MeterTile(label: String, level: () -> Float, opacity: Float, modifier: Modifier = Modifier) {
    val inCard = LocalInCard.current
    Column(
        modifier = modifier
            .then(if (inCard) Modifier else Modifier.background(tileBg(SurfaceColor, opacity), TileShape))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (label.isNotEmpty()) {
            Text(sentenceCase(label), color = LabelSecondary, fontWeight = FontWeight.Normal, fontSize = 10.sp, maxLines = 1)
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(top = if (label.isNotEmpty()) 3.dp else 0.dp)) {
            val horizontal = size.width >= size.height * 1.4f
            val n = if (horizontal) 20 else 14
            val gap = 1.5.dp.toPx()
            val lit = (level().coerceIn(0f, 1f) * n).roundToInt()
            val corner = CornerRadius(1.5.dp.toPx())
            fun segColor(i: Int): Color = when {
                i >= lit -> KnobTrack
                i < n * 0.62f -> OnGreen
                i < n * 0.86f -> Warning
                else -> Danger
            }
            val bar = 9.dp.toPx()
            if (horizontal) {
                val barH = minOf(size.height, bar)
                val top = (size.height - barH) / 2f
                val w = (size.width - gap * (n - 1)) / n
                for (i in 0 until n) {
                    drawRoundRect(segColor(i), topLeft = Offset(i * (w + gap), top), size = Size(w, barH), cornerRadius = corner)
                }
            } else {
                val barW = minOf(size.width, bar)
                val left = (size.width - barW) / 2f
                val h = (size.height - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val y = size.height - (i + 1) * h - i * gap
                    drawRoundRect(segColor(i), topLeft = Offset(left, y), size = Size(barW, h), cornerRadius = corner)
                }
            }
        }
    }
}

// Compose gak nyediain Slider vertikal bawaan -> ukur Slider biasa dengan constraint width/height ditukar, lalu diputar 90 derajat.
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = ThemeState.accentColor,
            inactiveTrackColor = KnobTrack
        ),
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        x = -(placeable.width / 2 - placeable.height / 2),
                        y = -(placeable.height / 2 - placeable.width / 2)
                    )
                }
            }
            .rotate(-90f)
    )
}

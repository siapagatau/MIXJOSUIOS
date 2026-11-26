package com.kendang.realpads

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GifBox
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

// ---------------------------------------------------------------------------------------------------------------------
// Bagian PANGGUNG (di ATAS mixer). Lihat StageModel.kt untuk data & StageMedia.kt untuk isi layar.
// Susunan lapisan: latar layar + media -> gambar panggung (crossfade antar scene) -> lampu (aditif) -> pegangan edit.
// ---------------------------------------------------------------------------------------------------------------------

private sealed class StageSel {
    data class Light(val id: String) : StageSel()
    data class Slot(val id: String) : StageSel()
}

/** Jeda antar gambar ulang lampu animasi (ms) = ~30 fps. */
private const val LIGHT_FRAME_MS = 33L

private val SCENE_CHIPS = listOf("auto" to "Otomatis", "cerah" to "Cerah", "sunset" to "Sunset", "malam" to "Malam")
private val LIGHT_COLORS = listOf(
    0xFFFFFFFF, 0xFFFF3B30, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF00E5FF, 0xFF2979FF, 0xFF7C4DFF, 0xFFFF2DAA
).map { it.toInt() }

@Composable
/**
 * [editable] false = hanya tampil (panel floating): tanpa tombol & tanpa mode edit; pengaturan dilakukan di app utama.
 * [side] bukan null = ada panel di SAMPING gambar panggung (YouTube potret): dipanggil dengan (lebar, tinggi) yang tersedia;
 * tingginya sama dengan tinggi gambar panggung, dan bar kontrol panggung tetap selebar penuh di bawahnya.
 */
fun StageSection(modifier: Modifier = Modifier, editable: Boolean = true, side: (@Composable (Dp, Dp) -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { StageUi.ensureLoaded(ctx); 0 }
    LaunchedEffect(Unit) { StageUi.prepare(ctx) }
    val def = StageUi.def
    Column(modifier.background(BgColor)) {
        if (def == null) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                Text("Panggung", color = Color.White, fontSize = AppTextSize, fontWeight = FontWeight.Bold)
                Text(
                    StageUi.status.ifBlank { "Menyiapkan panggung..." },
                    color = TextDim, fontSize = AppTextSize, modifier = Modifier.weight(1f)
                )
                if (!StageUi.busy) PillBtn(Icons.Filled.Refresh, "COBA LAGI") { scope.launch { StageUi.prepare(ctx) } }
            }
        } else {
            StageContent(def, editable, side)
        }
    }
}

@Composable
private fun StageContent(def: StageDef, editable: Boolean, side: (@Composable (Dp, Dp) -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = StageUi.cfg
    var editOn by remember { mutableStateOf(false) }
    val edit = editable && editOn
    var tab by remember { mutableStateOf(0) } // 0 = LAYAR, 1 = LAMPU
    var sel by remember { mutableStateOf<StageSel?>(null) }
    var scene by remember { mutableStateOf(stageSceneFor(def, cfg.scene)) }
    // Scene mengikuti jam HP (jadwal di stage.json) - dicek berkala; mode manual mengunci scene.
    LaunchedEffect(def, cfg.scene) {
        while (true) {
            scene = stageSceneFor(def, cfg.scene)
            delay(15_000)
        }
    }
    val sceneDef = def.scenes[scene] ?: def.scenes.values.first()
    val lights = cfg.lights ?: def.defaultLights

    // Jam animasi (ms) - hanya dibaca di lapisan gambar, jadi hanya menggambar ulang kanvas lampu.
    // HEMAT: jam hanya berjalan bila ada lampu yang BERGERAK/BERKEDIP (lampu diam & tetap = tanpa gambar ulang sama sekali),
    // dan dibatasi ~30 fps (bukan tiap frame layar) supaya tidak berebut GPU/CPU dengan video app lain (mis. YouTube).
    var timeMs by remember { mutableLongStateOf(0L) }
    val animated = cfg.lightsOn && lights.any { it.on && ((it.blink != "tetap" && it.bpm > 0f) || (it.isBeam && it.motion != "diam")) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        val t0 = SystemClock.uptimeMillis() - timeMs
        while (true) {
            timeMs = SystemClock.uptimeMillis() - t0
            delay(LIGHT_FRAME_MS)
        }
    }

    LaunchedEffect(sel) { when (sel) { is StageSel.Slot -> tab = 0; is StageSel.Light -> tab = 1; null -> {} } }

    var pendingSlot by remember { mutableStateOf<String?>(null) }
    // Pemilih berkas butuh Activity (ActivityResultRegistryOwner). Panel FLOATING berjalan di Service -> tidak ada, jadi hanya dibuat
    // saat panggung bisa diedit (app utama). `editable` tetap selama komposisi, jadi urutan composable aman.
    val picker = if (editable) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val slot = pendingSlot
            if (uri != null && slot != null) {
                scope.launch {
                    val err = StageUi.importMedia(ctx, slot, uri)
                    if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
                }
            }
        }
    } else null

    val density = LocalDensity.current
    var boxPx by remember { mutableStateOf(IntSize.Zero) }
    val sceneNow by rememberUpdatedState(sceneDef)

    fun hit(p: Offset): StageSel? {
        val w = boxPx.width.toFloat()
        val h = boxPx.height.toFloat()
        if (w <= 0f || h <= 0f) return null
        val reach = 30f * density.density
        var best: StageLight? = null
        var bestD = reach
        for (l in StageUi.currentLights()) {
            val d = hypot(p.x - l.x * w, p.y - l.y * h)
            if (d <= bestD) { best = l; bestD = d }
        }
        if (best != null) return StageSel.Light(best.id)
        for (s in sceneNow.slots) {
            if (p.x >= s.x * w && p.x <= (s.x + s.w) * w && p.y >= s.y * h && p.y <= (s.y + s.h) * h) return StageSel.Slot(s.id)
        }
        return null
    }

    fun inSlot(id: String, p: Offset): Boolean {
        val w = boxPx.width.toFloat()
        val h = boxPx.height.toFloat()
        val sl = sceneNow.slots.firstOrNull { it.id == id } ?: return false
        return p.x >= sl.x * w && p.x <= (sl.x + sl.w) * w && p.y >= sl.y * h && p.y <= (sl.y + sl.h) * h
    }

    /** Cubit (zoom) + geser media pada layar [id]; ox/oy dalam pecahan ukuran layar. */
    fun pinchSlot(id: String, pan: Offset, zoom: Float) {
        val w = boxPx.width.toFloat()
        val h = boxPx.height.toFloat()
        val sl = sceneNow.slots.firstOrNull { it.id == id } ?: return
        if (w <= 0f || h <= 0f) return
        StageUi.editMedia(ctx, id) {
            it.copy(
                scale = (it.scale * zoom).coerceIn(0.2f, 6f),
                ox = (it.ox + pan.x / (sl.w * w)).coerceIn(-2f, 2f),
                oy = (it.oy + pan.y / (sl.h * h)).coerceIn(-2f, 2f)
            )
        }
    }

    fun moveBy(target: StageSel?, dx: Float, dy: Float) {
        val w = boxPx.width.toFloat()
        val h = boxPx.height.toFloat()
        if (w <= 0f || h <= 0f) return
        when (target) {
            is StageSel.Light -> StageUi.editLight(ctx, target.id) {
                it.copy(x = (it.x + dx / w).coerceIn(0f, 1f), y = (it.y + dy / h).coerceIn(0f, 1f))
            }
            is StageSel.Slot -> {
                val s = sceneNow.slots.firstOrNull { it.id == target.id } ?: return
                StageUi.editMedia(ctx, target.id) {
                    it.copy(
                        ox = (it.ox + dx / (s.w * w)).coerceIn(-2f, 2f),
                        oy = (it.oy + dy / (s.h * h)).coerceIn(-2f, 2f)
                    )
                }
            }
            null -> {}
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val total = maxWidth // selebar layar penuh; bila lebih tinggi dari layar, panggung digulir
        // Ada panel di samping (YouTube potret): lebar panggung = sisa setelah panel + jarak; tingginya tetap mengikuti aspek panggung.
        val sideW = if (side != null) ytSideWidth(total, def.aspect) else 0.dp
        val gap = if (side != null) YT_SIDE_GAP_DP.dp else 0.dp
        val w = total - sideW - gap
        val h = w / def.aspect
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(w, h).clipToBounds().background(Color.Black)
        ) {
            // 1) latar layar + media
            for (slot in sceneDef.slots) {
                val sw = w * slot.w
                val sh = h * slot.h
                Box(
                    Modifier.offset(w * slot.x, h * slot.y).size(sw, sh).clipToBounds().background(Color(0xFF06060A)),
                    contentAlignment = Alignment.Center
                ) {
                    val m = cfg.media[slot.id]
                    if (m != null && m.on) StageSlotMedia(m, sw, sh)
                }
            }
            // 2) gambar panggung (layar hijau sudah transparan)
            Crossfade(targetState = scene, animationSpec = tween(1500), label = "stageScene") { s -> StageSceneImage(def, s) }
            // 3) lampu + pegangan edit
            val gain = sceneDef.lightGain
            val selLight = (sel as? StageSel.Light)?.id
            val selSlot = (sel as? StageSel.Slot)?.id
            Canvas(Modifier.fillMaxSize()) {
                val t = timeMs / 1000f
                if (cfg.lightsOn) drawStageLights(lights, t, gain)
                if (edit) drawStageHandles(lights, t, sceneDef, selLight, selSlot)
            }
            // 4) sentuhan mode edit. Ketuk = pilih. Geser lampu = pindah. Pada layar terpilih: geser 1 jari = geser media,
            //    cubit 2 jari = zoom media. Area tanpa sasaran tidak dikonsumsi, jadi halaman tetap bisa digulir.
            Box(
                Modifier.fillMaxSize().onSizeChanged { boxPx = it }.then(
                    if (!edit) Modifier else Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val cur = sel
                            val target: StageSel? =
                                if (cur is StageSel.Slot && inSlot(cur.id, down.position)) cur else hit(down.position)
                            if (target is StageSel.Light) sel = target
                            val slop = viewConfiguration.touchSlop
                            var moved = false
                            var acc = Offset.Zero
                            do {
                                val ev = awaitPointerEvent()
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                acc += pan
                                if (!moved && (acc.getDistance() > slop || zoom != 1f)) moved = true
                                if (moved) {
                                    when (target) {
                                        is StageSel.Light -> if (ev.changes.size == 1) {
                                            moveBy(target, pan.x, pan.y)
                                            ev.changes.forEach { it.consume() }
                                        }
                                        is StageSel.Slot -> if (StageUi.cfg.media.containsKey(target.id)) {
                                            sel = target
                                            pinchSlot(target.id, pan, zoom)
                                            ev.changes.forEach { it.consume() }
                                        }
                                        null -> {}
                                    }
                                }
                            } while (ev.changes.any { it.pressed })
                            if (!moved) sel = hit(down.position)
                        }
                    }
                )
            )
        }
        if (side != null) {
            Spacer(Modifier.width(gap))
            side(sideW, h)
        }
        }
    }

    if (!editable) return

    // ---- bar kontrol ----
    var stageMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(SurfaceColor)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Seg {
                SegIcon(Icons.Filled.Schedule, cfg.scene == "auto") { StageUi.update(ctx) { it.copy(scene = "auto") } }
                SegIcon(Icons.Filled.WbSunny, cfg.scene == "cerah") { StageUi.update(ctx) { it.copy(scene = "cerah") } }
                SegIcon(Icons.Filled.WbTwilight, cfg.scene == "sunset") { StageUi.update(ctx) { it.copy(scene = "sunset") } }
                SegIcon(Icons.Filled.NightsStay, cfg.scene == "malam") { StageUi.update(ctx) { it.copy(scene = "malam") } }
            }
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { stageMenu = true; scope.launch { StageUi.refreshCatalog(ctx) } }.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Tv, null, tint = TextDim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(def.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Filled.ArrowDropDown, null, tint = TextDim, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = stageMenu, onDismissRequest = { stageMenu = false }) {
                    if (StageUi.catalog.isEmpty()) DropdownMenuItem(text = { Text("Memuat daftar...") }, onClick = { stageMenu = false })
                    for (e in StageUi.catalog) {
                        DropdownMenuItem(
                            text = { Text((if (e.id == def.id) "✓ " else "") + e.name) },
                            onClick = { stageMenu = false; scope.launch { StageUi.choose(ctx, e) } }
                        )
                    }
                }
            }
            PillBtn(Icons.Filled.Lightbulb, if (cfg.lightsOn) "ON" else "OFF", on = cfg.lightsOn) { StageUi.update(ctx) { it.copy(lightsOn = !it.lightsOn) } }
            PillBtn(if (editOn) Icons.Filled.Check else Icons.Filled.Tune, if (editOn) "SELESAI" else "EDIT", on = editOn) { editOn = !editOn; if (!editOn) sel = null }
        }
        if (StageUi.status.isNotBlank()) Text(StageUi.status, color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
    }

    if (edit) {
        Column(
            Modifier.fillMaxWidth().background(CtrlPanelColor).padding(horizontal = 12.dp).padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Seg {
                    SegTab(Icons.Filled.Tv, "LAYAR", tab == 0) {
                        tab = 0
                        if (sel !is StageSel.Slot) sel = sceneDef.slots.firstOrNull()?.let { StageSel.Slot(it.id) }
                    }
                    SegTab(Icons.Filled.Lightbulb, "LAMPU", tab == 1) {
                        tab = 1
                        if (sel !is StageSel.Light) sel = lights.firstOrNull()?.let { StageSel.Light(it.id) }
                    }
                }
                Spacer(Modifier.weight(1f))
                CircleBtn(Icons.Filled.Close) { editOn = false; sel = null }
            }
            if (tab == 0) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sl in sceneDef.slots.sortedBy { it.x }) {
                        val m = cfg.media[sl.id]
                        SlotCard(slotLabel(sl.id), m?.kind, m != null, (sel as? StageSel.Slot)?.id == sl.id) { sel = StageSel.Slot(sl.id) }
                    }
                }
                val s = sel
                if (s is StageSel.Slot) SlotEditor(
                    slotId = s.id,
                    media = cfg.media[s.id],
                    onPick = { pendingSlot = s.id; picker?.launch(arrayOf("image/*", "video/*")) }
                )
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillBtn(Icons.Filled.Add, "SOROT") { sel = StageSel.Light(StageUi.addLight(ctx, "beam")) }
                    PillBtn(Icons.Filled.Add, "LAMPU") { sel = StageSel.Light(StageUi.addLight(ctx, "lamp")) }
                    for (l in lights) LightDot(l, (sel as? StageSel.Light)?.id == l.id) { sel = StageSel.Light(l.id) }
                    PillBtn(Icons.Filled.RestartAlt, "BAWAAN", warn = true) { sel = null; StageUi.resetLights(ctx) }
                }
                val s = sel
                if (s is StageSel.Light) {
                    val l = lights.firstOrNull { it.id == s.id }
                    if (l == null) sel = null else LightEditor(l, onDeselect = { sel = null })
                }
            }
        }
    }
}

@Composable
private fun StageSceneImage(def: StageDef, scene: String) {
    val file = def.scenes[scene]?.let { File(def.dir, it.imageFile) }
    val bmp by produceState<ImageBitmap?>(null, file?.path) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(file.path)?.asImageBitmap() } catch (e: Throwable) { null }
        }
    }
    val b = bmp
    if (b != null) Image(bitmap = b, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
}

// ---------------------------------------------------------------------------------------------------------------------
// Penggambaran lampu
// ---------------------------------------------------------------------------------------------------------------------

private fun blinkFactor(l: StageLight, t: Float): Float {
    if (l.blink == "tetap" || l.bpm <= 0f) return 1f
    val ph = (t * l.bpm / 60f + l.phase) % 1f
    return when (l.blink) {
        "kedip" -> if (ph < 0.5f) 1f else 0f
        "denyut" -> 0.12f + 0.88f * (0.5f - 0.5f * cos(2f * PI.toFloat() * ph))
        else -> 1f
    }
}

private fun beamAngle(l: StageLight, t: Float): Float {
    val cycle = t / max(l.period, 0.5f) + l.phase
    return when (l.motion) {
        "ayun" -> {
            val m = 0.5f - 0.5f * cos(2f * PI.toFloat() * cycle)
            l.angle + (l.angleTo - l.angle) * m
        }
        "sapu" -> l.angle + (l.angleTo - l.angle) * (cycle % 1f)
        else -> l.angle
    }
}

private fun DrawScope.drawStageLights(lights: List<StageLight>, t: Float, gain: Float) {
    val w = size.width
    val h = size.height
    val p = Path() // dipakai ulang untuk semua lapis sinar (tanpa alokasi per frame)
    for (l in lights) {
        if (!l.on) continue
        val k = (l.intensity * gain * blinkFactor(l, t)).coerceIn(0f, 1f)
        if (k <= 0.01f) continue
        val c = Color(l.color)
        val sx = l.x * w
        val sy = l.y * h
        if (l.isBeam) {
            val a = beamAngle(l, t) * PI.toFloat() / 180f
            val dirX = sin(a)
            val dirY = cos(a)
            val perpX = cos(a)
            val perpY = -sin(a)
            val len = l.length * h
            val fx = sx + dirX * len
            val fy = sy + dirY * len
            val near = 0.006f * w
            val far = max(near, tan(l.spread * PI.toFloat() / 180f) * len)
            // sinar kerucut VOLUMETRIK: 6 lapis dari lebar ke sempit (aditif) -> tepi lembut, tengah terang, memudar halus ke ujung
            for (i in 0 until 6) {
                val widthK = 1f - i * 0.17f
                val alpha = 0.13f
                p.reset()
                p.moveTo(sx + perpX * near * widthK, sy + perpY * near * widthK)
                p.lineTo(fx + perpX * far * widthK, fy + perpY * far * widthK)
                p.lineTo(fx - perpX * far * widthK, fy - perpY * far * widthK)
                p.lineTo(sx - perpX * near * widthK, sy - perpY * near * widthK)
                p.close()
                drawPath(
                    p,
                    brush = Brush.linearGradient(
                        0f to c.copy(alpha = alpha * k),
                        0.35f to c.copy(alpha = alpha * k * 0.55f),
                        1f to c.copy(alpha = 0f),
                        start = Offset(sx, sy), end = Offset(fx, fy)
                    ),
                    blendMode = BlendMode.Plus
                )
            }
            val r = 0.016f * w
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * k), c.copy(alpha = 0.6f * k), c.copy(alpha = 0f)), center = Offset(sx, sy), radius = r),
                radius = r, center = Offset(sx, sy), blendMode = BlendMode.Plus
            )
        } else {
            val r = l.size * w * 3f
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.95f * k), c.copy(alpha = 0.7f * k), c.copy(alpha = 0f)),
                    center = Offset(sx, sy), radius = r
                ),
                radius = r, center = Offset(sx, sy), blendMode = BlendMode.Plus
            )
        }
    }
}

private fun DrawScope.drawStageHandles(lights: List<StageLight>, t: Float, scene: StageSceneDef, selLight: String?, selSlot: String?) {
    val w = size.width
    val h = size.height
    val r = 8.dp.toPx()
    for (l in lights) {
        val sel = l.id == selLight
        val p = Offset(l.x * w, l.y * h)
        if (l.isBeam) {
            val a = beamAngle(l, t) * PI.toFloat() / 180f
            drawLine(
                Color.White.copy(alpha = if (sel) 0.8f else 0.35f), p,
                Offset(p.x + sin(a) * l.length * h, p.y + cos(a) * l.length * h), strokeWidth = 1.5.dp.toPx()
            )
        }
        drawCircle(Color(l.color).copy(alpha = if (l.on) 0.9f else 0.25f), radius = r * 0.7f, center = p)
        drawCircle(if (sel) ThemeState.accentColor else Color.White, radius = r, center = p, style = Stroke(if (sel) 3.dp.toPx() else 1.5.dp.toPx()))
    }
    if (selSlot != null) {
        scene.slots.firstOrNull { it.id == selSlot }?.let { s ->
            drawRect(
                ThemeState.accentColor, topLeft = Offset(s.x * w, s.y * h),
                size = androidx.compose.ui.geometry.Size(s.w * w, s.h * h), style = Stroke(3.dp.toPx())
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Komponen UI & editor: dua tab (LAYAR / LAMPU), ikon + label, kontrol dikelompokkan dalam kartu
// ---------------------------------------------------------------------------------------------------------------------

private val CardBg = SurfaceColor
private val ItemBg = SurfaceRaised

private fun slotLabel(id: String) = when (id) {
    "kiri" -> "KIRI"
    "kanan" -> "KANAN"
    "tengah" -> "TENGAH"
    "tengah-kiri" -> "TENGAH\nKIRI"
    "tengah-kanan" -> "TENGAH\nKANAN"
    else -> id.uppercase()
}

@Composable
private fun Seg(content: @Composable () -> Unit) {
    Row(
        Modifier.background(SurfaceRaised, SegmentShape).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) { content() }
}

@Composable
private fun SegIcon(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(44.dp).height(36.dp).clip(RoundedCornerShape(7.dp)).background(if (on) SegmentSelected else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun SegTab(icon: ImageVector?, label: String, on: Boolean, onClick: () -> Unit) {
    val fg = Color.White
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(7.dp)).background(if (on) SegmentSelected else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(sentenceCase(label), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PillBtn(icon: ImageVector?, label: String, on: Boolean = false, warn: Boolean = false, onClick: () -> Unit) {
    val bg = if (on) ThemeState.accentColor else if (warn) Danger.copy(alpha = 0.18f) else ItemBg
    val fg = if (on) ThemeState.accentTextColor else if (warn) Danger else ThemeState.accentColor
    Row(
        Modifier.height(36.dp).clip(ButtonShape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(sentenceCase(label), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun CircleBtn(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(ItemBg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Kartu satu layar: ikon jenis media (atau + bila kosong). */
@Composable
private fun SlotCard(label: String, kind: String?, filled: Boolean, selected: Boolean, onClick: () -> Unit) {
    val icon = when (kind) { "video" -> Icons.Filled.Movie; "gif" -> Icons.Filled.GifBox; "image" -> Icons.Filled.Image; else -> Icons.Filled.Add }
    Column(
        Modifier.width(78.dp).height(62.dp).clip(RoundedCornerShape(12.dp)).background(ItemBg)
            .border(if (selected) 2.dp else 0.dp, if (selected) ThemeState.accentColor else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (filled) ThemeState.accentColor else TextDim, modifier = Modifier.size(22.dp))
        Text(label, color = if (selected) Color.White else TextDim, fontSize = 9.sp, textAlign = TextAlign.Center, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BeamIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.42f, h * 0.12f); lineTo(w * 0.58f, h * 0.12f); lineTo(w * 0.96f, h * 0.96f); lineTo(w * 0.04f, h * 0.96f); close()
        }
        drawPath(p, tint.copy(alpha = 0.55f))
        drawCircle(tint, radius = w * 0.15f, center = Offset(w * 0.5f, h * 0.14f))
    }
}

/** Satu lampu sebagai bulatan berwarna (ikon sorot / bohlam di dalamnya). */
@Composable
private fun LightDot(l: StageLight, selected: Boolean, onClick: () -> Unit) {
    val ink = Color.Black.copy(alpha = 0.65f)
    Box(
        Modifier.size(42.dp).background(Color(l.color).copy(alpha = if (l.on) 1f else 0.3f), CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0xFF55555A), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (l.isBeam) BeamIcon(ink) else Icon(Icons.Filled.Lightbulb, null, tint = ink, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Group(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = ThemeState.accentColor, modifier = Modifier.size(16.dp))
            Text(title, color = ThemeState.accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun IconSlider(icon: ImageVector, label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = TextDim, fontSize = 11.sp, modifier = Modifier.width(54.dp), maxLines = 1)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = ThemeState.accentColor, activeTrackColor = ThemeState.accentColor)
        )
        Text(text, color = Color.White, fontSize = 11.sp, modifier = Modifier.width(54.dp), textAlign = TextAlign.End, maxLines = 1)
    }
}

/** Pita pelangi: ketuk atau geser untuk memilih warna. */
@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    var wPx by remember { mutableStateOf(1) }
    val rainbow = remember { Brush.horizontalGradient(List(7) { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }) }
    Box(
        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).background(rainbow)
            .onSizeChanged { wPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { p -> onHue((p.x / wPx * 360f).coerceIn(0f, 360f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); onHue((change.position.x / wPx * 360f).coerceIn(0f, 360f)) } }
    ) {
        val px = with(LocalDensity.current) { 11.dp.roundToPx() }
        Box(
            Modifier.align(Alignment.CenterStart).offset { IntOffset((hue / 360f * wPx).toInt() - px, 0) }
                .size(22.dp).border(3.dp, Color.White, CircleShape)
        )
    }
}

@Composable
private fun TwoCol(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth > 560.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), content = left)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), content = right)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { left(); right() }
        }
    }
}

@Composable
private fun LightEditor(l: StageLight, onDeselect: () -> Unit) {
    val ctx = LocalContext.current
    fun ed(f: (StageLight) -> StageLight) = StageUi.editLight(ctx, l.id, f)
    val hue = remember(l.color) { FloatArray(3).also { android.graphics.Color.colorToHSV(l.color, it) }[0] }
    val center = (l.angle + l.angleTo) / 2f
    val half = (l.angleTo - l.angle) / 2f
    val absHalf = kotlin.math.abs(half)
    fun swing(c: Float, h: Float) = { x: StageLight -> x.copy(angle = (c - h).coerceIn(-85f, 85f), angleTo = (c + h).coerceIn(-85f, 85f)) }
    TwoCol(
        left = {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillBtn(Icons.Filled.PowerSettingsNew, if (l.on) "NYALA" else "MATI", on = l.on) { ed { it.copy(on = !it.on) } }
                PillBtn(Icons.Filled.ContentCopy, "GANDA") { StageUi.duplicateLight(ctx, l.id) }
                PillBtn(Icons.Filled.DeleteOutline, "HAPUS", warn = true) { StageUi.deleteLight(ctx, l.id); onDeselect() }
            }
            Group(Icons.Filled.Palette, "WARNA") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in LIGHT_COLORS) {
                        Box(
                            Modifier.size(30.dp).background(Color(c), CircleShape)
                                .border(if (c == l.color) 3.dp else 1.dp, if (c == l.color) Color.White else Color(0xFF55555A), CircleShape)
                                .clickable { ed { it.copy(color = c) } }
                        )
                    }
                }
                HueBar(hue) { h -> ed { it.copy(color = android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f))) } }
            }
            Group(Icons.Filled.BrightnessHigh, "CAHAYA") {
                IconSlider(Icons.Filled.BrightnessHigh, "TERANG", l.intensity, 0.1f..1f, "${(l.intensity * 100).toInt()}%") { v -> ed { it.copy(intensity = v) } }
                Seg {
                    SegTab(null, "TETAP", l.blink == "tetap") { ed { it.copy(blink = "tetap") } }
                    SegTab(Icons.Filled.FlashOn, "KEDIP", l.blink == "kedip") { ed { it.copy(blink = "kedip", bpm = if (it.bpm <= 0f) 120f else it.bpm) } }
                    SegTab(null, "DENYUT", l.blink == "denyut") { ed { it.copy(blink = "denyut", bpm = if (it.bpm <= 0f) 60f else it.bpm) } }
                }
                if (l.blink != "tetap") IconSlider(Icons.Filled.Speed, "TEMPO", l.bpm, 20f..240f, "${l.bpm.toInt()} BPM") { v -> ed { it.copy(bpm = v) } }
            }
        },
        right = {
            if (l.isBeam) {
                Group(Icons.Filled.Sync, "GERAK") {
                    Seg {
                        SegTab(null, "DIAM", l.motion == "diam") { ed { it.copy(motion = "diam", angle = (it.angle + it.angleTo) / 2f, angleTo = (it.angle + it.angleTo) / 2f) } }
                        SegTab(null, "AYUN", l.motion == "ayun") { ed { swing(center, if (absHalf < 2f) 20f else absHalf)(it).copy(motion = "ayun") } }
                        SegTab(null, "SAPU", l.motion == "sapu") { ed { swing(center, if (absHalf < 2f) 20f else absHalf)(it).copy(motion = "sapu") } }
                    }
                    IconSlider(Icons.Filled.RotateRight, "ARAH", center, -80f..80f, "${center.toInt()}°") { v -> ed(swing(v, half)) }
                    if (l.motion != "diam") {
                        IconSlider(Icons.Filled.SwapHoriz, "AYUNAN", absHalf, 2f..80f, "±${absHalf.toInt()}°") { v -> ed(swing(center, v)) }
                        IconSlider(Icons.Filled.Timer, "WAKTU", l.period, 1f..30f, "${"%.1f".format(l.period)} dt") { v -> ed { it.copy(period = v) } }
                    }
                }
                Group(Icons.Filled.Height, "SINAR") {
                    IconSlider(Icons.Filled.Height, "PANJANG", l.length, 0.15f..1.1f, "${(l.length * 100).toInt()}%") { v -> ed { it.copy(length = v) } }
                    IconSlider(Icons.Filled.OpenInFull, "LEBAR", l.spread, 2f..25f, "${l.spread.toInt()}°") { v -> ed { it.copy(spread = v) } }
                }
            } else {
                Group(Icons.Filled.FiberManualRecord, "UKURAN") {
                    IconSlider(Icons.Filled.FiberManualRecord, "BESAR", l.size, 0.01f..0.08f, "${(l.size * 1000).toInt()}") { v -> ed { it.copy(size = v) } }
                }
            }
        }
    )
}

@Composable
private fun SlotEditor(slotId: String, media: StageMedia?, onPick: () -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillBtn(Icons.Filled.AddPhotoAlternate, if (media == null) "PILIH MEDIA" else "GANTI", on = media == null) { onPick() }
            if (media != null) {
                PillBtn(if (media.on) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, if (media.on) "TAMPIL" else "SEMBUNYI") {
                    StageUi.editMedia(ctx, slotId) { it.copy(on = !it.on) }
                }
                PillBtn(Icons.Filled.RestartAlt, "RESET") { StageUi.editMedia(ctx, slotId) { it.copy(scale = 1f, ox = 0f, oy = 0f) } }
                PillBtn(Icons.Filled.DeleteOutline, "HAPUS", warn = true) { StageUi.clearMedia(ctx, slotId) }
            }
        }
        if (media != null) {
            Group(Icons.Filled.ZoomIn, "UKURAN MEDIA") {
                IconSlider(Icons.Filled.ZoomIn, "ZOOM", media.scale, 0.3f..4f, "${(media.scale * 100).toInt()}%") { v ->
                    StageUi.editMedia(ctx, slotId) { it.copy(scale = v) }
                }
            }
        }
    }
}

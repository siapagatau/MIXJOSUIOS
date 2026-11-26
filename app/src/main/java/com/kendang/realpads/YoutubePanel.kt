package com.kendang.realpads

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

// ---------------------------------------------------------------------------------------------------------------------
// BAGIAN YOUTUBE: browser mini youtube.com, SELALU POTRET, diletakkan di SAMPING panggung (bukan menumpuk di atasnya).
//
//  - Layar lebar (landscape / jendela >= 600dp) dan PANGGUNG aktif: panggung di kiri, YouTube (hampir persegi, cukup lebar) di kanan, setinggi gambar
//    panggung -> terlihat seperti HP di samping panggung. Sisa halaman (mixer, pengaturan audio) tetap digulir seperti biasa,
//    karena WebView hanya menempati kotak kecil itu (bukan selebar layar).
//  - Jendela sempit (portrait / floating) atau panggung mati: YouTube di tengah (~74% lebar), dengan sisi kiri-kanan kosong supaya
//    tetap ada tempat menggulir halaman.
// Bar mini: mundur / maju / beranda / muat ulang. (Perisai iklan, REVERB di bar, dan tombol perbesar sudah dihapus.)
// Tampil di layar utama DAN panel floating (hostId "main" / "float"); WebView-nya satu, pindah otomatis (lihat YtHost).
// ---------------------------------------------------------------------------------------------------------------------

/** Lebar minimal area agar YouTube diletakkan di samping panggung. */
private const val SIDE_MIN_WIDTH_DP = 600

/** Perbandingan lebar : tinggi panel YouTube di samping panggung (hampir persegi: cukup lebar, tidak sempit memanjang). */
private const val YT_PORTRAIT_RATIO = 1.0f

/** Perbandingan lebar : tinggi panel YouTube di tengah (tanpa panggung / jendela sempit). */
private const val YT_CENTER_RATIO = 0.8f

/** Bagian lebar area yang boleh dipakai YouTube di tengah; sisanya (kiri-kanan) dibiarkan kosong sebagai tempat menggulir. */
private const val YT_CENTER_WIDTH_FRAC = 0.74f

/** Jarak panggung - YouTube (dp). */
const val YT_SIDE_GAP_DP = 6

/**
 * Lebar panel YouTube di samping panggung. Dihitung supaya tingginya = tinggi gambar panggung dan tetap potret:
 * lebarYt = rasio * tinggiPanggung, tinggiPanggung = (total - lebarYt - jarak) / aspekPanggung.
 */
fun ytSideWidth(total: Dp, stageAspect: Float): Dp {
    val usable = total.value - YT_SIDE_GAP_DP
    val ideal = YT_PORTRAIT_RATIO * usable / (stageAspect + YT_PORTRAIT_RATIO)
    return ideal.coerceIn(200f, maxOf(200f, total.value * 0.46f)).dp
}

/** Pembungkus WebView: sentuhan di area video/halaman tidak dicuri oleh gulir halaman mixer di induknya. */
private class YtHolder(ctx: Context) : FrameLayout(ctx) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * Panggung + YouTube sebagai satu kesatuan, dipakai SEMUA tempat (layar utama klasik, layar utama dengan skin, floating).
 * @param hostId "main" (layar utama) atau "float" (panel floating) - JANGAN tertukar, WebView dipegang berdasarkan id ini.
 * @param showStage false = panggung tidak ditampilkan di sini.
 */
@Composable
fun StageAndYoutube(
    hostId: String,
    modifier: Modifier = Modifier,
    showStage: Boolean = true,
    stageEditable: Boolean = true
) {
    val ctx = LocalContext.current
    remember { YtUi.ensureLoaded(ctx); StageUi.ensureLoaded(ctx); 0 }
    val ytOn = YtUi.mode
    val stageOn = showStage && StageUi.cfg.mode
    if (!ytOn && !stageOn) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val total = maxWidth
        if (ytOn && stageOn && total >= SIDE_MIN_WIDTH_DP.dp) {
            // YouTube potret DI SAMPING panggung (ukurannya diatur StageSection supaya setinggi gambar panggung).
            StageSection(
                Modifier.fillMaxWidth(), editable = stageEditable,
                side = { w, h -> YoutubePanel(hostId, Modifier.size(w, h)) }
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                if (stageOn) StageSection(Modifier.fillMaxWidth(), editable = stageEditable)
                if (ytOn) YoutubeCentered(hostId, total)
            }
        }
    }
}

/**
 * [StageAndYoutube] yang MENJAGA POSISI GULIR. Blok panggung / YouTube ada DI ATAS bagian lain di kolom yang bisa digulir; saat
 * YouTube (atau panggung) dimatikan, tingginya hilang dan Compose menjepit posisi gulir ke dasar -> layar tiba-tiba "loncat ke paling
 * bawah". Di sini setiap kali tinggi blok MENGECIL, posisi gulir dikurangi sebesar selisihnya, jadi isi yang sedang dilihat
 * (misalnya MIXER CHANNEL) tetap di tempat yang sama.
 */
/**
 * Penahan posisi gulir TANPA kedip. Perubahan tinggi blok (panggung / YouTube nyala-mati) mengubah letak semua isi di bawahnya.
 * Mengoreksi gulir SETELAH layout = satu frame salah (kedip). Di sini koreksi dilakukan di frame yang SAMA:
 *  1. saat blok diukur ulang dan tingginya berubah, dicatat posisi gulir yang diinginkan ([target]);
 *  2. [ytAnchor] (dipasang tepat setelah verticalScroll) menggeser isi secara visual sebesar (gulir sekarang - target), sehingga
 *     isi yang sedang dilihat tidak bergerak sedikit pun pada frame itu;
 *  3. frame berikutnya gulir sungguhan dipindah ke target dan geseran visual dibuang dalam SATU snapshot, hasilnya identik.
 */
private class YtAnchorState { val target = mutableIntStateOf(-1) }   // -1 = tidak ada koreksi tertunda

private val ytAnchors = java.util.WeakHashMap<ScrollState, YtAnchorState>()
private fun ytAnchorOf(scroll: ScrollState): YtAnchorState = ytAnchors.getOrPut(scroll) { YtAnchorState() }

/** Pasang TEPAT SETELAH verticalScroll(scroll) pada kolom yang memuat [AnchoredStageAndYoutube]. */
fun Modifier.ytAnchor(scroll: ScrollState): Modifier {
    val a = ytAnchorOf(scroll)
    return this.layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        layout(p.width, p.height) {
            val t = a.target.intValue
            val dy = if (t >= 0) scroll.value.coerceIn(0, scroll.maxValue) - t else 0
            p.place(0, dy)
        }
    }
}

@Composable
fun AnchoredStageAndYoutube(
    scroll: ScrollState,
    hostId: String,
    modifier: Modifier = Modifier,
    showStage: Boolean = true,
    stageEditable: Boolean = true
) {
    val a = remember(scroll) { ytAnchorOf(scroll) }
    // keadaan per-instans (bukan state): tinggi terakhir, status nyala terakhir, "baru dinyalakan"
    val mem = remember(scroll) { intArrayOf(0, -1, 0) }   // [0] tinggi terakhir, [1] nyala terakhir (-1 = belum ada), [2] growPending
    val on = YtUi.mode || (showStage && StageUi.cfg.mode)
    if (mem[1] < 0) mem[1] = if (on) 1 else 0
    else {
        if (mem[1] == 0 && on) mem[2] = 1       // OFF -> ON (bukan komposisi awal)
        if (!on) mem[2] = 0
        mem[1] = if (on) 1 else 0
    }

    // Langkah 3: pindahkan gulir sungguhan ke target & buang geseran visual, dalam satu snapshot.
    LaunchedEffect(scroll) {
        snapshotFlow { a.target.intValue }.collect { t ->
            if (t >= 0) {
                Snapshot.withMutableSnapshot {
                    scroll.dispatchRawDelta((t - scroll.value).toFloat())
                    a.target.intValue = -1
                }
            }
        }
    }
    DisposableEffect(scroll) { onDispose { a.target.intValue = -1 } }

    Box(
        modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            val h = p.height
            if (h != mem[0]) {
                val prev = mem[0]
                mem[0] = h
                val d = h - prev
                // gulir SEBELUM dijepit Compose (verticalScroll baru memperbarui batas sesudah anak selesai diukur)
                val pending = a.target.intValue
                val s = if (pending >= 0) pending else Snapshot.withoutReadObservation { scroll.value }
                val anchor = if (d < 0) s > 0
                else (prev > 0 && s > 0) || (mem[2] == 1 && (s > 0 || ChUi.mode))
                if (d > 0) mem[2] = 0
                if (anchor) a.target.intValue = (s + d).coerceAtLeast(0)
            }
            layout(p.width, h) { p.place(0, 0) }
        }
    ) {
        StageAndYoutube(hostId, Modifier.fillMaxWidth(), showStage, stageEditable)
    }
}

/** YouTube potret di tengah (jendela sempit atau panggung mati). Kiri-kanan dibiarkan kosong sebagai tempat menggulir. */
@Composable
private fun YoutubeCentered(hostId: String, total: Dp) {
    val screenH = LocalConfiguration.current.screenHeightDp.toFloat()
    // Lebar dulu (sebagian besar area, kiri-kanan sisakan ruang kosong untuk menggulir), tinggi mengikuti rasio.
    val w = (total.value * YT_CENTER_WIDTH_FRAC).coerceAtMost(560f)
    val h = (w / YT_CENTER_RATIO).coerceIn(260f, maxOf(260f, screenH * 0.9f))
    Box(Modifier.fillMaxWidth().background(BgColor).padding(vertical = 3.dp), contentAlignment = Alignment.TopCenter) {
        YoutubePanel(hostId, Modifier.size(w.dp, h.dp))
    }
}

@Composable
private fun NavBtn(icon: ImageVector, desc: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(28.dp).clip(RoundedCornerShape(6.dp)).background(PanelColor).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = if (enabled) Color.White else TextDim, modifier = Modifier.size(17.dp))
    }
}

/** Panel YouTube potret: bar mini (mundur / maju / beranda / muat ulang) + WebView. Ukuran ditentukan pemanggil lewat [modifier]. */
@Composable
fun YoutubePanel(hostId: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { YtUi.ensureLoaded(ctx); 0 }

    // Klaim WebView saat tampil di depan (RESUME), lepas saat tidak terlihat (STOP) -> app ke latar, floating mengambil alih.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, hostId) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> YtHost.claim(hostId)
                Lifecycle.Event.ON_STOP -> YtHost.release(hostId)
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            YtHost.release(hostId)
        }
    }
    // Penyembuh: bila tidak ada yang memegang WebView padahal jendela ini sedang tampil, ambil sendiri (tanpa klik).
    val holder = YtHost.active
    LaunchedEffect(holder) {
        if (holder == null && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) YtHost.claim(hostId)
    }

    val active = holder == hostId
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(PanelColor).padding(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            NavBtn(Icons.Filled.ArrowBack, "Mundur", active && YtWeb.canBack, Modifier.weight(1f)) { YtWeb.back() }
            NavBtn(Icons.Filled.ArrowForward, "Maju", active && YtWeb.canForward, Modifier.weight(1f)) { YtWeb.forward() }
            NavBtn(Icons.Filled.Home, "Beranda YouTube", active, Modifier.weight(1f)) { YtWeb.home() }
            NavBtn(Icons.Filled.Refresh, "Muat ulang", active, Modifier.weight(1f)) { YtWeb.reload() }
        }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
            if (active) YtWebArea(hostId) else YtElsewhere(hostId)
        }
    }
}

@Composable
private fun YtWebArea(hostId: String) {
    val ctx = LocalContext.current
    val holder = remember { YtHolder(ctx) }
    DisposableEffect(holder) {
        YtWeb.attach(ctx, holder, hostId)
        onDispose { YtWeb.detach(holder) }
    }
    AndroidView(factory = { holder }, modifier = Modifier.fillMaxSize())
}

/**
 * WebView sedang dipegang jendela lain. Pindah terjadi OTOMATIS (jendela yang baru dibuka/tampil mengambilnya); area ini
 * hanya cadangan untuk kasus langka dua jendela tampil bersamaan: ketuk di mana saja untuk memindahkannya ke sini.
 */
@Composable
private fun YtElsewhere(hostId: String) {
    val where = if (hostId == YtHost.MAIN) "panel floating" else "layar utama"
    Box(Modifier.fillMaxSize().clickable { YtHost.claim(hostId) }, contentAlignment = Alignment.Center) {
        Text(
            "Video sedang tampil di $where\nketuk untuk pindah ke sini",
            color = TextDim, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp)
        )
    }
}

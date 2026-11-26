package com.kendang.realpads

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------------------------------------------------
// Isi layar green screen: foto (image), GIF (animasi, API 28+; di bawahnya jadi gambar diam), video (MediaExtractor + MediaCodec ->
// TextureView, DIPUTAR ULANG terus). Video HANYA memakai trek video: trek audio tidak pernah dipilih/dibuka, jadi video panggung
// TIDAK menghasilkan suara sama sekali (bukan sekadar volume 0) -> tidak ikut terekam oleh rekam layar / audio internal, dan tidak
// mengganggu audio mixer. Ukuran dasar = menutupi layar (cover, proporsi asli);
// StageMedia.scale memperbesar/memperkecil, ox/oy menggeser (pecahan ukuran layar). Bagian yang keluar dari layar terpotong
// (clipToBounds di pemanggil) dan rangka panggung tetap di depan media.
// ---------------------------------------------------------------------------------------------------------------------

private const val TAG = "StageMedia"

/** Sisi terpanjang GIF setelah dekode (px). */
private const val GIF_MAX_SIDE = 960

/** Batas frame video yang DIGAMBAR ke layar per detik (video 60 fps tetap hemat; frame lain dibuang tanpa digambar). */
private const val VIDEO_MAX_FPS = 30

/** Ukuran konten: menutupi kotak [boxW]x[boxH] dengan proporsi iw:ih, dikali [scale]. */
private fun coverSize(boxW: Dp, boxH: Dp, iw: Int, ih: Int, scale: Float): Pair<Dp, Dp> {
    val w = iw.coerceAtLeast(1)
    val h = ih.coerceAtLeast(1)
    val cover = maxOf(boxW.value / w, boxH.value / h)
    return (w * cover * scale).dp to (h * cover * scale).dp
}

@Composable
fun StageSlotMedia(m: StageMedia, boxW: Dp, boxH: Dp) {
    val ctx = LocalContext.current
    val file = remember(m.file) { File(StageUi.mediaDir(ctx), m.file) }
    // key(file.path): saat media DIGANTI, seluruh konten (TextureView/ImageView + dekoder) dibuat ulang dari nol. Tanpa ini,
    // AndroidView memakai ulang TextureView lama (factory hanya jalan sekali) sehingga video baru tidak tampil/membeku di
    // media lama sampai slot disembunyikan lalu ditampilkan lagi.
    key(file.path, m.kind) {
        when (m.kind) {
            "video" -> VideoContent(file, boxW, boxH, m)
            "gif" -> GifContent(file, boxW, boxH, m)
            else -> ImageContent(file, boxW, boxH, m)
        }
    }
}

// ---- foto ----

private fun decodeSampled(file: File, maxSide: Int): ImageBitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
    val bmp: Bitmap? = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    bmp?.asImageBitmap()
} catch (e: Throwable) {
    Log.w(TAG, "foto gagal dimuat: ${e.message}")
    null
}

@Composable
private fun ImageContent(file: File, boxW: Dp, boxH: Dp, m: StageMedia) {
    val bmp by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, 2048) }
    }
    val b = bmp ?: return
    val (w, h) = coverSize(boxW, boxH, b.width, b.height, m.scale)
    Image(
        bitmap = b,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.requiredSize(w, h).offset(boxW * m.ox, boxH * m.oy)
    )
}

// ---- GIF ----

private fun loadGif(file: File): Drawable? = try {
    if (Build.VERSION.SDK_INT >= 28) {
        // GIF besar diperkecil saat dekode (sisi terpanjang maks 960 px): animasi jauh lebih ringan, kualitas di layar panggung tetap cukup.
        val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val side = maxOf(info.size.width, info.size.height)
            if (side > GIF_MAX_SIDE) {
                val k = GIF_MAX_SIDE.toFloat() / side
                decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
        }
        if (d is AnimatedImageDrawable) d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        d
    } else {
        // Di bawah Android 9: hanya bingkai pertama (tanpa animasi).
        BitmapFactory.decodeFile(file.path)?.let { android.graphics.drawable.BitmapDrawable(null as android.content.res.Resources?, it) }
    }
} catch (e: Throwable) {
    Log.w(TAG, "GIF gagal dimuat: ${e.message}")
    null
}

@Composable
private fun GifContent(file: File, boxW: Dp, boxH: Dp, m: StageMedia) {
    val drawable by produceState<Drawable?>(null, file.path) { value = withContext(Dispatchers.IO) { loadGif(file) } }
    val d = drawable ?: return
    DisposableEffect(d) {
        (d as? Animatable)?.start()
        onDispose { (d as? Animatable)?.stop() }
    }
    val (w, h) = coverSize(boxW, boxH, d.intrinsicWidth, d.intrinsicHeight, m.scale)
    AndroidView(
        factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageDrawable(d) } },
        update = { v -> if (v.drawable !== d) v.setImageDrawable(d) },
        modifier = Modifier.requiredSize(w, h).offset(boxW * m.ox, boxH * m.oy)
    )
}

// ---- video ----

/**
 * Dekoder video SAJA di thread sendiri (prioritas latar belakang). Hanya trek video yang dipilih di MediaExtractor, jadi tidak ada
 * AudioTrack/pemutar audio sama sekali = tidak ada suara untuk ditangkap rekam layar. Frame yang telat dibuang (mengejar), frame
 * di atas [VIDEO_MAX_FPS] tidak digambar, dan saat video habis diulang dari awal.
 */
private class DecodeThread(
    private val path: String,
    private val surface: Surface,
    private val onFormat: (dispW: Int, dispH: Int, rotation: Int) -> Unit,
) : Thread("stage-video") {
    @Volatile private var stop = false
    @Volatile var paused = false

    fun stopAndJoin() {
        stop = true
        try { join(1500) } catch (e: InterruptedException) { /* abaikan */ }
    }

    private fun displaySize(f: MediaFormat): Pair<Int, Int> {
        var w = f.getInteger(MediaFormat.KEY_WIDTH)
        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
        if (f.containsKey("crop-left") && f.containsKey("crop-right") && f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
        }
        return w to h
    }

    private fun rotationOf(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_ROTATION)) ((f.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360 else 0

    private fun report(f: MediaFormat, rot: Int) {
        val (w, h) = displaySize(f)
        if (w > 0 && h > 0) if (rot == 90 || rot == 270) onFormat(h, w, rot) else onFormat(w, h, rot)
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull {
                (ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")
            }
            if (track == null) { Log.w(TAG, "tidak ada trek video"); return }
            val fmt = ex.getTrackFormat(track)
            // HANYA trek video dipilih: trek audio tidak pernah dibaca/diputar -> tidak ada suara sama sekali.
            ex.selectTrack(track)
            val rot = rotationOf(fmt)
            report(fmt, rot)

            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 1) // best-effort: tidak berebut dekoder dengan video app lain (YouTube) bila dekoder terbatas
            c.configure(fmt, surface, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            val minGapUs = 1_000_000L / VIDEO_MAX_FPS
            var inputDone = false
            var basePts = -1L
            var baseNs = 0L
            var lastRenderPts = Long.MIN_VALUE

            while (!stop) {
                if (paused) {
                    while (paused && !stop) Thread.sleep(40)
                    basePts = -1L // jam diatur ulang sesudah jeda
                    continue
                }
                if (!inputDone) {
                    val ix = c.dequeueInputBuffer(4_000)
                    if (ix >= 0) {
                        val buf = c.getInputBuffer(ix)
                        val n = if (buf != null) ex.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            c.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ix, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val out = c.dequeueOutputBuffer(info, 8_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    report(c.outputFormat, rot)
                } else if (out >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    var render = info.size > 0
                    val pts = info.presentationTimeUs
                    if (render && lastRenderPts != Long.MIN_VALUE && pts >= lastRenderPts && pts - lastRenderPts < minGapUs - 2_000L) {
                        render = false // di atas batas fps: dekode saja, tidak digambar
                    }
                    if (render) {
                        if (basePts < 0) { basePts = pts; baseNs = System.nanoTime() }
                        val dueNs = baseNs + (pts - basePts) * 1000L
                        var waitMs = (dueNs - System.nanoTime()) / 1_000_000L
                        while (waitMs > 1 && !stop && !paused) {
                            Thread.sleep(minOf(waitMs, 20L))
                            waitMs = (dueNs - System.nanoTime()) / 1_000_000L
                        }
                        val lateMs = (System.nanoTime() - dueNs) / 1_000_000L
                        if (lateMs > 400) { basePts = pts; baseNs = System.nanoTime() } // sangat telat: atur ulang jam, video melambat bukan membeku
                        else if (lateMs > 80) render = false                           // telat: buang frame, kejar
                        if (render) lastRenderPts = pts
                    }
                    c.releaseOutputBuffer(out, render && !stop)
                    if (eos) {
                        // Ulang dari awal.
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        c.flush()
                        inputDone = false
                        basePts = -1L
                        lastRenderPts = Long.MIN_VALUE
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "video gagal diputar: ${e.message}")
        } finally {
            try { codec?.stop() } catch (e: Throwable) { /* belum siap: abaikan */ }
            try { codec?.release() } catch (e: Throwable) { /* abaikan */ }
            try { extractor?.release() } catch (e: Throwable) { /* abaikan */ }
        }
    }
}

/** Pemegang satu video: TextureView + [DecodeThread]. Dekoder dihentikan saat permukaan hilang / keluar dari komposisi. */
private class VideoHolder(
    private val path: String,
    private val onSize: (Int, Int) -> Unit,
) : TextureView.SurfaceTextureListener {
    @Volatile private var view: TextureView? = null
    private var surface: Surface? = null
    private var worker: DecodeThread? = null
    private var paused = false
    private var rotation = 0

    fun createView(ctx: Context): TextureView = TextureView(ctx).also {
        view = it
        it.surfaceTextureListener = this
        it.isOpaque = true
        it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyRotation() }
    }

    /** Video yang disimpan miring (metadata rotasi): decoder ke Surface tidak memutar sendiri, jadi diputar di TextureView. */
    private fun applyRotation() {
        val v = view ?: return
        val vw = v.width.toFloat()
        val vh = v.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val m = Matrix()
        if (rotation == 90 || rotation == 270) m.postScale(vh / vw, vw / vh, vw / 2f, vh / 2f)
        if (rotation != 0) m.postRotate(rotation.toFloat(), vw / 2f, vh / 2f)
        v.setTransform(m)
    }

    private fun onFormat(w: Int, h: Int, rot: Int) {
        val v = view ?: return
        v.post {
            rotation = rot
            onSize(w, h)
            applyRotation()
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        stopWorker()
        val s = Surface(st)
        surface = s
        if (!paused) startWorker(s)
    }

    private fun startWorker(s: Surface) {
        val t = DecodeThread(path, s, ::onFormat)
        worker = t
        t.start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopWorker()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // JEDA = dekoder DILEPAS total (bukan cuma ditahan). Dekoder video perangkat jumlahnya terbatas; dekoder panggung yang
    // hanya "tidur" tetap memegang slot sehingga YouTube di WebView tidak kebagian dekoder dan videonya berhenti tiap di-play.
    fun setPaused(p: Boolean) {
        paused = p
        if (p) stopWorker()
        else if (worker == null) surface?.let { startWorker(it) }
    }

    private fun stopWorker() {
        worker?.stopAndJoin()
        worker = null
    }

    fun release() {
        stopWorker()
    }
}

/**
 * Video panggung. Selama YouTube dalam app AKTIF (YtUi.mode), video panggung TIDAK diputar: tampil sebagai GAMBAR DIAM (satu frame).
 * Alasannya: dekoder video perangkat jumlahnya terbatas; bila panggung ikut memakainya, pemutar YouTube di WebView tidak kebagian
 * dekoder dan videonya berhenti tiap di-play (terutama setelah pindah mode biasa <-> floating). Keluar dari komposisi = dekoder dilepas.
 */
@Composable
private fun VideoContent(file: File, boxW: Dp, boxH: Dp, m: StageMedia) {
    val ctx = LocalContext.current
    remember { YtUi.ensureLoaded(ctx); 0 }
    if (YtUi.mode) VideoStill(file, boxW, boxH, m) else VideoPlaying(file, boxW, boxH, m)
}

/** Satu frame video sebagai gambar (disimpan di memori per berkas, maks 720 px sisi terpanjang). */
private object VideoStills {
    private val cache = HashMap<String, Bitmap>()

    @Synchronized fun get(path: String): Bitmap? {
        cache[path]?.let { return it }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            // Frame pertama sering hitam: coba detik ke-1 dulu, lalu awal video.
            val f = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            f?.let {
                val side = maxOf(it.width, it.height)
                val out = if (side > 720) {
                    val k = 720f / side
                    Bitmap.createScaledBitmap(it, (it.width * k).toInt().coerceAtLeast(1), (it.height * k).toInt().coerceAtLeast(1), true)
                } else it
                cache[path] = out
                out
            }
        } catch (e: Throwable) {
            Log.w(TAG, "frame diam video gagal: ${e.message}")
            null
        } finally {
            try { r.release() } catch (e: Throwable) { /* abaikan */ }
        }
    }
}

@Composable
private fun VideoStill(file: File, boxW: Dp, boxH: Dp, m: StageMedia) {
    val bmp by produceState<Bitmap?>(null, file.path) { value = withContext(Dispatchers.IO) { VideoStills.get(file.path) } }
    val b = bmp ?: return
    val (w, h) = coverSize(boxW, boxH, b.width, b.height, m.scale)
    Image(
        bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds,
        modifier = Modifier.requiredSize(w, h).offset(boxW * m.ox, boxH * m.oy)
    )
}

@Composable
private fun VideoPlaying(file: File, boxW: Dp, boxH: Dp, m: StageMedia) {
    var vw by remember(file.path) { mutableStateOf(0) }
    var vh by remember(file.path) { mutableStateOf(0) }
    val holder = remember(file.path) { VideoHolder(file.path) { w, h -> vw = w; vh = h } }
    DisposableEffect(holder) { onDispose { holder.release() } }
    // Jeda saat app di latar belakang, lanjut saat kembali.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, holder) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) holder.setPaused(true)
            else if (e == Lifecycle.Event.ON_RESUME) holder.setPaused(false)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val (w, h) = if (vw > 0 && vh > 0) coverSize(boxW, boxH, vw, vh, m.scale) else (boxW to boxH)
    AndroidView(
        factory = { c -> holder.createView(c) },
        modifier = Modifier.requiredSize(w, h).offset(boxW * m.ox, boxH * m.oy)
    )
}

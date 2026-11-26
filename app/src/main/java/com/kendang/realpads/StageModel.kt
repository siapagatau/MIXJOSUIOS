package com.kendang.realpads

import android.content.Context
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kendang.realpads.skin.SkinEntry
import com.kendang.realpads.skin.StageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Calendar

// ---------------------------------------------------------------------------------------------------------------------
// PANGGUNG (live stage) di ATAS mixer. Skin panggung diunduh dari repo ALL-TYPE-SKIN-MIXJOS (type "stage"):
//   stage.json + gambar scene per waktu (cerah / sunset / malam) dengan layar green screen yang SUDAH transparan.
// Urutan gambar (belakang -> depan): latar layar (media pengguna) -> gambar panggung -> cahaya lampu (aditif).
// Pengaturan pengguna (media tiap layar, lampu buatan sendiri, mode scene) disimpan di SharedPreferences "stage_prefs".
// Berkas media disalin ke filesDir/stage-media supaya tetap bisa dipakai offline & tidak tergantung izin berkas asal.
// ---------------------------------------------------------------------------------------------------------------------

/** Satu layar green screen pada sebuah scene. Koordinat 0..1 terhadap gambar scene. */
data class StageSlotDef(val id: String, val x: Float, val y: Float, val w: Float, val h: Float)

data class StageSceneDef(val name: String, val imageFile: String, val lightGain: Float, val slots: List<StageSlotDef>)

data class StageScheduleItem(val fromMin: Int, val scene: String)

/**
 * Satu lampu. type: "beam" (lampu sorot, cahaya panjang) | "lamp" (lampu biasa, glow bulat).
 * Posisi x,y 0..1 terhadap gambar. Sudut dalam derajat: 0 = lurus ke bawah, positif = condong ke kanan.
 * motion (hanya beam): "diam" | "ayun" (bolak-balik halus antara angle dan angleTo) | "sapu" (angle -> angleTo lalu loncat ulang);
 * period = detik per satu putaran gerak. blink: "tetap" | "kedip" (nyala-mati) | "denyut" (redup-terang halus), bpm = tempo.
 */
data class StageLight(
    val id: String,
    val type: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val on: Boolean = true,
    val intensity: Float = 0.9f,
    val size: Float = 0.03f,
    val angle: Float = 0f,
    val angleTo: Float = 0f,
    val motion: String = "diam",
    val period: Float = 6f,
    val phase: Float = 0f,
    val length: Float = 0.5f,
    val spread: Float = 6f,
    val blink: String = "tetap",
    val bpm: Float = 0f,
) {
    val isBeam get() = type == "beam"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type).put("x", x.toDouble()).put("y", y.toDouble())
        .put("color", String.format("#%06X", color and 0xFFFFFF)).put("on", on)
        .put("intensity", intensity.toDouble()).put("size", size.toDouble())
        .put("angle", angle.toDouble()).put("angleTo", angleTo.toDouble()).put("motion", motion)
        .put("period", period.toDouble()).put("phase", phase.toDouble())
        .put("length", length.toDouble()).put("spread", spread.toDouble())
        .put("blink", blink).put("bpm", bpm.toDouble())

    companion object {
        fun fromJson(o: JSONObject): StageLight {
            val type = if (o.optString("type") == "lamp") "lamp" else "beam"
            val color = try { AColor.parseColor(o.optString("color", "#FFFFFF")) } catch (e: Exception) { 0xFFFFFFFF.toInt() }
            val motion = o.optString("motion", "diam").let { if (it == "ayun" || it == "sapu") it else "diam" }
            val blink = o.optString("blink", "tetap").let { if (it == "kedip" || it == "denyut") it else "tetap" }
            return StageLight(
                id = o.optString("id").ifBlank { "l-" + System.nanoTime() },
                type = type,
                x = o.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                y = o.optDouble("y", 0.4).toFloat().coerceIn(0f, 1f),
                color = color or (0xFF shl 24),
                on = o.optBoolean("on", true),
                intensity = o.optDouble("intensity", 0.9).toFloat().coerceIn(0.05f, 1f),
                size = o.optDouble("size", 0.03).toFloat().coerceIn(0.008f, 0.1f),
                angle = o.optDouble("angle", 0.0).toFloat().coerceIn(-85f, 85f),
                angleTo = o.optDouble("angleTo", 0.0).toFloat().coerceIn(-85f, 85f),
                motion = motion,
                period = o.optDouble("period", 6.0).toFloat().coerceIn(0.5f, 60f),
                phase = o.optDouble("phase", 0.0).toFloat().coerceIn(0f, 1f),
                length = o.optDouble("length", 0.5).toFloat().coerceIn(0.1f, 1.2f),
                spread = o.optDouble("spread", 6.0).toFloat().coerceIn(1f, 30f),
                blink = blink,
                bpm = o.optDouble("bpm", 0.0).toFloat().coerceIn(0f, 300f),
            )
        }
    }
}

class StageDef(
    val id: String,
    val name: String,
    val version: String,
    val aspect: Float,
    val scenes: Map<String, StageSceneDef>,
    val schedule: List<StageScheduleItem>,
    val defaultLights: List<StageLight>,
    val dir: File,
)

object StageParser {
    private fun hhmm(s: String): Int {
        val p = s.split(":")
        val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return (h.coerceIn(0, 23) * 60 + m.coerceIn(0, 59))
    }

    /** Baca stage.json di [dir]. Melempar bila tidak valid atau gambar scene tidak ada. */
    fun parseDir(dir: File, id: String): StageDef {
        val root = JSONObject(File(dir, "stage.json").readText(Charsets.UTF_8))
        val asp = root.optJSONArray("aspect")
        val aw = asp?.optDouble(0, 16.0) ?: 16.0
        val ah = asp?.optDouble(1, 9.0) ?: 9.0
        if (aw <= 0 || ah <= 0) throw IOException("aspect tidak valid")
        val so = root.optJSONObject("scenes") ?: throw IOException("stage.json tanpa 'scenes'")
        val scenes = LinkedHashMap<String, StageSceneDef>()
        for (name in so.keys()) {
            val o = so.optJSONObject(name) ?: continue
            val img = o.optString("image")
            if (!Regex("^[A-Za-z0-9_.-]{1,64}$").matches(img)) throw IOException("nama gambar tidak valid: $img")
            if (!File(dir, img).isFile) throw IOException("gambar scene '$name' tidak ada")
            val slots = ArrayList<StageSlotDef>()
            val sa = o.optJSONArray("slots") ?: JSONArray()
            for (i in 0 until sa.length()) {
                val s = sa.optJSONObject(i) ?: continue
                val sid = s.optString("id")
                if (sid.isBlank()) continue
                val w = s.optDouble("w", 0.0).toFloat()
                val h = s.optDouble("h", 0.0).toFloat()
                if (w <= 0f || h <= 0f) continue
                slots.add(StageSlotDef(sid, s.optDouble("x", 0.0).toFloat(), s.optDouble("y", 0.0).toFloat(), w, h))
            }
            scenes[name] = StageSceneDef(name, img, o.optDouble("lightGain", 1.0).toFloat().coerceIn(0f, 2f), slots)
        }
        if (scenes.isEmpty()) throw IOException("stage.json tanpa scene")
        val sched = ArrayList<StageScheduleItem>()
        val sch = root.optJSONArray("schedule") ?: JSONArray()
        for (i in 0 until sch.length()) {
            val o = sch.optJSONObject(i) ?: continue
            val sc = o.optString("scene")
            if (sc in scenes) sched.add(StageScheduleItem(hhmm(o.optString("from", "00:00")), sc))
        }
        sched.sortBy { it.fromMin }
        val lights = ArrayList<StageLight>()
        val la = root.optJSONArray("defaultLights") ?: JSONArray()
        for (i in 0 until la.length()) la.optJSONObject(i)?.let { lights.add(StageLight.fromJson(it)) }
        return StageDef(
            id = id,
            name = root.optString("name", id),
            version = root.optString("version", "?"),
            aspect = (aw / ah).toFloat(),
            scenes = scenes,
            schedule = sched,
            defaultLights = lights,
            dir = dir,
        )
    }
}

/** Scene yang dipakai sekarang: mode manual ("cerah"/"sunset"/"malam") atau "auto" = menurut jam lokal HP dan jadwal skin. */
fun stageSceneFor(def: StageDef, mode: String): String {
    if (mode != "auto" && def.scenes.containsKey(mode)) return mode
    val c = Calendar.getInstance()
    val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    var pick = def.schedule.lastOrNull()?.scene // sebelum jadwal pertama = lanjutan dari hari sebelumnya
    for (it in def.schedule) if (it.fromMin <= now) pick = it.scene
    return if (pick != null && def.scenes.containsKey(pick)) pick else def.scenes.keys.first()
}

/** Media pada satu layar. scale 1 = menutupi layar (cover); ox/oy = geser sebagai pecahan ukuran layar. */
data class StageMedia(
    val file: String,
    val kind: String, // "image" | "gif" | "video"
    val scale: Float = 1f,
    val ox: Float = 0f,
    val oy: Float = 0f,
    val on: Boolean = true,
)

data class StageConfig(
    val mode: Boolean = false,        // bagian PANGGUNG tampil di atas mixer
    val stageId: String = "",         // skin panggung terpilih
    val scene: String = "auto",       // auto | cerah | sunset | malam
    val lightsOn: Boolean = true,
    val lights: List<StageLight>? = null, // null = pakai lampu bawaan skin
    val media: Map<String, StageMedia> = emptyMap(),
)

object StageStore {
    private const val PREFS = "stage_prefs"
    private const val KEY = "config"

    fun load(context: Context): StageConfig {
        val txt = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return StageConfig()
        return try {
            val o = JSONObject(txt)
            val media = LinkedHashMap<String, StageMedia>()
            o.optJSONObject("media")?.let { mo ->
                for (k in mo.keys()) {
                    val m = mo.optJSONObject(k) ?: continue
                    val f = m.optString("file")
                    if (f.isBlank()) continue
                    val kind = m.optString("kind").let { if (it == "gif" || it == "video") it else "image" }
                    media[k] = StageMedia(
                        f, kind,
                        m.optDouble("scale", 1.0).toFloat().coerceIn(0.2f, 6f),
                        m.optDouble("ox", 0.0).toFloat().coerceIn(-2f, 2f),
                        m.optDouble("oy", 0.0).toFloat().coerceIn(-2f, 2f),
                        m.optBoolean("on", true)
                    )
                }
            }
            val lights = o.optJSONArray("lights")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { StageLight.fromJson(it) } }
            }
            StageConfig(
                mode = o.optBoolean("mode", false),
                stageId = o.optString("stageId", ""),
                scene = o.optString("scene", "auto").ifBlank { "auto" },
                lightsOn = o.optBoolean("lightsOn", true),
                lights = lights,
                media = media,
            )
        } catch (e: Exception) {
            Log.w("StageStore", "config rusak, pakai bawaan: ${e.message}")
            StageConfig()
        }
    }

    fun save(context: Context, c: StageConfig) {
        val mo = JSONObject()
        for ((k, m) in c.media) {
            mo.put(k, JSONObject().put("file", m.file).put("kind", m.kind).put("scale", m.scale.toDouble())
                .put("ox", m.ox.toDouble()).put("oy", m.oy.toDouble()).put("on", m.on))
        }
        val o = JSONObject().put("mode", c.mode).put("stageId", c.stageId).put("scene", c.scene)
            .put("lightsOn", c.lightsOn).put("media", mo)
        c.lights?.let { l -> o.put("lights", JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}

/** Keadaan PANGGUNG (satu proses, state Compose). */
object StageUi {
    private const val TAG = "StageUi"
    private const val MAX_MEDIA_BYTES = 300L * 1024 * 1024

    var cfg: StageConfig by mutableStateOf(StageConfig())
        private set
    var def: StageDef? by mutableStateOf(null)
        private set
    /** Pesan unduhan / galat untuk panel (kosong = tidak ada). */
    var status: String by mutableStateOf("")
        private set
    var catalog: List<SkinEntry> by mutableStateOf(emptyList())
        private set
    var busy: Boolean by mutableStateOf(false)
        private set

    private var loaded = false
    private var appCtx: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { appCtx?.let { StageStore.save(it, cfg) } }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        appCtx = context.applicationContext
        cfg = StageStore.load(context.applicationContext)
        loaded = true
    }

    /** Ubah pengaturan; tampil seketika, disimpan ke disk setelah jeda singkat (aman dipanggil tiap frame saat menggeser). */
    fun update(context: Context, f: (StageConfig) -> StageConfig) {
        ensureLoaded(context)
        cfg = f(cfg)
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 400)
    }

    fun setMode(context: Context, on: Boolean) = update(context) { it.copy(mode = on) }

    /** Lampu yang berlaku sekarang (milik pengguna bila sudah diubah, selain itu bawaan skin). */
    fun currentLights(): List<StageLight> = cfg.lights ?: def?.defaultLights ?: emptyList()

    fun setLights(context: Context, lights: List<StageLight>) = update(context) { it.copy(lights = lights) }

    fun editLight(context: Context, id: String, f: (StageLight) -> StageLight) =
        setLights(context, currentLights().map { if (it.id == id) f(it) else it })

    fun addLight(context: Context, type: String): String {
        val id = "u-" + System.currentTimeMillis()
        val l = if (type == "lamp") {
            StageLight(id, "lamp", 0.5f, 0.45f, 0xFFFF9F0A.toInt(), size = 0.035f, blink = "kedip", bpm = 120f)
        } else {
            StageLight(
                id, "beam", 0.5f, 0.42f, 0xFFFFFFFF.toInt(),
                angle = -20f, angleTo = 20f, motion = "ayun", period = 6f, phase = (Math.random()).toFloat(),
                length = 0.42f, spread = 11f
            )
        }
        setLights(context, currentLights() + l)
        return id
    }

    fun deleteLight(context: Context, id: String) = setLights(context, currentLights().filter { it.id != id })

    fun duplicateLight(context: Context, id: String): String? {
        val src = currentLights().firstOrNull { it.id == id } ?: return null
        val nid = "u-" + System.currentTimeMillis()
        setLights(context, currentLights() + src.copy(id = nid, x = (src.x + 0.03f).coerceAtMost(1f), y = (src.y + 0.03f).coerceAtMost(1f)))
        return nid
    }

    /** Kembalikan lampu ke bawaan skin. */
    fun resetLights(context: Context) = update(context) { it.copy(lights = null) }

    fun editMedia(context: Context, slotId: String, f: (StageMedia) -> StageMedia) =
        update(context) { c -> c.media[slotId]?.let { c.copy(media = c.media + (slotId to f(it))) } ?: c }

    fun mediaDir(context: Context): File = File(context.applicationContext.filesDir, "stage-media").also { it.mkdirs() }

    fun clearMedia(context: Context, slotId: String) {
        val old = cfg.media[slotId]?.file
        update(context) { it.copy(media = it.media - slotId) }
        if (old != null) File(mediaDir(context), old).delete()
    }

    /** Salin foto/GIF/video pilihan ke penyimpanan app lalu pasang di layar [slotId]. null = sukses, selain itu pesan error. */
    suspend fun importMedia(context: Context, slotId: String, uri: Uri): String? {
        val app = context.applicationContext
        val oldFile = cfg.media[slotId]?.file
        val err = withContext(Dispatchers.IO) {
            val mime = app.contentResolver.getType(uri) ?: ""
            val k = when {
                mime == "image/gif" -> "gif"
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                else -> return@withContext "Format tidak didukung (pilih foto, GIF, atau video)"
            }
            val ext = when (k) { "gif" -> "gif"; "video" -> "mp4"; else -> "img" }
            val safe = slotId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val f = File(mediaDir(app), "$safe-${System.currentTimeMillis()}.$ext")
            try {
                val ins = app.contentResolver.openInputStream(uri) ?: return@withContext "Berkas tidak bisa dibuka"
                ins.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_MEDIA_BYTES) throw IOException("Berkas terlalu besar (maksimal 300 MB)")
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: Exception) {
                f.delete()
                return@withContext e.message ?: "Gagal menyalin berkas"
            }
            pending = f.name to k
            null
        }
        if (err != null) return err
        val (name, k) = pending ?: return "Gagal memasang media"
        pending = null
        update(app) { it.copy(media = it.media + (slotId to StageMedia(name, k))) }
        if (oldFile != null) withContext(Dispatchers.IO) { File(mediaDir(app), oldFile).delete() }
        // Video terpasang normal; bila resolusinya tinggi, beri saran singkat (4K bikin lag saat app lain seperti YouTube sedang jalan).
        return if (k == "video") withContext(Dispatchers.IO) { heavyVideoHint(File(mediaDir(app), name)) } else null
    }

    private fun heavyVideoHint(f: File): String? = try {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (maxOf(w, h) > 1920) "Video terpasang. Resolusi ${w}x$h berat dan bisa bikin lag saat app lain jalan; disarankan maks 1080p (720p lebih ringan)." else null
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        null
    }

    @Volatile private var pending: Pair<String, String>? = null

    // ---- unduhan panggung dari repo ----

    private fun loadCached(app: Context, id: String): StageDef? {
        val dir = StageRepository.cachedSkinDir(app, id) ?: return null
        return try { StageParser.parseDir(dir, id) } catch (e: Exception) { Log.w(TAG, "panggung '$id' tidak terbaca: ${e.message}"); null }
    }

    /** Muat panggung terpilih dari cache; bila belum ada, unduh dari repo (yang pertama di katalog). */
    suspend fun prepare(context: Context) {
        ensureLoaded(context)
        if (def != null || busy) return
        busy = true
        try {
            val app = context.applicationContext
            val id = cfg.stageId
            if (id.isNotBlank()) {
                val d = withContext(Dispatchers.IO) { loadCached(app, id) }
                if (d != null) { def = d; status = ""; return }
            }
            status = "Mengunduh panggung..."
            val list = withContext(Dispatchers.IO) { StageRepository.refreshIndex(app) }
            catalog = list
            val entry = list.firstOrNull { it.id == id } ?: list.firstOrNull()
            if (entry == null) { status = "Belum ada panggung di repo (atau sedang offline)."; return }
            download(app, entry)
        } finally {
            busy = false
        }
    }

    suspend fun refreshCatalog(context: Context) {
        val app = context.applicationContext
        catalog = withContext(Dispatchers.IO) { StageRepository.refreshIndex(app) }
    }

    /** Pilih panggung lain: diunduh bila belum ada di cache. */
    suspend fun choose(context: Context, entry: SkinEntry) {
        if (busy) return
        busy = true
        try {
            val app = context.applicationContext
            val cached = if (StageRepository.isDownloaded(app, entry.id)) withContext(Dispatchers.IO) { loadCached(app, entry.id) } else null
            if (cached != null) { def = cached; status = ""; update(app) { it.copy(stageId = entry.id) } } else download(app, entry)
        } finally {
            busy = false
        }
    }

    private suspend fun download(app: Context, entry: SkinEntry) {
        status = "Mengunduh ${entry.name}..."
        val err = withContext(Dispatchers.IO) {
            try { StageRepository.download(app, entry); null } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
        }
        if (err != null) { status = "Gagal unduh panggung: $err"; return }
        val d = withContext(Dispatchers.IO) { loadCached(app, entry.id) }
        if (d == null) { status = "Panggung terunduh tetapi tidak terbaca"; return }
        def = d
        status = ""
        update(app) { it.copy(stageId = entry.id) }
    }
}

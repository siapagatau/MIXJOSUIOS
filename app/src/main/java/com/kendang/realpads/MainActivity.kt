package com.kendang.realpads

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kendang.realpads.skin.MixerBridge
import com.kendang.realpads.skin.SkinBinding
import com.kendang.realpads.skin.SkinHost
import com.kendang.realpads.skin.SkinLoadingScreen
import com.kendang.realpads.skin.SkinManager
import com.kendang.realpads.skin.SkinPickerDialog
import com.kendang.realpads.skin.SkinUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var hasMicPermission by mutableStateOf(false)

    // True kalau user menekan switch Floating sebelum izin mic ada - begitu
    // izin diberikan, alur Floating dilanjutkan otomatis.
    private var pendingFloating = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted && pendingFloating) requestFloatingMode()
        pendingFloating = false
    }

    // Panel floating butuh 2 izin: notifikasi (Android 13+, wajib buat
    // foreground service-nya) dan "gambar di atas app lain". Alurnya:
    // minta notifikasi dulu (kalau perlu) -> baru cek/minta izin overlay ->
    // baru nyalain FloatingWindowService. Ini SAMA SEKALI gak nyentuh
    // AudioEngine, jadi gak ada risiko nambah delay mic.
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> proceedToOverlayCheck() }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (Settings.canDrawOverlays(this)) startFloatingService(this) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    fun requestFloatingMode() {
        // Foreground service bertipe microphone WAJIB punya izin RECORD_AUDIO
        // (Android 14+ langsung crash kalau belum), jadi minta dulu.
        if (!hasMicPermission) {
            pendingFloating = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        proceedToOverlayCheck()
    }

    private fun proceedToOverlayCheck() {
        if (Settings.canDrawOverlays(this)) {
            startFloatingService(this)
        } else {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // Landscape penuh: sembunyikan status/navigation bar (muncul sementara bila digeser dari tepi).
    private fun applyImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()   // dialog/izin bisa mengembalikan bar -> sembunyikan lagi
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seperti DTX: konten edge-to-edge, jarak aman diatur sendiri lewat safeDrawingPadding.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Output mic->speaker memakai Usage::Media (lihat openStreams() di
        // KaraokeEngine.cpp), jadi tombol fisik volume harus ke STREAM_MUSIC.
        // Dulu diarahkan ke STREAM_ALARM (sisa percobaan lama) -> tombol
        // malah menaikkan volume alarm, bukan suara mic.
        volumeControlStream = AudioManager.STREAM_MUSIC

        ThemeState.load(this)

        hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                MixerScreen(
                    hasMicPermission = hasMicPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onRequestFloatingMode = { requestFloatingMode() },
                )
            }
        }

        // Minta izin mic langsung saat app pertama kali dibuka.
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Visual sentuhan global (sama dengan APK DTX): overlay di atas seluruh konten, tidak menangani input.
        val fxOverlay = TouchFxOverlay(this)
        touchFxOverlay = fxOverlay
        addContentView(
            fxOverlay,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val appCtx = applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val loaded = TouchFxState.loadFromDisk(appCtx)
            android.os.Handler(android.os.Looper.getMainLooper()).post { TouchFxState.applyLoaded(loaded) }
        }
    }

    private var touchFxOverlay: TouchFxOverlay? = null

    // Hanya MEMBACA event setelah jalur normal (kontrol/audio/UI) selesai - tidak mengubah,
    // menunda, atau mengonsumsi sentuhan.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        try { touchFxOverlay?.onTouch(ev) } catch (_: Throwable) {}
        return handled
    }

    override fun onDestroy() {
        super.onDestroy()
        // Lepas engine hanya bila panel floating tidak sedang memakainya (floating tetap boleh jalan tanpa layar utama).
        if (!FloatingWindowService.running.value) {
            AudioEngine.nativeShutdown()
            YtWeb.destroy()   // pemutar YouTube ikut berhenti bila app benar-benar ditutup
        }
    }
}

fun startFloatingService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, FloatingWindowService::class.java))
}

fun stopFloatingService(context: Context) {
    context.stopService(Intent(context, FloatingWindowService::class.java))
}

// Knob EQ 0..1 -> gain asli -15..+15 dB (0.5 = tengah = 0dB/flat), persis
// rentang EQ channel strip Yamaha MG yang jadi acuan fitur ini.
fun eqKnobToDb(knob: Float): Float = (knob - 0.5f) * 30f
fun dbToEqKnob(db: Float): Float = db / 30f + 0.5f

// ---------------------------------------------------------------------------
// Kelompok mixer: MIC, REVERB, ECHO, EQ MIC, EQ AUDIO. Semua kelompok tampil SEKALIGUS (tanpa halaman) lewat MixerGrid (MixerPanel.kt),
// tiap kelompok punya judul + saklar ON/OFF sendiri. Nilai berkunci nama function -> sinkron dengan skin & panel floating.
// ---------------------------------------------------------------------------
class MixControl(val fn: String, val label: String, val bipolar: Boolean = false)   // bipolar = knob EQ (tengah = 0 dB)
class MixGroup(val title: String, val toggleFn: String, val controls: List<MixControl>)

val MixGroups: List<MixGroup> = listOf(
    MixGroup("MIC", "mic.toggle", listOf(MixControl("mic.volume", "VOLUME"), MixControl("mic.gate", "GATE"))),
    MixGroup(
        "REVERB", "reverb.toggle",
        listOf(
            MixControl("reverb.wet", "WET"), MixControl("reverb.dry", "DRY"), MixControl("reverb.width", "WIDTH"),
            MixControl("reverb.damp", "DAMP"), MixControl("reverb.room", "ROOM"), MixControl("reverb.cess", "CESS")
        )
    ),
    MixGroup(
        "ECHO", "echo.toggle",
        listOf(MixControl("echo.delay", "DELAY"), MixControl("echo.feedback", "FEEDBACK"), MixControl("echo.mix", "MIX"))
    ),
    MixGroup(
        "EQ MIC", "eqmic.toggle",
        listOf(MixControl("eqmic.low", "LOW", true), MixControl("eqmic.mid", "MID", true), MixControl("eqmic.high", "HIGH", true))
    ),
    MixGroup(
        "EQ AUDIO", "eqaudio.toggle",
        listOf(
            MixControl("eqaudio.gain", "GAIN", true), MixControl("eqaudio.sub", "SUB", true), MixControl("eqaudio.low", "LOW", true),
            MixControl("eqaudio.mid", "MID", true), MixControl("eqaudio.high", "HIGH", true)
        )
    ),
)

fun formatMixValue(fn: String, v: Float): String =
    if (fn.startsWith("eqmic.") || fn.startsWith("eqaudio.")) {
        val d = eqKnobToDb(v).roundToInt()
        if (d > 0) "+$d dB" else "$d dB"
    } else "${(v * 100).roundToInt()}%"

@Composable
fun MixerScreen(
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRequestFloatingMode: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val floatingOn by FloatingWindowService.running.collectAsState()
    var showTheme by remember { mutableStateOf(false) }
    var showSkinPicker by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    // Satu layar = satu mode (sidebar). Mode awal: Channel.
    var tab by rememberSaveable { mutableStateOf(AppTab.Channel) }

    LaunchedEffect(Unit) { SkinManager.ensureLoaded(context) }
    // Skin gagal dimuat/dirender -> jatuh ke tampilan klasik; tampilkan alasannya (seperti APK DTX).
    val skinFailedReason = (SkinManager.state as? SkinManager.State.Failed)?.reason
    LaunchedEffect(skinFailedReason) {
        if (skinFailedReason != null) Toast.makeText(context, "Skin gagal dipakai: $skinFailedReason", Toast.LENGTH_LONG).show()
    }

    // Background gambar (Tema -> Background): dibaca & disalin ke internal storage (sama dengan DTX).
    LaunchedEffect(Unit) {
        val loadedBg = withContext(Dispatchers.IO) { BackgroundState.loadFromDisk(context) }
        BackgroundState.applyLoaded(loadedBg)
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                val decoded = bytes?.let { b -> withContext(Dispatchers.IO) { BackgroundState.decodeAndSave(context, b) } }
                if (decoded != null) BackgroundState.applyImage(context, decoded)
                else Toast.makeText(context, "Gagal muat gambar background", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val touchFxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.let { TouchFxState.decodeAndSave(context, it) }
                }
                if (bmp != null) TouchFxState.applyImage(bmp)
                else Toast.makeText(context, "Gagal muat gambar sentuhan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val skinUi = remember { SkinUiState() }
    val bridge = remember { MixerBridge(context.applicationContext, skinUi) }
    val onFloat = { if (floatingOn) stopFloatingService(context) else onRequestFloatingMode() }
    // REVERB AUDIO sekarang bekerja di pemutar YouTube app ini (Web Audio) - cukup saklar, tanpa izin rekam layar.
    val onSysReverbToggle = { YtReverb.toggle(context) }
    SideEffect {
        bridge.onSysReverb = onSysReverbToggle
        bridge.hasMicPermission = { hasMicPermission }
        bridge.requestMicPermission = onRequestPermission
        bridge.onFloating = onFloat
        bridge.onTheme = { showTheme = true }
        bridge.onSkinPick = { showSkinPicker = true }
    }
    SkinBinding(bridge, hasMicPermission)

    // Status awal tiap bagian dimuat sekali (nilai tersimpan). Memuat TIDAK mengubah saklar efek apa pun.
    remember {
        Eq31Ui.ensureLoaded(context); CompUi.ensureLoaded(context); FxUi.ensureLoaded(context)
        StageUi.ensureLoaded(context); YtUi.ensureLoaded(context); ChUi.ensureLoaded(context)
        true
    }
    // YouTube: pemutar (dan efek di dalamnya) hanya hidup selama layar YouTube dibuka - sama seperti tombol YOUTUBE lama.
    // Pindah ke layar lain = pemutar ditutup; channel yang butuh YouTube diredupkan ("Butuh YouTube").
    LaunchedEffect(tab) {
        if (tab == AppTab.Yt) YtUi.setMode(context, true)
        else if (YtUi.mode) YtUi.setMode(context, false)
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppSidebar(tab, { tab = it })
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            AppNavBar(
                title = tab.title,
                floatingOn = floatingOn,
                onFloating = onFloat,
                onTheme = { showTheme = true },
                onSkin = { showSkinPicker = true },
                onResetAudio = { showResetConfirm = true }
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val skinState = SkinManager.state
                val activeSkin = (skinState as? SkinManager.State.Active)?.skin
                val loadingSkin = skinState is SkinManager.State.Loading && SkinManager.selectedId(context) != SkinManager.CLASSIC_ID
                val bgImage = BackgroundState.imageBitmap
                if (bgImage != null) {
                    Image(
                        bitmap = bgImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = BackgroundState.opacity,
                        modifier = Modifier.matchParentSize()
                    )
                }
                val cardOpacity = if (bgImage != null) BackgroundState.padOpacity else 1f
                when (tab) {
                    AppTab.Channel -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        val body = (maxHeight - ScreenPadding * 2).coerceAtLeast(240.dp)
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
                            ChannelSection(skinUi, bridge, cardOpacity, onSysReverbToggle, Modifier.fillMaxWidth(), bodyHeight = body)
                        }
                    }
                    AppTab.Mic, AppTab.Audio -> when {
                        // Skin aktif: skin tetap dirender Skin Engine (mixer klasik = Mikrofon + Audio dalam satu skin).
                        activeSkin != null -> SkinHost(
                            skin = activeSkin,
                            actions = bridge,
                            ui = skinUi,
                            onFatal = { SkinManager.reportRenderFailure(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                        loadingSkin -> SkinLoadingScreen()
                        tab == AppTab.Mic -> MicScreen(skinUi, bridge, Modifier.fillMaxSize(), cardOpacity)
                        else -> AudioScreen(skinUi, bridge, onSysReverbToggle, Modifier.fillMaxSize(), cardOpacity)
                    }
                    AppTab.Comp -> ScrollScreen { CompressorSection(skinUi, bridge, cardOpacity, Modifier.fillMaxWidth()) }
                    AppTab.Eq -> ScrollScreen { EqualizerSection(skinUi, bridge, cardOpacity, Modifier.fillMaxWidth()) }
                    AppTab.Fx -> ScrollScreen { FxSection(cardOpacity, Modifier.fillMaxWidth()) }
                    AppTab.Yt -> YoutubeScreen(floatingOn, cardOpacity, onSysReverbToggle)
                    AppTab.Stage -> ScrollScreen { StageSection(Modifier.fillMaxWidth(), editable = true) }
                }
            }
        }
    }

    if (showSkinPicker) SkinPickerDialog(onDismiss = { showSkinPicker = false })

    if (showResetConfirm) {
        IosAlert(
            title = "Reset audio",
            message = "Semua knob dan saklar audio kembali ke nilai awal: mixer, reverb audio, kompresor, equalizer 31 band, dan FX. " +
                "Tema, skin, dan isi panggung tidak berubah.",
            cancelText = "Batal",
            confirmText = "Reset",
            destructive = true,
            onCancel = { showResetConfirm = false },
            onConfirm = {
                showResetConfirm = false
                resetAllAudioSettings(context)
                bridge.syncAll()
                Toast.makeText(context, "Semua setelan audio direset", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showTheme) {
        ColorPickerDialog(
            initial = ThemeState.accentColor,
            backgroundImage = BackgroundState.imageBitmap,
            backgroundOpacity = BackgroundState.opacity,
            padOpacity = BackgroundState.padOpacity,
            onDismiss = { showTheme = false },
            onConfirm = { color ->
                ThemeState.set(context, color)
                showTheme = false
            },
            onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
            onClearBackground = { BackgroundState.clearImage(context) },
            onOpacityChange = { BackgroundState.setOpacity(context, it) },
            onPadOpacityChange = { BackgroundState.setPadOpacity(context, it) },
            touchFxPreview = TouchFxState.preview,
            touchFxSizeDp = TouchFxState.sizeDp,
            onPickTouchFx = { touchFxPicker.launch(arrayOf("image/*")) },
            onClearTouchFx = { TouchFxState.clearImage(context) },
            onTouchFxSizeChange = { TouchFxState.setSize(context, it) },
            touchFxFadeMs = TouchFxState.fadeMs,
            onTouchFxFadeChange = { TouchFxState.setFade(context, it) }
        )
    }
}

/** Layar yang isinya lebih tinggi dari ruang yang ada: menggulir vertikal (tanpa bilah gulir), padding layar 14 dp. */
@Composable
private fun ScrollScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        content = content
    )
}

/** Layar YouTube: pemutar di kiri, kontrol efek (reverb, delay, kirim, output) di kanan. Aturan lama: mati otomatis saat Floating aktif. */
@Composable
private fun YoutubeScreen(floatingOn: Boolean, opacity: Float, onSysReverbToggle: () -> Unit) {
    if (floatingOn) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "YouTube dimatikan otomatis saat Floating aktif.\nMatikan Floating untuk memakai pemutar.",
                color = LabelSecondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(ScreenPadding)) {
        val wide = maxWidth >= 560.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                YoutubePanel(YtHost.MAIN, Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(CardGap)
                ) { SysReverbNarrow(opacity, onSysReverbToggle, Modifier.fillMaxWidth()) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CardGap)) {
                YoutubePanel(YtHost.MAIN, Modifier.fillMaxWidth().height(300.dp))
                SysReverbNarrow(opacity, onSysReverbToggle, Modifier.fillMaxWidth())
            }
        }
    }
}


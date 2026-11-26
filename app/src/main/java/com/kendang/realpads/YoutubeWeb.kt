package com.kendang.realpads

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

// ---------------------------------------------------------------------------------------------------------------------
// YOUTUBE DI DALAM APP: browser mini yang langsung membuka youtube.com, tampil POTRET di SAMPING panggung (lihat YoutubePanel.kt).
//
// - YouTube dibiarkan NORMAL (tanpa pemblokir iklan / tanpa layar penuh khusus) supaya ringan dan tidak merusak pemutar.
// - SATU WebView dipakai bersama (YtWeb). Layar utama dan panel floating sama-sama menampilkan YoutubePanel, tapi WebView
//   hanya bisa ada di satu tempat: YtHost menentukan siapa yang memegangnya SECARA OTOMATIS (yang terakhir tampil/dibuka
//   menang), jadi pindah ke mode floating / kembali ke app tidak perlu klik apa pun.
// - Reverb: lihat YtReverb.kt (Web Audio di dalam halaman, tanpa rekam layar).
// ---------------------------------------------------------------------------------------------------------------------

/** Pengaturan tampilan YouTube (mode di strip bawah), tersimpan. */
object YtUi {
    private const val PREFS = "yt_prefs"
    var mode by mutableStateOf(false)
        private set
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = p.getBoolean("mode", false)
        loaded = true
    }

    fun setMode(context: Context, on: Boolean) {
        ensureLoaded(context)
        // Floating aktif = YouTube in-app tidak boleh menyala (efek audio global): permintaan menyalakan otomatis ditolak.
        if (on && FloatingWindowService.running.value) {
            Toast.makeText(context.applicationContext, "YouTube dimatikan otomatis saat floating aktif", Toast.LENGTH_SHORT).show()
            return
        }
        mode = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("mode", mode).apply()
        // Mode dimatikan = pemutar ditutup total (video berhenti, memori dilepas).
        if (!on) YtWeb.destroy()
        // Mode nyala = semua efek (EQ, kompresor, FX, reverb) dikerjakan di dalam pemutar, efek session 0 dilepas.
        GlobalEqEngine.setBypass(on)
    }
}

/**
 * Aturan navigasi: browser khusus YouTube, tapi LOGIN Google harus bisa jalan sampai selesai.
 * Alur login memakai banyak alamat (accounts.google.co.id, gstatic, penyedia SSO kantor/sekolah, dsb) dan perpindahan sementara
 * (about:blank, data:). Karena itu, selama alur login berlangsung ([loginFlow]) semua halaman web boleh dimuat; begitu kembali ke
 * YouTube, pembatasan berlaku lagi. Di luar login, link keluar YouTube tetap ditolak.
 */
object YtNav {
    private val navHosts = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "google.com", "g.co",
        "gstatic.com", "googleapis.com", "googleusercontent.com", "ggpht.com", "recaptcha.net",
    )
    // google.co.id, google.com.sg, google.de, dst. (domain Google per negara)
    private val googleCountry = Regex("(^|\\.)google\\.([a-z]{2,3}|co\\.[a-z]{2}|com\\.[a-z]{2})$")

    /** Sedang dalam alur login Google (diisi oleh WebViewClient). */
    @Volatile var loginFlow = false

    private fun hostOf(u: Uri) = u.host?.lowercase()

    fun isTrustedHost(host: String): Boolean =
        navHosts.any { host == it || host.endsWith(".$it") } || googleCountry.containsMatchIn(host)

    /** Halaman akun Google (awal alur login). */
    fun isAccountsPage(u: Uri): Boolean {
        val host = hostOf(u) ?: return false
        return host.startsWith("accounts.") && isTrustedHost(host)
    }

    /** Halaman YouTube biasa (bukan halaman akun): alur login dianggap selesai. */
    fun isYoutubePage(u: Uri): Boolean {
        val host = hostOf(u) ?: return false
        if (host.startsWith("accounts.")) return false
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    /** Skema internal yang aman dan dipakai halaman login (tidak membuka app/situs lain). */
    fun isInternalScheme(u: Uri): Boolean =
        when (u.scheme?.lowercase()) { "about", "data", "blob", "javascript" -> true else -> false }

    fun isWeb(u: Uri): Boolean {
        val scheme = u.scheme?.lowercase()
        return scheme == "https" || scheme == "http"
    }

    /** Boleh dimuat di panel ini? Memperbarui [loginFlow]. */
    fun allowNavigation(u: Uri): Boolean {
        if (isInternalScheme(u)) return true
        if (!isWeb(u)) return false              // intent://, market://, dst: tidak membuka app lain
        val host = hostOf(u) ?: return false
        if (isAccountsPage(u)) { loginFlow = true; return true }
        if (isYoutubePage(u)) { loginFlow = false; return true }
        if (isTrustedHost(host)) return true
        return loginFlow                          // penyedia SSO / halaman perantara login
    }
}

/**
 * Siapa yang memegang WebView sekarang: "main" (layar utama) atau "float" (panel floating). Yang terakhir mengklaim = aktif.
 * PENTING: id "main" dan "float" TIDAK BOLEH tertukar/bentrok - tiap jendela selalu memakai id-nya sendiri (lihat parameter hostId).
 */
object YtHost {
    const val MAIN = "main"
    const val FLOAT = "float"

    private val claims = mutableStateListOf<String>()
    val active: String? get() = claims.lastOrNull()

    private val handler = Handler(Looper.getMainLooper())
    private var blurJob: Runnable? = null

    /** Ada kolom teks di halaman YouTube yang sedang fokus (diisi skrip halaman). */
    @Volatile var inputFocused = false
        private set

    fun claim(id: String) {
        claims.remove(id)
        claims.add(id)
        // Pemegang berganti -> keyboard jendela floating tidak perlu tertinggal.
        if (id != FLOAT) FloatingWindowService.setInputFocusable(false)
    }

    fun release(id: String) {
        claims.remove(id)
        if (id == FLOAT) {
            cancelBlur()
            inputFocused = false
            FloatingWindowService.setInputFocusable(false)
        }
    }

    private fun cancelBlur() {
        blurJob?.let { handler.removeCallbacks(it) }
        blurJob = null
    }

    /**
     * Kolom teks di halaman fokus/lepas. Hanya jendela floating yang perlu dibuat bisa menerima keyboard.
     * "Lepas" ditunda sebentar: YouTube sering melepas lalu memfokuskan kolom yang sama saat mengetik (hindari keyboard berkedip).
     */
    fun onInput(focus: Boolean) {
        cancelBlur()
        if (focus) {
            inputFocused = true
            if (active == FLOAT) FloatingWindowService.setInputFocusable(true)
        } else {
            val r = Runnable {
                inputFocused = false
                FloatingWindowService.setInputFocusable(false)
            }
            blurJob = r
            handler.postDelayed(r, 350)
        }
    }
}

/** Objek yang dilihat skrip halaman sebagai "MixjosAndroid". Hanya halaman YouTube/Google yang boleh dibuka (lihat YtNav). */
class YtBridge {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun onLevel(ms: Double) { YtReverb.meanSquare = ms.toFloat() }
    /** Level sinyal di pemutar SETELAH fader volume YouTube (channel YOUTUBE): rata-rata kuadrat, dikirim ~20x/dtk. */
    @JavascriptInterface fun onLevelIn(ms: Double) { YtReverb.inMeanSquare = ms.toFloat() }
    @JavascriptInterface fun onReady() { main.post { YtReverb.pushNow() } }
    @JavascriptInterface fun onInput(focus: Boolean) { main.post { YtHost.onInput(focus) } }
    /** Posisi putar video utama (dikirim halaman tiap detik); dipakai melanjutkan video saat pemutar dibuat ulang di jendela lain. */
    @JavascriptInterface fun onTime(sec: Double, playing: Boolean) { YtWeb.lastTime = sec; YtWeb.lastPlaying = playing }
}

/** WebView YouTube tunggal. Semua fungsi dipanggil dari thread utama. */
object YtWeb {
    const val HOME = "https://m.youtube.com/"

    private var view: WebView? = null
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var docStartScript = false

    /** Jendela yang terakhir memegang WebView ("main"/"float"). Pindah jendela = pemutar dibuat ulang (lihat attach). */
    private var attachedHost: String? = null
    @Volatile var lastTime = 0.0
    @Volatile var lastPlaying = false

    var canBack by mutableStateOf(false)
        private set
    var canForward by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set

    /**
     * Tempelkan WebView ke [holder] milik jendela [hostId].
     * PINDAH JENDELA (layar utama <-> floating): WebView yang sama dicopot lalu dipasang di jendela lain membuat pemutarnya rusak
     * (video langsung berhenti tiap di-play). Maka saat jendela berganti, WebView lama dibuang dan dibuat baru di jendela tujuan,
     * lalu halaman yang sama dibuka lagi dan videonya dilanjutkan dari menit terakhir (cookie/login tetap ada).
     */
    fun attach(context: Context, holder: ViewGroup, hostId: String) {
        val app = context.applicationContext
        appCtx = app
        SysReverbUi.ensureLoaded(app)
        var start = HOME
        val old = view
        // Dibuat ulang bila (a) pindah jendela, atau (b) WebView yang sudah pernah terpasang DICOPOT lalu dipasang lagi (parent == null):
        // itu terjadi saat tata letak berganti tanpa pindah jendela, mis. floating di-RESIZE melewati batas lebar sehingga panel
        // YouTube berpindah dari "di tengah" ke "di samping panggung" (komposisi lama dibuang, holder baru). WebView yang dipindah
        // parent-nya begini juga membuat pemutarnya rusak (video langsung berhenti tiap di-play), jadi diperlakukan sama.
        if (old != null && ((attachedHost != null && attachedHost != hostId) || old.parent == null)) {
            start = resumeUrl(old)
            destroy()
        }
        val v = view ?: create(app, start).also { view = it }
        attachedHost = hostId
        (v.parent as? ViewGroup)?.let { if (it !== holder) it.removeView(v) }
        if (v.parent == null) {
            holder.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        v.onResume()
        v.resumeTimers()
        refreshNav()
    }

    /** Alamat halaman sekarang; bila sedang menonton, ditambah &t=detik supaya video lanjut dari posisi terakhir. */
    private fun resumeUrl(v: WebView): String {
        val u = v.url ?: return HOME
        if (!u.startsWith("http")) return HOME
        val t = lastTime.toInt()
        if (t < 3 || !u.contains("/watch")) return u
        return runCatching {
            val uri = Uri.parse(u)
            val b = uri.buildUpon().clearQuery()
            for (n in uri.queryParameterNames) {
                if (n == "t" || n == "start") continue
                for (x in uri.getQueryParameters(n)) b.appendQueryParameter(n, x)
            }
            b.appendQueryParameter("t", t.toString() + "s").build().toString()
        }.getOrDefault(u)
    }

    /** Lepas dari [holder]. Sengaja TIDAK dijeda: suara tetap jalan saat panel dipindah / app ke latar. */
    fun detach(holder: ViewGroup) {
        val v = view ?: return
        if (v.parent === holder) holder.removeView(v)
    }

    fun destroy() {
        val v = view ?: return
        view = null
        main.removeCallbacks(showKb)
        runCatching {
            (v.parent as? ViewGroup)?.removeView(v)
            v.stopLoading()
            v.loadUrl("about:blank")
            v.destroy()
        }
        canBack = false; canForward = false; loading = false
        YtReverb.meanSquare = 0f
        attachedHost = null
        YtNav.loginFlow = false
    }

    fun back() { view?.let { if (it.canGoBack()) it.goBack() } }
    fun forward() { view?.let { if (it.canGoForward()) it.goForward() } }
    fun home() { view?.loadUrl(HOME) }
    fun reload() { view?.reload() }

    fun eval(js: String) {
        main.post { view?.evaluateJavascript(js, null) }
    }

    // ---- Keyboard jendela floating ----
    // Jendela floating baru bisa menerima keyboard SETELAH flag NOT_FOCUSABLE dicopot dan jendelanya benar-benar mendapat fokus
    // (asinkron, beberapa puluh ms). Karena itu showSoftInput diulang beberapa kali sampai berhasil, selama kolom teks masih fokus.
    private var kbTries = 0
    private val showKb = object : Runnable {
        override fun run() {
            val v = view ?: return
            if (!YtHost.inputFocused) return
            v.requestFocus()
            val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (kbTries == 0) imm?.restartInput(v)
            imm?.showSoftInput(v, 0)
            kbTries++
            if (kbTries < 6) main.postDelayed(this, 150)
        }
    }

    fun showKeyboard() {
        main.removeCallbacks(showKb)
        kbTries = 0
        main.post(showKb)
    }

    fun hideKeyboard() {
        main.removeCallbacks(showKb)
        val v = view ?: return
        (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Lepas fokus kolom teks di halaman (dipakai saat keyboard ditutup, supaya jendela floating kembali tidak mencuri fokus). */
    fun blurInput() {
        eval("try{var a=document.activeElement;if(a&&a.blur)a.blur();}catch(e){}")
    }

    private fun refreshNav() {
        val v = view ?: return
        canBack = v.canGoBack()
        canForward = v.canGoForward()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(app: Context, startUrl: String = HOME): WebView {
        YtUi.ensureLoaded(app)
        // Konteks aplikasi (bukan Activity/Service) supaya WebView aman dipindah antara layar utama dan floating.
        val wv = WebView(MutableContextWrapper(app))
        wv.setBackgroundColor(android.graphics.Color.BLACK)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Wajib false: tanpa ini AudioContext (reverb) dan video tidak mau jalan otomatis.
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            // Tanda "; wv" membuat YouTube menganggap ini WebView app -> hilangkan supaya tampil seperti browser biasa.
            // "Version/4.0" juga ciri WebView: Google menolak login (disallowed_useragent) bila masih ada.
            userAgentString = userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Login Google (langganan/Premium) butuh cookie pihak ketiga.
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.addJavascriptInterface(YtBridge(), "MixjosAndroid")
        docStartScript = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(wv, YtScripts.ALL, setOf("https://*.youtube.com", "https://youtube.com"))
                docStartScript = true
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (YtNav.allowNavigation(request.url)) return false
                // Link keluar YouTube ditolak (ini browser khusus YouTube). Pemberitahuan hanya bila pengguna yang mengetuk link web;
                // pengalihan otomatis / skema internal (intent://, market://) ditolak diam-diam supaya tidak muncul saat login Google.
                if (request.hasGesture() && !request.isRedirect && YtNav.isWeb(request.url)) {
                    main.post { appCtx?.let { Toast.makeText(it, "Hanya YouTube yang bisa dibuka di sini", Toast.LENGTH_SHORT).show() } }
                }
                return true
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                loading = true
                lastTime = 0.0
                // Kembali ke YouTube (atau halaman dimuat lewat loadUrl tanpa melewati shouldOverrideUrlLoading) -> sinkronkan status login.
                url?.let { u -> runCatching { Uri.parse(u) }.getOrNull()?.let { if (YtNav.isAccountsPage(it)) YtNav.loginFlow = true else if (YtNav.isYoutubePage(it)) YtNav.loginFlow = false } }
                // Perangkat tanpa dukungan document-start: suntik terlambat.
                if (!docStartScript) v.evaluateJavascript(YtScripts.ALL, null)
                YtReverb.pushNow()
                refreshNav()
            }

            override fun onPageFinished(v: WebView, url: String?) {
                loading = false
                if (!docStartScript) v.evaluateJavascript(YtScripts.ALL, null)
                YtReverb.pushNow()
                refreshNav()
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                refreshNav()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            // Layar penuh bawaan YouTube tidak didukung (tombolnya disembunyikan skrip halaman).
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) { callback?.onCustomViewHidden() }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        wv.loadUrl(startUrl)
        return wv
    }
}

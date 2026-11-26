package com.kendang.realpads

// ---------------------------------------------------------------------------------------------------------------------
// SKRIP YANG DISUNTIKKAN KE HALAMAN YOUTUBE (WebView). Dipasang sebelum skrip halaman jalan (document-start).
//
// 1) BASE     - perapihan ringan, TANPA pemblokir iklan (YouTube dibiarkan normal supaya tetap ringan):
//      a. video tidak dijeda YouTube saat jendela tersembunyi (floating diminimalkan / app lain di depan)
//      b. sembunyikan tombol layar penuh (tidak didukung) dan tombol "Buka Apl" (bikin header sempit berhimpitan)
//      c. beri tahu Kotlin saat kolom teks (cari) fokus/lepas -> jendela floating dibuat bisa menerima keyboard
// 2) AUDIO    - SEMUA efek suara pemutar, memakai Web Audio API DI DALAM pemutar (bukan efek session 0), supaya ikut terekam
//      saat rekam layar: reverb + delay (kirim per band Sub/Low/Mid/High), lalu EQ AUDIO + EQ 31 band (L/R), kompresor,
//      parametrik, keseimbangan/volume, 8D pan, tube drive, limiter. Tidak ada rekam layar / MediaProjection: suara video
//      diambil langsung dari elemen <video> lewat createMediaElementSource, diproses, lalu diputar. Setelan (semua knob)
//      dikirim Kotlin lewat window.__mixjos.set (YtReverb.pushNow + YtFx.json). Modul yang OFF tidak disambung.
//
// Jembatan ke Kotlin: objek "MixjosAndroid" (YtBridge). Jangan memakai tanda dolar di sini (raw string Kotlin).
// ---------------------------------------------------------------------------------------------------------------------

object YtScripts {

    const val BASE = """
(function(){
  if (window.__mjBase) return; window.__mjBase = true;

  // ===== DIAGNOSA SEMENTARA: tulisan hijau kecil di kiri-bawah halaman (DBG = false untuk mematikan) =====
  // Menunjukkan siapa yang menghentikan video: "src=JS" = skrip YouTube sendiri memanggil pause(); "src=NATIF" = pemutar/WebView
  // menjeda tanpa dipanggil skrip (mis. fokus audio / jendela tersembunyi). Juga mencatat permintaan layar penuh dan status audio.
  var DBG = false, T0 = Date.now(), lines = [], hud = null, jsPause = 0, lastTouchB = 0, pauseFix = [];
  function render(){
    try {
      if (!DBG || !document.documentElement) return;
      if (!hud || !hud.isConnected) {
        hud = document.createElement('div');
        hud.style.cssText = 'position:fixed;left:0;bottom:0;max-width:100%;z-index:2147483647;pointer-events:none;font:9px/1.25 monospace;color:#0f0;background:rgba(0,0,0,.65);padding:2px 3px;white-space:pre-wrap;word-break:break-all';
        document.documentElement.appendChild(hud);
      }
      hud.textContent = lines.join('\n');
    } catch(e){}
  }
  function dbg(m){
    var t = ((Date.now() - T0) / 1000).toFixed(1);
    lines.push(t + ' ' + m); if (lines.length > 10) lines.shift(); render();
  }
  window.__mjLog = dbg;
  dbg('skrip aktif');

  document.addEventListener('touchstart', function(){ lastTouchB = Date.now(); }, true);
  document.addEventListener('click', function(){ lastTouchB = Date.now(); }, true);

  // Catat siapa yang memanggil pause().
  try {
    var origPause = HTMLMediaElement.prototype.pause;
    HTMLMediaElement.prototype.pause = function(){
      if (this.tagName === 'VIDEO') {
        jsPause = Date.now();
        var st = ''; try { st = (new Error().stack || '').split('\n')[2] || ''; } catch(e){}
        dbg('JS pause() ' + st.slice(-70));
      }
      return origPause.apply(this, arguments);
    };
  } catch(e){}

  // Video dijeda TANPA panggilan pause() dari skrip dan tanpa sentuhan pengguna = dijeda dari luar -> lanjutkan (maks 4x / 10 dtk).
  function autoResume(v){
    try {
      if (Date.now() - jsPause < 400) return;
      if (v.ended || Date.now() - lastTouchB < 1500) return;
      var now = Date.now();
      pauseFix = pauseFix.filter(function(x){ return now - x < 10000; });
      if (pauseFix.length >= 4) { dbg('auto-play menyerah'); return; }
      pauseFix.push(now);
      setTimeout(function(){
        if (!v.paused || v.ended) return;
        try { var p = v.play(); if (p && p.catch) p.catch(function(e){ dbg('play() ditolak ' + (e && e.name)); }); } catch(x){}
        dbg('auto-play');
      }, 120);
    } catch(e){}
  }

  ['play','playing','pause','waiting','stalled','error','ended','emptied'].forEach(function(n){
    document.addEventListener(n, function(e){
      var v = e.target; if (!v || v.tagName !== 'VIDEO') return;
      var x = '';
      if (n === 'error') x = ' code=' + (v.error ? v.error.code : '?');
      if (n === 'pause') x = ' src=' + (Date.now() - jsPause < 400 ? 'JS' : 'NATIF') + ' t=' + v.currentTime.toFixed(1);
      dbg('v:' + n + x);
      if (n === 'pause') autoResume(v);
    }, true);
  });
  ['pagehide','pageshow','freeze','resume','blur','focus'].forEach(function(n){
    window.addEventListener(n, function(){ dbg('win:' + n); }, true);
  });
  ['fullscreenchange','webkitfullscreenchange'].forEach(function(n){
    document.addEventListener(n, function(){ dbg('fullscreenchange'); }, true);
  });
  // ===== akhir diagnosa =====

  // Jangan biarkan YouTube menjeda video saat jendela tersembunyi.
  try {
    Object.defineProperty(document, 'hidden', { get: function(){ return false; }, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; }, configurable: true });
    document.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
  } catch(e){}

  // LAYAR PENUH tidak didukung di panel ini: WebChromeClient menutupnya lagi seketika. Masalahnya YouTube mobile MASUK layar penuh
  // sendiri begitu play ditekan saat layar HP landscape (app ini sensorLandscape) -> WebView langsung menutupnya -> YouTube
  // menjeda video -> "klik play langsung berhenti, berulang terus". Maka halaman diberi tahu bahwa layar penuh TIDAK tersedia,
  // dan semua permintaannya ditolak, sehingga YouTube tidak pernah mencoba dan video tetap jalan di dalam panel.
  try {
    var denyFs = function(){ dbg('minta layar penuh -> ditolak'); var p = Promise.reject(new TypeError('Fullscreen tidak tersedia di panel ini')); p.catch(function(){}); return p; };
    ['requestFullscreen','webkitRequestFullscreen','webkitRequestFullScreen','mozRequestFullScreen','msRequestFullscreen'].forEach(function(n){
      try { if (Element.prototype[n]) Object.defineProperty(Element.prototype, n, { value: denyFs, configurable: true, writable: true }); } catch(e){}
    });
    ['fullscreenEnabled','webkitFullscreenEnabled','webkitFullScreenEnabled'].forEach(function(n){
      try { Object.defineProperty(document, n, { get: function(){ return false; }, configurable: true }); } catch(e){}
    });
    if (window.HTMLVideoElement) {
      var nop = function(){};
      ['webkitEnterFullscreen','webkitEnterFullScreen'].forEach(function(n){
        try { Object.defineProperty(HTMLVideoElement.prototype, n, { value: nop, configurable: true, writable: true }); } catch(e){}
      });
      try { Object.defineProperty(HTMLVideoElement.prototype, 'webkitSupportsFullscreen', { get: function(){ return false; }, configurable: true }); } catch(e){}
    }
  } catch(e){}

  // Tombol layar penuh tidak didukung di panel ini -> sembunyikan supaya tidak jadi tombol mati.
  var styleEl = null;
  function addCss(){
    if (styleEl) return;
    styleEl = document.createElement('style');
    styleEl.textContent = '.ytp-fullscreen-button,.fullscreen-icon{display:none !important}';
    (document.head || document.documentElement).appendChild(styleEl);
  }
  if (document.documentElement) addCss(); else document.addEventListener('DOMContentLoaded', addCss);

  // Tombol "Buka Apl / Open app" di header: di panel sempit menimpa logo -> sembunyikan (dicek jarang, sangat ringan).
  var OPEN_APP = /^(buka apl|buka aplikasi|open app|open in app)/i;
  setInterval(function(){
    try {
      var els = document.querySelectorAll('header button, header a, ytm-mobile-topbar-renderer button, ytm-mobile-topbar-renderer a');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (el.__mjHide) continue;
        var t = (el.textContent || '').trim();
        if (t && t.length < 20 && OPEN_APP.test(t)) { el.style.setProperty('display', 'none', 'important'); el.__mjHide = true; }
      }
    } catch(e){}
  }, 2000);

  // Kolom teks (cari) fokus/lepas -> Kotlin mengatur keyboard jendela floating.
  function isEditable(t){
    return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable === true);
  }
  document.addEventListener('focusin', function(e){
    if (isEditable(e.target)) { try { MixjosAndroid.onInput(true); } catch(x){} }
  }, true);
  document.addEventListener('focusout', function(e){
    if (isEditable(e.target)) { try { MixjosAndroid.onInput(false); } catch(x){} }
  }, true);

  // Lapor posisi putar video utama tiap detik: dipakai Kotlin melanjutkan video saat pemutar dibuat ulang di jendela lain.
  setInterval(function(){
    try {
      var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
      if (v && v.readyState > 0 && isFinite(v.duration) && v.duration > 20) MixjosAndroid.onTime(v.currentTime, !v.paused);
      else MixjosAndroid.onTime(0, false);
    } catch(x){}
  }, 1000);
})();
"""

    const val AUDIO = """
(function(){
  if (window.__mixjos) return;
  var AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) return;

  // Pusat 31 band ISO (sama dengan Eq31.CENTERS), Q 1/3 oktaf, jumlah slot parametrik per sisi (Fx.MAX_BANDS).
  var CEN = [20,25,31.5,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000];
  var NB = 31, Q31 = 4.318, NP = 16;
  var TYPES = ['lowpass','highpass','bandpass','peaking','lowshelf','highshelf'];

  function zeros(n){ var a = []; for (var i = 0; i < n; i++) a.push(0); return a; }
  var S = { on:false, reverbOn:true, echoOn:false, wet:0.5, room:0.6, damp:0.4, width:1, cess:0.4, preDelay:0.2,
            echoDelay:0.45, echoFeedback:0.45, echoMix:0.4, gain:0.5, revSend:[0,0.3,0.8,1], echoSend:[0,0,0.7,0.7],
            vol:1, ch:false };   // vol = fader volume channel YOUTUBE (gain linear), ch = mode CHANNEL (ukur level untuk meter)
  // Efek lain (EQ AUDIO + EQ 31 band + kompresor + FX). Diisi Kotlin (YtFx.json).
  var X = { on:false, inDb:0, pre:[zeros(NB), zeros(NB)],
            comp:{ on:false, freq:19000, atk:10, rel:100, ratio:2, thr:-20, mk:0, post:[0,0] },
            peq:{ on:false, bands:[] }, tube:{ on:false, db:0 }, pan:{ on:false, depth:0, period:10 } };
  var ctx = null, input = null, mix = null, g = {}, M = {}, hooked = new WeakSet(), irTimer = 0, irKey = '', cur = null, linked = false, lastMs = 0, lastIn = 0, volSet = false;

  function L(m){ try { if (window.__mjLog) window.__mjLog(m); } catch(e){} }
  var lw = null;
  setInterval(function(){ var w = S.on || X.on; if (w !== lw) { lw = w; L('efek ' + (w ? 'ON' : 'OFF (audio tidak disambung)')); } }, 1000);

  function db2lin(d){ return Math.pow(10, d / 20); }
  function biq(type, f){ var b = ctx.createBiquadFilter(); b.type = type; b.frequency.value = f; b.Q.value = 0.7071; return b; }
  // Simpul gain stereo tetap (suara mono dinaikkan ke 2 kanal; splitter/merger di bawahnya selalu dapat L dan R).
  function sg(){ var n = ctx.createGain(); n.channelCount = 2; n.channelCountMode = 'explicit'; n.channelInterpretation = 'speakers'; return n; }

  function build(){
    if (ctx) return true;
    try { ctx = new AC({ latencyHint: 'playback' }); } catch(e){ try { ctx = new AC(); } catch(e2){ return false; } }
    L('ctx dibuat sr=' + ctx.sampleRate + ' ' + ctx.state);
    input = sg(); mix = sg();
    input.connect(mix);                              // suara asli lewat apa adanya (jalur kering)
    var sub = biq('lowpass', 90), hp1 = biq('highpass', 90), low = biq('lowpass', 400);
    var hp2 = biq('highpass', 400), mid = biq('lowpass', 4000), high = biq('highpass', 4000);
    input.connect(sub); input.connect(hp1); hp1.connect(low); input.connect(hp2); hp2.connect(mid); input.connect(high);
    var bands = [sub, low, mid, high];
    g.revBus = ctx.createGain(); g.echoBus = ctx.createGain(); g.rs = []; g.es = [];
    for (var i = 0; i < 4; i++) {
      var a = ctx.createGain(), b = ctx.createGain();
      bands[i].connect(a); a.connect(g.revBus);
      bands[i].connect(b); b.connect(g.echoBus);
      g.rs.push(a); g.es.push(b);
    }
    g.pre = ctx.createDelay(0.5); g.revOut = ctx.createGain();
    g.dly = ctx.createDelay(1.5); g.fb = ctx.createGain(); g.fbLp = biq('lowpass', 3500); g.echoOut = ctx.createGain();
    g.dly.connect(g.fbLp); g.fbLp.connect(g.fb); g.fb.connect(g.dly); g.dly.connect(g.echoOut);
    g.fx = ctx.createGain(); g.revOut.connect(g.fx); g.echoOut.connect(g.fx); g.fx.connect(mix);
    g.an = ctx.createAnalyser(); g.an.fftSize = 1024; g.fx.connect(g.an);
    g.buf = new Float32Array(1024);
    // Meter channel YOUTUBE: level sinyal sesudah fader volume (input = simpul gain volume), sebelum efek.
    g.anIn = ctx.createAnalyser(); g.anIn.fftSize = 1024; input.connect(g.anIn);
    g.bufIn = new Float32Array(1024);
    buildFx();
    route();
    return true;
  }

  // ---------------------------------------------------------------------------------------------------------------------
  // EFEK SESSION-0 YANG DIPINDAH KE HALAMAN. Urutan sama dengan DynamicsProcessing:
  //   gain masuk -> EQ 31 band (L/R terpisah) -> kompresor (band bawah Frekuensi) -> parametrik (L/R) + keseimbangan/volume/8D pan
  //   -> tube drive -> limiter pengaman.
  // Modul yang tidak dipakai TIDAK disambung (tanpa beban DSP); semua OFF = jalur kering langsung ke keluaran seperti sebelumnya.
  // ---------------------------------------------------------------------------------------------------------------------
  function buildFx(){
    var s, i, f, prev, sp, mg;
    M.ing = sg(); M.ing.id = "g"; M.ing.i = M.ing; M.ing.o = M.ing;

    // EQ 31 band
    var P = M.pre = { id:'p', i: sg(), o: sg(), f: [[], []], c: [[], []], key: '' };
    sp = ctx.createChannelSplitter(2); mg = ctx.createChannelMerger(2);
    P.i.connect(sp);
    for (s = 0; s < 2; s++) {
      prev = null;
      for (i = 0; i < NB; i++) {
        f = ctx.createBiquadFilter(); f.type = 'peaking'; f.frequency.value = Math.min(CEN[i], ctx.sampleRate * 0.45); f.Q.value = Q31; f.gain.value = 0;
        if (!prev) sp.connect(f, s, 0); else prev.connect(f);
        prev = f; P.f[s].push(f); P.c[s].push(0);
      }
      prev.connect(mg, 0, s);
    }
    mg.connect(P.o);

    // Kompresor: Frekuensi < ~19 kHz = hanya band bawah dikompresi (crossover Linkwitz-Riley 24 dB/oktaf), selebihnya lewat apa adanya.
    var C = M.comp = { id:'c', i: sg(), o: sg() };
    C.cin = sg(); C.full = ctx.createGain(); C.lowG = ctx.createGain(); C.highG = ctx.createGain();
    C.l = [biq('lowpass', 1000), biq('lowpass', 1000)]; C.h = [biq('highpass', 1000), biq('highpass', 1000)];
    // Q lowpass/highpass Web Audio dalam dB: -3,0103 dB = Q 0,7071 (Butterworth).
    for (i = 0; i < 2; i++) { C.l[i].Q.value = -3.0103; C.h[i].Q.value = -3.0103; }
    C.i.connect(C.full); C.full.connect(C.cin);
    C.i.connect(C.l[0]); C.l[0].connect(C.l[1]); C.l[1].connect(C.lowG); C.lowG.connect(C.cin);
    C.i.connect(C.h[0]); C.h[0].connect(C.h[1]); C.h[1].connect(C.highG); C.highG.connect(C.o);
    C.dc = ctx.createDynamicsCompressor(); C.dc.knee.value = 6; C.mk = ctx.createGain();
    C.cin.connect(C.dc); C.dc.connect(C.mk); C.mk.connect(C.o);
    C.full.gain.value = 1; C.lowG.gain.value = 0; C.highG.gain.value = 0;

    // Parametrik (slot tetap per sisi) + gain sisi (keseimbangan/volume/8D pan)
    var Q = M.side = { id:'s', i: sg(), o: sg(), f: [[], []], k: [[], []], g: [null, null] };
    sp = ctx.createChannelSplitter(2); mg = ctx.createChannelMerger(2);
    Q.i.connect(sp);
    for (s = 0; s < 2; s++) {
      prev = null;
      for (i = 0; i < NP; i++) {
        f = ctx.createBiquadFilter(); f.type = 'peaking'; f.frequency.value = 1000; f.Q.value = 1; f.gain.value = 0;
        if (!prev) sp.connect(f, s, 0); else prev.connect(f);
        prev = f; Q.f[s].push(f); Q.k[s].push('');
      }
      Q.g[s] = ctx.createGain(); prev.connect(Q.g[s]); Q.g[s].connect(mg, 0, s);
    }
    mg.connect(Q.o);

    // Tube drive = dorongan gain (dibatasi limiter di bawah, seperti LoudnessEnhancer).
    M.tube = sg(); M.tube.id = 't'; M.tube.i = M.tube; M.tube.o = M.tube;

    // Limiter pengaman (threshold -2 dB, rasio 20). DynamicsCompressor Web Audio menambah makeup otomatis (pangkat 0,6 dari
    // peredaman pada 0 dBFS) -> dibatalkan dengan gain 'fix' supaya level di bawah threshold tidak berubah.
    var L = M.lim = { id:'l', i: sg() };
    L.dc = ctx.createDynamicsCompressor();
    L.dc.threshold.value = -2; L.dc.knee.value = 0; L.dc.ratio.value = 20; L.dc.attack.value = 0.002; L.dc.release.value = 0.08;
    L.fix = ctx.createGain(); L.fix.gain.value = db2lin(0.6 * (-2 * (1 - 1 / 20)));
    L.i.connect(L.dc); L.dc.connect(L.fix); L.o = L.fix;
  }

  var routeKey = null;   // null = belum pernah disambung (jalur kering juga harus disambung sekali)
  function flags(){
    var F = X, nz = function(a){ for (var i = 0; i < a.length; i++) if (Math.abs(a[i]) > 0.05) return true; return false; };
    var on = !!F.on;
    return {
      on: on,
      pre: on && (nz(F.pre[0]) || nz(F.pre[1])),
      comp: on && !!F.comp.on,
      side: on && ((F.peq.on && F.peq.bands.length > 0) || !!F.pan.on || nz(F.comp.post)),
      tube: on && !!F.tube.on
    };
  }
  function route(){
    var fl = flags(), list = [];
    if (fl.on) {
      list.push(M.ing);
      if (fl.pre) list.push(M.pre);
      if (fl.comp) list.push(M.comp);
      if (fl.side) list.push(M.side);
      if (fl.tube) list.push(M.tube);
      list.push(M.lim);
    } else if (S.vol > 1.0001) {
      list.push(M.lim);                                // dorongan volume (+dB) tanpa efek lain: tetap dijaga limiter
    }
    var key = list.map(function(m){ return m.id; }).join('');
    if (key === routeKey) return;
    routeKey = key;
    try {
      mix.disconnect(); M.ing.disconnect(); M.pre.o.disconnect(); M.comp.o.disconnect(); M.side.o.disconnect(); M.tube.disconnect(); M.lim.o.disconnect();
    } catch(e){}
    var prev = mix;
    for (var i = 0; i < list.length; i++) { prev.connect(list[i].i); prev = list[i].o; }
    prev.connect(ctx.destination);
  }

  // --- EQ grafis: pita Q tetap bertumpuk (respons tiap band bocor ke tetangganya), jadi nilai per band DISELESAIKAN dulu supaya
  //     respons gabungan di pusat tiap band = dB yang diminta (sama seperti slider EQ grafis). Cascade biquad = jumlah dB, jadi
  //     cukup matriks respons K (dB per dB) + beberapa iterasi koreksi (respons peaking tidak persis linear terhadap gain).
  var GE = null;
  function peakResp(c, A, j){
    var al = c.al, b0 = 1 + al * A, b2 = 1 - al * A, a0 = 1 + al / A, a2 = 1 - al / A, b1 = -2 * c.cw, a1 = b1;
    var c1 = GE.p[j].c1, c2 = GE.p[j].c2;
    var num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2;
    var den = a0 * a0 + a1 * a1 + a2 * a2 + 2 * (a0 * a1 + a1 * a2) * c1 + 2 * a0 * a2 * c2;
    if (num <= 0 || den <= 0) return 0;
    return 10 * Math.log(num / den) / Math.LN10;
  }
  function geInit(){
    var fs = ctx.sampleRate, i, j, k, n = NB;
    GE = { fs: fs, c: [], p: [], inv: null };
    for (i = 0; i < n; i++) {
      var w0 = 2 * Math.PI * Math.min(CEN[i], fs * 0.45) / fs;
      GE.c.push({ cw: Math.cos(w0), al: Math.sin(w0) / (2 * Q31) });
      GE.p.push({ c1: Math.cos(w0), c2: Math.cos(2 * w0) });
    }
    var A6 = Math.pow(10, 6 / 40), a = [], r;
    for (j = 0; j < n; j++) { r = []; for (i = 0; i < n; i++) r.push(peakResp(GE.c[i], A6, j) / 6); for (i = 0; i < n; i++) r.push(i === j ? 1 : 0); a.push(r); }
    for (i = 0; i < n; i++) {                        // Gauss-Jordan
      var piv = i;
      for (j = i + 1; j < n; j++) if (Math.abs(a[j][i]) > Math.abs(a[piv][i])) piv = j;
      var tmp = a[i]; a[i] = a[piv]; a[piv] = tmp;
      var d = a[i][i];
      if (Math.abs(d) < 1e-9) { GE.inv = null; return; }
      for (k = 0; k < 2 * n; k++) a[i][k] /= d;
      for (j = 0; j < n; j++) if (j !== i) { var m = a[j][i]; if (m !== 0) for (k = 0; k < 2 * n; k++) a[j][k] -= m * a[i][k]; }
    }
    GE.inv = []; for (i = 0; i < n; i++) GE.inv.push(a[i].slice(n));
  }
  function mulInv(v){
    var out = [], i, j, s;
    for (i = 0; i < NB; i++) { s = 0; for (j = 0; j < NB; j++) s += GE.inv[i][j] * v[j]; out.push(s); }
    return out;
  }
  function solveGraphic(t){
    var i, j, it, any = false;
    for (i = 0; i < NB; i++) if (Math.abs(t[i]) > 0.05) { any = true; break; }
    if (!any) return zeros(NB);
    if (!GE || GE.fs !== ctx.sampleRate) geInit();
    if (!GE.inv) return t.slice();
    var gv = mulInv(t);
    for (i = 0; i < NB; i++) gv[i] = Math.max(-30, Math.min(30, gv[i]));
    for (it = 0; it < 3; it++) {
      var A = [], res = [];
      for (i = 0; i < NB; i++) A.push(Math.pow(10, gv[i] / 40));
      for (j = 0; j < NB; j++) {
        var act = 0;
        for (i = 0; i < NB; i++) if (Math.abs(gv[i]) > 0.001) act += peakResp(GE.c[i], A[i], j);
        res.push(t[j] - act);
      }
      var dv = mulInv(res);
      for (i = 0; i < NB; i++) gv[i] = Math.max(-30, Math.min(30, gv[i] + dv[i]));
    }
    return gv;
  }

  // DynamicsCompressor Web Audio menambah makeup otomatis yang tidak bisa dimatikan: (1 / keluaran kurva statis pada 0 dBFS) ^ 0,6.
  // Rumus kurvanya (termasuk knee) disalin dari mesin Chromium, lalu dibatalkan di sini; Gain kompresor = makeup asli.
  function chromeMakeupDb(T, knee, R){
    var lin = function(d){ return Math.pow(10, d / 20); }, dbf = function(x){ return 20 * Math.log(x) / Math.LN10; };
    var linT = lin(T), slope = 1 / R, i;
    function kc(x, k){ return x < linT ? x : linT + (1 - Math.exp(-k * (x - linT))) / k; }
    function slopeAt(x, k){ if (x < linT) return 1; var x2 = x * 1.001; return (dbf(kc(x2, k)) - dbf(kc(x, k))) / (dbf(x2) - dbf(x)); }
    var kneeDb = T + knee, kx = lin(kneeDb), minK = 0.1, maxK = 10000, k = 5;
    for (i = 0; i < 15; i++) { if (slopeAt(kx, k) < slope) maxK = k; else minK = k; k = Math.sqrt(minK * maxK); }
    var yk = dbf(kc(kx, k));
    var full = 1 < kx ? kc(1, k) : lin(yk + slope * (0 - kneeDb));
    return -0.6 * dbf(full);
  }

  var panTimer = 0;
  function sideGains(){
    if (!ctx) return;
    var F = X, t = ctx.currentTime, s;
    var pos = F.pan.on ? Math.sin(2 * Math.PI * (Date.now() / 1000) / Math.max(0.5, F.pan.period)) : 0;
    for (s = 0; s < 2; s++) {
      var att = s === 0 ? Math.max(0, pos) : Math.max(0, -pos);
      var d = (F.comp.post[s] || 0) - (F.pan.on ? F.pan.depth * att : 0);
      M.side.g[s].gain.setTargetAtTime(db2lin(d), t, 0.02);
    }
  }

  function applyFx(){
    if (!ctx) return;
    var F = X, fl = flags(), t = ctx.currentTime, k = 0.02, s, i;
    route();
    if (!fl.on) {
      if (panTimer) { clearInterval(panTimer); panTimer = 0; }
      return;
    }
    M.ing.gain.setTargetAtTime(db2lin(F.inDb), t, k);

    if (fl.pre) {
      var pk = F.pre[0].join(',') + '|' + F.pre[1].join(',');
      if (pk !== M.pre.key) {
        M.pre.key = pk;
        for (s = 0; s < 2; s++) {
          var gv = solveGraphic(F.pre[s]);
          for (i = 0; i < NB; i++) {
            if (Math.abs(gv[i] - M.pre.c[s][i]) < 0.01) continue;
            M.pre.c[s][i] = gv[i];
            M.pre.f[s][i].gain.setTargetAtTime(gv[i], t, k);
          }
        }
      }
    }

    if (fl.comp) {
      var C = M.comp, c = F.comp, split = c.freq < 18000;
      var thr = Math.max(-100, Math.min(0, c.thr)), ratio = Math.max(1, Math.min(20, c.ratio));
      C.dc.threshold.setTargetAtTime(thr, t, k);
      C.dc.ratio.setTargetAtTime(ratio, t, k);
      C.dc.attack.setTargetAtTime(Math.min(1, c.atk / 1000), t, k);
      C.dc.release.setTargetAtTime(Math.min(1, c.rel / 1000), t, k);
      C.mk.gain.setTargetAtTime(db2lin(c.mk - chromeMakeupDb(thr, 6, ratio)), t, k);
      C.full.gain.setTargetAtTime(split ? 0 : 1, t, k);
      C.lowG.gain.setTargetAtTime(split ? 1 : 0, t, k);
      C.highG.gain.setTargetAtTime(split ? 1 : 0, t, k);
      var fq = Math.max(20, Math.min(ctx.sampleRate * 0.45, c.freq));
      for (i = 0; i < 2; i++) { C.l[i].frequency.setTargetAtTime(fq, t, k); C.h[i].frequency.setTargetAtTime(fq, t, k); }
    }

    if (fl.side) {
      var Q = M.side;
      for (s = 0; s < 2; s++) {
        var list = [];
        if (F.peq.on) for (i = 0; i < F.peq.bands.length && list.length < NP; i++) { var b = F.peq.bands[i]; if (b[4] === 0 || b[4] === s + 1) list.push(b); }
        for (i = 0; i < NP; i++) {
          var key, ty, hz, gd, q, fn = Q.f[s][i];
          if (i < list.length) {
            var bb = list[i];
            ty = Math.max(0, Math.min(5, bb[0] | 0)); hz = Math.max(20, Math.min(ctx.sampleRate * 0.45, bb[1])); gd = bb[2]; q = Math.max(0.1, bb[3]);
          } else { ty = 3; hz = 1000; gd = 0; q = 1; }
          key = ty + '|' + hz + '|' + gd + '|' + q;
          if (key === Q.k[s][i]) continue;
          Q.k[s][i] = key;
          if (fn.type !== TYPES[ty]) fn.type = TYPES[ty];
          fn.frequency.setTargetAtTime(hz, t, k);
          // lowpass/highpass: Q Web Audio dalam dB; band pass/peak: Q linear; rak: Q tidak dipakai.
          fn.Q.setTargetAtTime(ty <= 1 ? 20 * Math.log(q) / Math.LN10 : q, t, k);
          fn.gain.setTargetAtTime(ty >= 3 ? gd : 0, t, k);
        }
      }
      sideGains();
    }

    if (fl.tube) M.tube.gain.setTargetAtTime(db2lin(F.tube.db), t, k);

    if (F.pan.on && fl.side) { if (!panTimer) panTimer = setInterval(sideGains, 40); }
    else if (panTimer) { clearInterval(panTimer); panTimer = 0; sideGains(); }
  }

  // Fader volume channel YOUTUBE. Tersambung ke Web Audio: gain di simpul masukan (bisa -tak hingga .. +6 dB; kirim reverb ikut
  // mengikuti fader). Belum tersambung: volume elemen video (maks 0 dB). Volume elemen yang sudah tersambung dijaga 1 supaya tidak dobel.
  // Sebelum fader pernah digeser (vol = 1) volume bawaan YouTube tidak disentuh.
  function applyVol(){
    var v = S.vol; if (!(v >= 0)) v = 1;
    if (ctx && input) input.gain.setTargetAtTime(v, ctx.currentTime, 0.02);
    if (v === 1 && !volSet) return;
    if (v !== 1) volSet = true;
    var all = document.querySelectorAll('video,audio');
    for (var i = 0; i < all.length; i++) {
      var e = all[i], want = hooked.has(e) ? 1 : Math.min(1, v);
      if (Math.abs(e.volume - want) > 0.001) { try { e.volume = want; } catch(x){} }
    }
  }

  // Bus kirim hanya tersambung saat efek ON -> saat OFF tidak ada beban DSP.
  function link(on){
    if (!ctx || on === linked) return;
    linked = on;
    try {
      if (on) { g.revBus.connect(g.pre); g.echoBus.connect(g.dly); }
      else { g.revBus.disconnect(); g.echoBus.disconnect(); }
    } catch(e){}
  }

  // Impuls reverb buatan sendiri: derau meluruh (waktu = ROOM + CESS), makin lama makin redam (DAMP), L/R dekorelasi (WIDTH).
  function makeIR(){
    var rate = ctx.sampleRate;
    var rt = 0.5 + S.room * 2.6 + S.cess * 0.6;
    var len = Math.floor(rate * Math.min(rt * 1.15, 3.6));
    var buf = ctx.createBuffer(2, len, rate), L = buf.getChannelData(0), R = buf.getChannelData(1);
    var a0 = Math.min(0.98, 0.10 + (1 - S.damp) * 0.82 + S.cess * 0.08);
    var dec = 6.9078 / (rt * rate);
    var lpL = 0, lpR = 0, fade = Math.max(1, 0.003 * rate), w = S.width;
    for (var i = 0; i < len; i++) {
      var t = i / len, a = Math.max(0.04, a0 * (1 - 0.65 * t));
      var n1 = Math.random() * 2 - 1, n2 = Math.random() * 2 - 1;
      var sl = n1, sr = w * n2 + (1 - w) * n1;
      lpL += a * (sl - lpL); lpR += a * (sr - lpR);
      var e = Math.exp(-dec * i) * (i < fade ? i / fade : 1);
      L[i] = lpL * e; R[i] = lpR * e;
    }
    return buf;
  }
  // Ganti impuls dengan crossfade (tanpa klik): konvolver baru muncul pelan, yang lama dilepas.
  function swapIR(){
    if (!ctx) return;
    var conv = ctx.createConvolver(); conv.normalize = true; conv.buffer = makeIR();
    var gn = ctx.createGain(); gn.gain.value = 0;
    g.pre.connect(conv); conv.connect(gn); gn.connect(g.revOut);
    var t = ctx.currentTime; gn.gain.setTargetAtTime(1, t, 0.05);
    var old = cur; cur = { c: conv, g: gn };
    if (old) {
      old.g.gain.setTargetAtTime(0, t, 0.05);
      setTimeout(function(){ try { g.pre.disconnect(old.c); old.c.disconnect(); old.g.disconnect(); } catch(e){} }, 700);
    }
  }
  function needIR(){
    var k = [S.room, S.damp, S.width, S.cess].map(function(x){ return Math.round(x * 100); }).join(',');
    if (k === irKey) return;
    irKey = k; clearTimeout(irTimer);
    irTimer = setTimeout(swapIR, cur ? 180 : 0);
  }

  // Skala sama dengan versi native lama: delay 50..600 ms, feedback maks 0.75, pre-delay 0..80 ms, gain keluaran efek 0..2.
  function apply(){
    if (!ctx) return;
    var t = ctx.currentTime, k = 0.03;
    var rOn = S.on && S.reverbOn, eOn = S.on && S.echoOn;
    g.revOut.gain.setTargetAtTime(rOn ? S.wet * 0.9 : 0, t, k);
    g.echoOut.gain.setTargetAtTime(eOn ? S.echoMix * 0.9 : 0, t, k);
    g.pre.delayTime.setTargetAtTime(S.preDelay * 0.08, t, k);
    g.dly.delayTime.setTargetAtTime((50 + S.echoDelay * 550) / 1000, t, 0.05);
    g.fb.gain.setTargetAtTime(S.echoFeedback * 0.75, t, k);
    g.fx.gain.setTargetAtTime(S.gain * 2, t, k);
    for (var i = 0; i < 4; i++) {
      g.rs[i].gain.setTargetAtTime(S.revSend[i], t, k);
      g.es[i].gain.setTargetAtTime(S.echoSend[i], t, k);
    }
    link(S.on);
    needIR();
    applyFx();
    applyVol();
  }

  // createMediaElementSource HANYA dipanggil setelah salah satu efek (reverb / EQ / kompresor / FX) dinyalakan sekali
  // (jalur suara elemen berubah permanen; setelah itu semua OFF = jalur kering).
  // PENGAMAN: konteks audio dibuat/dipakai hanya bila ADA elemen yang sedang diputar, dan elemen baru disambung HANYA saat konteks
  // benar-benar 'running'. Konteks yang masih 'suspended' (kebijakan autoplay) membuat suara/video elemen macet begitu disambung
  // (video langsung berhenti) -> bila belum running, elemen dibiarkan bersuara normal dan dicoba lagi 0,8 dtk kemudian.
  function wanted(){ return S.on || X.on || S.ch || S.vol > 1.0001; }

  // Sentuhan terakhir pengguna: dipakai agar "lanjutkan otomatis" di bawah tidak melawan jeda yang disengaja.
  var lastTouch = 0, anyHooked = false;
  function touched(){ lastTouch = Date.now(); }
  document.addEventListener('touchstart', touched, true);
  document.addEventListener('pointerdown', touched, true);
  document.addEventListener('click', touched, true);

  function hook(v){
    if (hooked.has(v)) return;
    hooked.add(v);
    var was = !v.paused && !v.ended;
    try { var src = ctx.createMediaElementSource(v); src.connect(input); anyHooked = true; L('disambung ' + (was ? 'SAAT JALAN' : 'sebelum jalan')); applyVol(); } catch(e){ L('hook gagal ' + (e && e.name)); return; }
    // Memindahkan suara elemen yang SEDANG diputar ke Web Audio kadang membuat pemutar menjeda diri sesaat.
    // Selama ~2 dtk sesudah disambung, bila video berhenti tanpa sentuhan pengguna, lanjutkan.
    if (was) {
      var n = 0, tm = setInterval(function(){
        n++;
        if (v.paused && !v.ended && Date.now() - lastTouch > 1200) { try { var p = v.play(); if (p && p.catch) p.catch(function(){}); } catch(e){} }
        if (n >= 8) clearInterval(tm);
      }, 250);
    }
  }
  // Elemen yang sudah disambung bergantung pada konteks: begitu konteks tidak 'running' suaranya (dan jam videonya) macet -> hidupkan lagi.
  function keepAlive(){
    if (!ctx || !anyHooked) return;
    if (ctx.state !== 'running') { try { var p = ctx.resume(); if (p && p.catch) p.catch(function(){}); } catch(e){} }
  }
  setInterval(keepAlive, 500);
  function poll(){
    if (!wanted()) return;
    // Sambungkan SEBELUM video diputar bila bisa (elemen yang sudah jalan lebih rawan macet saat dipindah ke Web Audio).
    var vs = document.getElementsByTagName('video'), as = document.getElementsByTagName('audio'), i, todo = [];
    for (i = 0; i < vs.length; i++) if (!hooked.has(vs[i])) todo.push(vs[i]);
    for (i = 0; i < as.length; i++) if (!hooked.has(as[i])) todo.push(as[i]);
    if (!todo.length) { keepAlive(); return; }
    if (!ctx) { if (!build()) return; apply(); try { ctx.onstatechange = function(){ L('ctx ' + ctx.state); keepAlive(); }; } catch(e){} }
    if (ctx.state !== 'running') { try { ctx.resume(); } catch(e){} return; }
    for (i = 0; i < todo.length; i++) hook(todo[i]);
  }
  setInterval(poll, 800);
  document.addEventListener('play', poll, true);
  document.addEventListener('loadstart', poll, true);
  document.addEventListener('touchstart', function(){ if (ctx && ctx.state !== 'running') { try { ctx.resume(); } catch(e){} } poll(); }, true);

  // Meter keluaran efek (rata-rata kuadrat, sama dengan meter native lama).
  setInterval(function(){
    if (!ctx || !S.on) {
      if (lastMs !== 0) { lastMs = 0; try { MixjosAndroid.onLevel(0); } catch(e){} }
      return;
    }
    g.an.getFloatTimeDomainData(g.buf);
    var s = 0, b = g.buf;
    for (var i = 0; i < b.length; i++) s += b[i] * b[i];
    lastMs = s / b.length;
    try { MixjosAndroid.onLevel(lastMs); } catch(e){}
  }, 50);

  // Meter channel YOUTUBE (mode CHANNEL): rata-rata kuadrat sinyal sesudah fader volume.
  setInterval(function(){
    if (!ctx || !g.anIn || !anyHooked || !S.ch) {
      if (lastIn !== 0) { lastIn = 0; try { MixjosAndroid.onLevelIn(0); } catch(e){} }
      return;
    }
    g.anIn.getFloatTimeDomainData(g.bufIn);
    var s = 0, b = g.bufIn;
    for (var i = 0; i < b.length; i++) s += b[i] * b[i];
    lastIn = s / b.length;
    try { MixjosAndroid.onLevelIn(lastIn); } catch(e){}
  }, 50);

  window.__mixjos = {
    set: function(o){
      for (var k in o) {
        if (k === 'revSend' || k === 'echoSend') {
          for (var i = 0; i < 4; i++) if (typeof o[k][i] === 'number') S[k][i] = o[k][i];
        } else if (k === 'fx') {
          X = o.fx;
        } else S[k] = o[k];
      }
      apply(); poll();
    },
    // Hanya untuk uji: ambil konteks + simpul masukan.
    __t: function(){ build(); return { ctx: ctx, input: input, mk: chromeMakeupDb }; }
  };
  try { MixjosAndroid.onReady(); } catch(e){}
})();
"""

    const val ALL = BASE + "\n" + AUDIO
}

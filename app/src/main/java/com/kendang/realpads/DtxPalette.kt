package com.kendang.realpads

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------------------------------
// TOKEN DESAIN (gaya iOS gelap) - lihat dokumen "MIXJOS - Dokumen desain UI/UX baru", bab 4.
// Satu aksen (ThemeState.accentColor). Hijau/kuning/merah HANYA untuk status ON dan level meter.
// Nama lama (PanelColor, PadColor, dst.) dipertahankan sebagai alias supaya file yang belum dirombak tetap kompilasi,
// tetapi nilainya sudah mengikuti token baru.
// ---------------------------------------------------------------------------------------------------------------------

// --- Warna (bab 4.1) ---
val Bg = Color(0xFF000000)               // latar layar (hitam murni, hemat daya OLED)
val SurfaceColor = Color(0xFF1C1C1E)          // kartu, strip channel, daftar
val SurfaceRaised = Color(0xFF2C2C2E)    // tombol sekunder, kontrol segmented
val SidebarColor = Color(0xFF0E0E10)          // latar sidebar
val Separator = Color(0xFF38383A)        // garis pemisah 0,5 dp
val LabelPrimary = Color(0xFFFFFFFF)     // teks utama
val LabelSecondary = Color(0x99EBEBF5)   // keterangan, satuan (EBEBF5 60%)
val OnGreen = Color(0xFF30D158)          // saklar ON, level aman
val Warning = Color(0xFFFFD60A)          // meter 62-86%
val Danger = Color(0xFFFF453A)           // meter puncak, tombol Reset
val SwitchOff = Color(0xFF39393D)        // saklar mati
val KnobTrack = Color(0xFF3A3A3C)        // jalur busur knob
val SegmentSelected = Color(0xFF636366)  // item terpilih pada segmented
val FaderWell = Color(0xFF0A0A0B)        // wadah fader channel
val Scrim = Color(0x80000000)            // latar belakang sheet / alert (hitam 50%)
val DefaultTint = Color(0xFF0A84FF)      // aksen bawaan iOS

// --- Bentuk (bab 4.3) ---
val CardShape = RoundedCornerShape(14.dp)
val ButtonShape = RoundedCornerShape(9.dp)
val SegmentShape = RoundedCornerShape(9.dp)
val SheetShape = RoundedCornerShape(16.dp)
val AlertShape = RoundedCornerShape(14.dp)
val SidebarItemShape = RoundedCornerShape(10.dp)

// --- Jarak ---
val ScreenPadding = 14.dp
val CardGap = 8.dp
val CardPadding = 10.dp
val SidebarWidth = 76.dp
val NavBarHeight = 50.dp

// --- Alias nama lama -> nilai baru ---
val BgColor = Bg
val PanelColor = SurfaceRaised
val PadColor = SurfaceRaised
val PadLineColor = KnobTrack
val PadTextColor = Color(0xFFAEAEB4)     // teks sekunder buram (setara LabelSecondary di atas latar gelap)
val CtrlPanelColor = SurfaceColor
val TextDim = Color(0xFF8E8E93)
val BackdropColor = Bg

val AppTextSize = 12.sp
val GroupOuterPaddingV = 8.dp
val GroupOuterPaddingH = 8.dp
val GroupItemSpacing = 6.dp
val ControlIconSize = 24.dp

// Warna INDIKATOR (saklar ON, meter): hijau iOS, TIDAK mengikuti tema.
val IndicatorGreen = OnGreen
val IndicatorGreenText = Color(0xFF04200C)

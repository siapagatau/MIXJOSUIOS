package com.kendang.realpads

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------------------------------
// SHELL APLIKASI (desain iOS, bab 5): sidebar kiri 76 dp berisi 8 mode dalam 3 kelompok + nav bar atas 50 dp.
// Satu layar = satu mode. Menggantikan strip 10 tombol di bawah (MixActionStrip) dan tab panah di tepi kanan.
// ---------------------------------------------------------------------------------------------------------------------

enum class AppTab(val title: String, val icon: ImageVector, val group: Int) {
    Channel("Channel", Icons.Outlined.Tune, 0),
    Mic("Mikrofon", Icons.Outlined.Mic, 0),
    Audio("Audio", Icons.Outlined.GraphicEq, 0),
    Comp("Kompresor", Icons.Outlined.Compress, 1),
    Eq("Equalizer", Icons.Outlined.Equalizer, 1),
    Fx("FX", Icons.Outlined.AutoAwesome, 1),
    Yt("YouTube", Icons.Outlined.PlayCircle, 2),
    Stage("Panggung", Icons.Outlined.Theaters, 2),
}

/** Sidebar kiri: item aktif memakai latar tint 17% dan ikon + label berwarna tint. Pindah mode = ganti isi seketika. */
@Composable
fun AppSidebar(tab: AppTab, onTab: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val accent = ThemeState.accentColor
    Column(
        modifier = modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .background(SidebarColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var lastGroup = -1
        for (t in AppTab.values()) {
            if (lastGroup != -1 && t.group != lastGroup) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(0.5.dp).background(Separator))
                Spacer(Modifier.height(4.dp))
            }
            lastGroup = t.group
            val sel = t == tab
            val tint = if (sel) accent else LabelSecondary
            val latest by rememberUpdatedState(onTab)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(vertical = 1.dp)
                    .clip(SidebarItemShape)
                    .background(if (sel) accent.copy(alpha = 0.17f) else androidx.compose.ui.graphics.Color.Transparent)
                    .pointerInput(t) { detectTapGestures(onTap = { latest(t) }) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(t.icon, contentDescription = t.title, tint = tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(2.dp))
                Text(t.title, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Nav bar atas 50 dp: judul layar (kiri); saklar Floating, ikon Tema, menu tiga titik (kanan). */
@Composable
fun AppNavBar(
    title: String,
    floatingOn: Boolean,
    onFloating: () -> Unit,
    onTheme: () -> Unit,
    onSkin: () -> Unit,
    onResetAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().height(NavBarHeight).padding(start = ScreenPadding, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title, color = LabelPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
            maxLines = 1, modifier = Modifier.weight(1f)
        )
        // Floating: seluruh baris (label + saklar) bisa diketuk
        Row(
            Modifier.height(NavBarHeight).pointerInput(Unit) { detectTapGestures(onTap = { onFloating() }) }.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Floating", color = LabelSecondary, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            IosSwitch(floatingOn)
        }
        NavIcon(Icons.Outlined.Palette, "Warna tema", onTheme)
        Box {
            NavIcon(Icons.Outlined.MoreVert, "Menu lainnya") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(SurfaceRaised)) {
                DropdownMenuItem(text = { Text("Pilih skin", color = LabelPrimary) }, onClick = { menu = false; onSkin() })
                DropdownMenuItem(text = { Text("Warna tema", color = LabelPrimary) }, onClick = { menu = false; onTheme() })
                DropdownMenuItem(text = { Text("Reset audio", color = Danger) }, onClick = { menu = false; onResetAudio() })
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Separator))
}

@Composable
private fun NavIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val latest by rememberUpdatedState(onClick)
    Box(
        Modifier.size(44.dp).pointerInput(Unit) { detectTapGestures(onTap = { latest() }) },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = desc, tint = ThemeState.accentColor, modifier = Modifier.size(22.dp)) }
}

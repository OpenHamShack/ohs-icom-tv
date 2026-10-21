package com.openhamshack.kind.stationflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.core.tv.Amber
import com.openhamshack.core.tv.Dim
import com.openhamshack.core.tv.LockGreen
import com.openhamshack.core.tv.TvItem
import com.openhamshack.core.tv.TvSafeH
import com.openhamshack.core.tv.TvSafeV
import com.openhamshack.i18n.ShackFormat
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.api.Heard
import java.time.Instant
import java.time.ZoneOffset

private enum class Tab(val title: Int) { Decodes(R.string.heard_tab_decodes), Stations(R.string.heard_tab_stations), Aprs(R.string.heard_tab_aprs) }

/**
 * Everything heard, as big rows: the decoder's transcript, the stations it
 * makes of them, and APRS. OK on a row tunes to it - for a decode, the dial
 * that heard it in USB-D, which puts the station back at the audio offset it
 * was decoded at; for APRS, its channel in FM. Up from the first row reaches
 * the tabs.
 */
@Composable
internal fun HeardScreen(s: StationFlowSession, onClose: () -> Unit) {
    val decodes by s.decodes.collectAsState()
    val spots by s.spots.collectAsState()
    val aprs by s.aprs.collectAsState()
    val digi by s.digi.collectAsState()
    var tab by remember { mutableStateOf(Tab.Decodes) }
    val firstTab = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(60); runCatching { firstTab.requestFocus() } }

    val list = when (tab) { Tab.Decodes -> decodes; Tab.Stations -> spots; Tab.Aprs -> aprs }
    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = TvSafeH, vertical = TvSafeV)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.heard_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
            Tab.entries.forEachIndexed { i, t ->
                val n = when (t) { Tab.Decodes -> decodes.size; Tab.Stations -> spots.size; Tab.Aprs -> aprs.size }
                TvItem(onClick = { tab = t }, focus = if (i == 0) firstTab else null, selected = t == tab) {
                    Text(stringResource(R.string.heard_tab_count, stringResource(t.title), n), fontSize = 18.sp)
                }
            }
        }
        Text(subtitle(tab, digi.holder), color = Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        if (tab != Tab.Aprs && digi.holder == null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("FT8", "FT4", "JS8").filter { digi.modes.isEmpty() || it in digi.modes }.forEach { m ->
                    TvItem(onClick = { s.startDecoder(m) }) { Text(stringResource(R.string.heard_start_decoder, m), fontSize = 16.sp) }
                }
            }
        }
        if (tab == Tab.Aprs && aprs.isEmpty()) {
            Row(Modifier.padding(top = 8.dp)) { TvItem(onClick = { s.startAprs() }) { Text(stringResource(R.string.heard_start_aprs), fontSize = 16.sp) } }
        }
        if (list.isEmpty()) {
            Text(stringResource(R.string.heard_nothing), color = Dim, fontSize = 18.sp, modifier = Modifier.padding(top = 20.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.key }) { h -> HeardRow(h) { s.tuneToHeard(h) } }
        }
        Text(stringResource(R.string.heard_hint), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
    }
}

@Composable
private fun subtitle(tab: Tab, holder: String?) = when (tab) {
    Tab.Decodes -> if (holder != null) stringResource(R.string.heard_sub_decoding, holder)
        else stringResource(R.string.heard_sub_no_decoder)
    Tab.Stations -> stringResource(R.string.heard_sub_stations)
    Tab.Aprs -> stringResource(R.string.heard_sub_aprs)
}

/**
 * The right-hand column of a heard row: where it was heard, then the audio
 * offset it was decoded at (or, for APRS, the mode a tune uses).
 *
 * The frequency is in the contract's format (I18N.md §4.1: kHz with one
 * decimal below 30 MHz, MHz with three from there up, the locale's decimal
 * separator, a no-break space before the unit) and not in the dotted
 * MHz.kHz.Hz groups of the main dial. The dial and the radio faces copy the
 * radio's own display, where the dots are what an Icom shows; this list is
 * ours, and here the dots would read as digit grouping in half the world.
 * Three MHz decimals round off the Hz a decode's audio offset adds; the
 * offset is printed beneath, exactly, and OK tunes to the exact Hz.
 */
internal fun heardTuning(h: Heard, locale: String): Pair<String, String> =
    (h.rfHz?.let { ShackFormat.frequency(it, locale) } ?: "") to
        (h.audioHz?.let { ShackFormat.offset(it, "Hz", locale) } ?: h.tuneMode ?: "")

@Composable
private fun HeardRow(h: Heard, onTune: () -> Unit) {
    val locale = shackLocale()
    TvItem(onClick = onTune, modifier = Modifier.fillMaxWidth()) {
        Text(clock(h.whenS), color = Dim, fontSize = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(110.dp))
        Text(h.snr?.let { "%+d".format(it) } ?: "", color = Dim, fontSize = 20.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(h.call ?: "?", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = if (h.forMe) Amber else if (h.cq) LockGreen else Color.White)
                listOfNotNull(h.mode, h.grid, h.country, h.distanceKm?.let { stringResource(R.string.heard_distance_km, ShackFormat.number(it, locale)) }).forEach {
                    Text(it, color = Dim, fontSize = 16.sp)
                }
            }
            Text(h.text, fontSize = 18.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            val (freq, under) = heardTuning(h, locale)
            Text(freq, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            Text(under, color = Dim, fontSize = 14.sp)
        }
    }
}

private fun clock(s: Double?): String {
    if (s == null) return ""
    val t = Instant.ofEpochMilli((s * 1000).toLong()).atZone(ZoneOffset.UTC)
    return "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
}

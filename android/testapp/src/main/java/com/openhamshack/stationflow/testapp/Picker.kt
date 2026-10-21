package com.openhamshack.stationflow.testapp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.core.data.SavedBox
import com.openhamshack.core.tv.Amber
import com.openhamshack.core.tv.Dim
import com.openhamshack.core.tv.OpenHamShackTvTheme
import com.openhamshack.core.tv.OnAirRed
import com.openhamshack.core.tv.ScrollOnlyAsNeeded
import com.openhamshack.core.tv.TvFocus
import com.openhamshack.core.tv.TvItem
import com.openhamshack.core.tv.TvSafeH
import com.openhamshack.core.tv.TvSafeV
import com.openhamshack.core.tv.TvSection
import com.openhamshack.core.tv.TvTextDialog
import com.openhamshack.core.tv.rememberBackRouting

/**
 * Choosing a StationFlow: found on the network first (one OK and it opens),
 * then the saved ones, then a typed address. The OpenHamShack app's own picker
 * replaces this; it is here so the kind can be driven on an emulator.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Picker(vm: HostViewModel) {
    OpenHamShackTvTheme {
        Surface(Modifier.fillMaxSize().then(rememberBackRouting()), color = Color.Black) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides ScrollOnlyAsNeeded) { PickerBody(vm) }
        }
    }
}

@Composable
private fun PickerBody(vm: HostViewModel) {
    val boxes by vm.boxes.collectAsStateWithLifecycle()
    val found by vm.discovery.boxes.collectAsStateWithLifecycle()
    val findError by vm.discovery.error.collectAsStateWithLifecycle()
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var typing by remember { mutableStateOf(false) }
    val firstFound = remember { FocusRequester() }
    val firstSaved = remember { FocusRequester() }
    val addItem = remember { FocusRequester() }
    var movedToFound by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { if (boxes.isNotEmpty()) firstSaved.requestFocus() else addItem.requestFocus() }
    }
    LaunchedEffect(found.firstOrNull()?.serviceName) {
        if (found.isNotEmpty() && !movedToFound) {
            delay(100)
            if (runCatching { firstFound.requestFocus() }.isSuccess) movedToFound = true
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = TvSafeH, vertical = TvSafeV)) {
        Text(stringResource(R.string.app_name), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        // Two lines, not one: English fits on one, a translation 40% longer
        // (I18N.md rule 5) would be cut off mid-sentence.
        Text(stringResource(R.string.picker_tagline), color = Dim, fontSize = 15.sp, maxLines = 2)
        busy?.let { Text(it, color = TvFocus, fontSize = 16.sp) }
        error?.let { Text(it, color = Amber, fontSize = 16.sp) }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { TvSection(stringResource(R.string.picker_section_found)) }
                if (found.isEmpty()) item {
                    Text(findError ?: stringResource(R.string.picker_looking),
                        color = Dim, fontSize = 15.sp)
                }
                items(found, key = { "nsd-" + it.serviceName }) { d ->
                    TvItem(onClick = { vm.openDiscovered(d) }, focus = if (d == found.first()) firstFound else null,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontSize = 22.sp)
                            Text(listOf(d.address.display,
                                if (d.platform) stringResource(R.string.picker_found_platform, d.version)
                                else stringResource(R.string.picker_found_single, d.version),
                            ).joinToString(stringResource(R.string.list_separator)), color = Dim, fontSize = 14.sp)
                        }
                        Text(stringResource(R.string.picker_open), color = TvFocus, fontSize = 16.sp)
                    }
                }
                item { TvSection(stringResource(R.string.picker_section_saved)) }
                if (boxes.isEmpty()) item { Text(stringResource(R.string.picker_none_saved), color = Dim, fontSize = 15.sp) }
                items(boxes, key = { it.id }) { box ->
                    SavedRow(box, if (box == boxes.first()) firstSaved else null, { vm.open(box) }, { vm.forget(box) })
                }
            }
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvSection(stringResource(R.string.picker_section_add))
                TvItem(onClick = { typing = true }, focus = addItem, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(stringResource(R.string.picker_type_address), fontSize = 20.sp)
                        Text(stringResource(R.string.picker_type_address_example), color = Dim, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (typing) {
        TvTextDialog(stringResource(R.string.address_title), stringResource(R.string.address_hint),
            KeyboardType.Uri, onDismiss = { typing = false }) { text, fail ->
            vm.add(text) { why -> if (why == null) typing = false else fail(why) }
        }
    }
    when (val p = prompt) {
        is Prompt.Password -> TvTextDialog(
            stringResource(R.string.password_title, p.box.title),
            stringResource(R.string.password_hint).let { hint ->
                p.why?.let { stringResource(R.string.password_hint_with_reason, it, hint) } ?: hint
            },
            KeyboardType.Password, secret = true, onDismiss = { vm.dismissPrompt() },
        ) { pw, fail -> vm.signIn(p.box, pw, fail) }
        is Prompt.Trust -> AlertDialog(
            onDismissRequest = { vm.dismissPrompt() },
            title = { Text(stringResource(if (p.changed) R.string.trust_title_changed else R.string.trust_title)) },
            text = {
                Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.changed) Text(stringResource(R.string.trust_changed_warning), color = Amber, fontSize = 15.sp)
                    Text(stringResource(R.string.trust_explain, p.box.address.display), fontSize = 15.sp)
                    Text(p.subject, color = Dim, fontSize = 14.sp)
                    Text(p.fingerprint.split(':').chunked(16).joinToString("\n") { it.joinToString(":") }, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                }
            },
            confirmButton = { TvItem(onClick = { vm.trust(p) }) { Text(stringResource(R.string.trust_confirm), fontSize = 16.sp) } },
            dismissButton = { TvItem(onClick = { vm.dismissPrompt() }) { Text(stringResource(R.string.cancel), fontSize = 16.sp) } },
        )
        null -> {}
    }
}

@Composable
private fun SavedRow(box: SavedBox, focus: FocusRequester?, onOpen: () -> Unit, onForget: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvItem(onClick = onOpen, focus = focus, modifier = Modifier.weight(1f)) {
            Column {
                Text(box.title, fontSize = 22.sp)
                Text(listOfNotNull(box.address.display,
                    if (box.session != null) stringResource(R.string.picker_signed_in) else null,
                    if (box.trustedCa != null) stringResource(R.string.picker_certificate_trusted) else null,
                ).joinToString(stringResource(R.string.list_separator)), color = Dim, fontSize = 14.sp)
            }
        }
        TvItem(onClick = { if (armed) onForget() else armed = true }) {
            Text(stringResource(if (armed) R.string.picker_forget_confirm else R.string.picker_forget), color = if (armed) OnAirRed else Dim, fontSize = 16.sp)
        }
    }
}

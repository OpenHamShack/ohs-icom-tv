package com.openhamshack.kind.stationflow.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.formatStep

/**
 * The TV's main screen for a radio that has a face: the radio's own screen,
 * as large as the TV's safe area allows at the radio's proportions, and one
 * line of help under it. The D-pad behaves as it did before the screen was
 * radio-shaped - the frequency has focus, left/right tunes, OK changes the
 * step, down reaches the keys - only now the keys are the radio's key bar.
 */
@Composable
fun RadioFaceTv(
    s: StationFlowSession, face: Face, st: RadioState?, pending: Long?, step: Long, muted: Boolean,
    linkProblem: String?, dial: FocusRequester, host: FaceHost, viewOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    var page by remember(face.key) { mutableIntStateOf(0) }
    var focusedKey by remember { mutableIntStateOf(-1) }
    var dialFocused by remember { mutableStateOf(false) }
    val utc = rememberUtc()
    val keys = faceKeys(face, page)
    // The face replaces the plain layout when the first state names the model, after the screen's own
    // "focus the dial" has already run against the plain layout's dial; ask again for this one's.
    androidx.compose.runtime.LaunchedEffect(face.key) {
        kotlinx.coroutines.delay(80)
        runCatching { dial.requestFocus() }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            RadioFaceView(
                face, st,
                FaceExtras(pendingHz = pending, step = step, muted = muted, keyPage = page, linkProblem = linkProblem,
                    focusedKey = focusedKey, dialFocused = dialFocused, utc = utc),
                s.waterfall,
            ) { scale ->
                FaceTvControls(face, scale, s, host, page, onPage = { page = (page + 1) % pageCount(face) }, dial,
                    onFocusKey = { if (it >= 0) focusedKey = it else if (focusedKey == -1 - it) focusedKey = -1 },
                    onDialFocus = { dialFocused = it })
            }
        }
        val vo = if (viewOnly) "View only - sign in to tune  ·  " else ""
        val help = when {
            dialFocused -> "◀ ▶ tune ${formatStep(step)}  ·  OK change step  ·  ▼ keys  ·  ⏯ mute  ·  CH+/CH− band  ·  Back: leave"
            focusedKey >= 0 -> keys.getOrNull(focusedKey)?.let { k ->
                (if (k.action == KeyAction.Page) "OK: more keys" else "OK: ${k.label.lowercase()}") +
                    "  ·  ◀ ▶ along  ·  ▲ frequency  ·  Back: leave"
            } ?: ""
            else -> ""
        }
        Text(vo + help, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Phones and tablets: the same face, by touch. Wide enough for the radio's
 * proportions (a tablet, a phone on its side) and it is the radio's screen
 * as it is; upright, it is [compactOf] the face - the radio's top half kept,
 * the scope taller, every key in two rows. Spec: RADIO-DISPLAYS.md 5.4.
 */
@Composable
fun RadioFaceTouch(
    s: StationFlowSession, face: Face, st: RadioState?, pending: Long?, step: Long, muted: Boolean,
    linkProblem: String?, host: FaceHost, modifier: Modifier = Modifier,
) {
    var page by remember(face.key) { mutableIntStateOf(0) }
    val utc = rememberUtc()
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val aspect = maxWidth.value / maxHeight.value
        val shown = if (aspect >= face.w / face.h * 0.92f) face else compactOf(face, aspect)
        RadioFaceView(
            shown, st,
            FaceExtras(pendingHz = pending, step = step, muted = muted, keyPage = page, linkProblem = linkProblem, utc = utc),
            s.waterfall,
            modifier = if (shown === face) Modifier else Modifier.fillMaxWidth(),
        ) { scale ->
            FaceTouchControls(shown, scale, s, host, page, onPage = { page = (page + 1) % pageCount(shown) }, s.waterfall)
        }
    }
}

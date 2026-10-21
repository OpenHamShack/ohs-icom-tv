package com.openhamshack.kind.stationflow.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openhamshack.core.tv.LocalTvInert
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.Waterfall
import kotlin.math.roundToInt

/** What the face's controls tell the screen around them. */
interface FaceHost {
    fun openTune()
    fun openDecodes()
    fun openSstv()
    fun focusChanged(what: FaceFocus)
}

enum class FaceFocus { None, Dial, Key }

/** A key on the face doing what it says: receive-side only (see [KeyAction]). */
fun runKey(a: KeyAction, s: StationFlowSession, host: FaceHost, page: () -> Unit) {
    val st = s.state.value
    when (a) {
        KeyAction.Page -> page()
        KeyAction.Mode -> s.cycleMode()
        KeyAction.Filter -> s.cycleFilter()
        KeyAction.Band -> s.cycleBand()
        KeyAction.Vfo -> s.setVfo(if (st?.vfo == "VFOB") "VFOA" else "VFOB")
        KeyAction.Step -> s.cycleStep()
        KeyAction.Tune -> host.openTune()
        KeyAction.Decodes -> host.openDecodes()
        KeyAction.Sstv -> host.openSstv()
        KeyAction.Mute -> s.toggleMute()
    }
}

/** A rectangle of the face, in the dp the face is drawn at. */
@Composable
private fun Modifier.at(r: Px, scalePx: Float): Modifier {
    val d = LocalDensity.current
    return this
        .offset { IntOffset((r.x * scalePx).roundToInt(), (r.y * scalePx).roundToInt()) }
        .size(with(d) { (r.w * scalePx).toDp() }, with(d) { (r.h * scalePx).toDp() })
}

/**
 * The face's D-pad controls, laid over its picture: the main band's
 * frequency (left/right tune by the step, OK changes the step - as before
 * this was a radio-shaped screen) and the keys along the bottom (OK does
 * the key; Up returns to the frequency). Invisible: the face draws their
 * focus rings itself, where the radio's own touch areas are.
 *
 * [dialFocus] is requested by the screen on open and whenever a panel
 * closes, so a TV is never left with focus nowhere.
 */
@Composable
fun FaceTvControls(
    face: Face, scalePx: Float, s: StationFlowSession, host: FaceHost,
    page: Int, onPage: () -> Unit, dialFocus: FocusRequester,
    onFocusKey: (Int) -> Unit, onDialFocus: (Boolean) -> Unit,
) {
    val inert = LocalTvInert.current
    val keys = faceKeys(face, page)
    val rects = keyRects(face)
    val keyFocus = remember(rects.size) { List(rects.size) { FocusRequester() } }
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier.at(dialRect(face), scalePx)
            .focusRequester(dialFocus)
            .focusProperties {
                canFocus = !inert
                // Down from the frequency lands on MODE, the first key after the page key - not on
                // whichever key happens to sit under the dial's centre.
                down = keyFocus.getOrElse(1) { FocusRequester.Default }
            }
            .onFocusChanged { onDialFocus(it.isFocused); if (it.isFocused) host.focusChanged(FaceFocus.Dial) }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { s.tuneBy(-1); true }
                    Key.DirectionRight -> { s.tuneBy(+1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = noRipple, indication = null) { s.cycleStep() }
            .semantics { contentDescription = "Frequency" },
    )
    rects.forEachIndexed { i, r ->
        val k = keys.getOrNull(i) ?: return@forEachIndexed
        Box(
            Modifier.at(r, scalePx)
                .focusRequester(keyFocus[i])
                .focusProperties { canFocus = !inert; up = dialFocus }
                .onFocusChanged { if (it.isFocused) { onFocusKey(i); host.focusChanged(FaceFocus.Key) } else onFocusKey(-1 - i) }
                .clickable(interactionSource = noRipple, indication = null) { runKey(k.action, s, host, onPage) }
                .semantics { contentDescription = k.label },
        )
    }
}

/**
 * The same face by touch (phone, tablet): drag along the frequency to tune
 * a step per notch, tap it to change the step; tap the scope or waterfall
 * to tune there (to the step's grid); tap a key. No focus rings - a finger
 * knows where it is.
 */
@Composable
fun FaceTouchControls(
    face: Face, scalePx: Float, s: StationFlowSession, host: FaceHost,
    page: Int, onPage: () -> Unit, waterfall: Waterfall,
) {
    val keys = faceKeys(face, page)
    val notchPx = with(LocalDensity.current) { 22.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.at(dialRect(face), scalePx)
            .pointerInput(s) {
                detectHorizontalDragGestures(onDragStart = { dragged = 0f }) { _, dx ->
                    dragged += dx
                    while (dragged > notchPx) { s.tuneBy(+1); dragged -= notchPx }
                    while (dragged < -notchPx) { s.tuneBy(-1); dragged += notchPx }
                }
            }
            .pointerInput(s) { detectTapGestures { s.cycleStep() } }
            .semantics { contentDescription = "Frequency: drag to tune, tap to change the step" },
    )
    val scope = Px(face.spectrum.x, face.spectrum.y, face.spectrum.w, face.waterfall.bottom - face.spectrum.y)
    Box(
        Modifier.at(scope, scalePx).pointerInput(s) {
            detectTapGestures { pos ->
                val sp = waterfall.latest ?: return@detectTapGestures
                val frac = (pos.x / size.width).coerceIn(0f, 1f)
                val hz = sp.startHz + (sp.spanHz * frac).toLong()
                val step = s.effectiveStep()
                s.tuneTo(Math.round(hz.toDouble() / step) * step)
            }
        }.semantics { contentDescription = "Band scope: tap to tune there" },
    )
    keyRects(face).forEachIndexed { i, r ->
        val k = keys.getOrNull(i) ?: return@forEachIndexed
        Box(
            Modifier.at(r, scalePx)
                .clickable { runKey(k.action, s, host, onPage) }
                .semantics { contentDescription = k.label },
        )
    }
}

/** The UTC clock for the status row, ticking once a second while composed. */
@Composable
fun rememberUtc(): String {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = clock() } }
    return now
}

private fun clock(): String {
    val t = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
    return "%02d:%02d".format(t.hour, t.minute)
}

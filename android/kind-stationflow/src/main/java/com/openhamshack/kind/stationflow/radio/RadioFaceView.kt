package com.openhamshack.kind.stationflow.radio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.openhamshack.kind.stationflow.Waterfall
import com.openhamshack.kind.stationflow.api.RadioState
import kotlin.math.roundToInt

/**
 * Icom's default (black) screen colours, sampled from the manuals' pictures
 * of the IC-9700 (RADIO-DISPLAYS.md, "Colours"). Not the app's TV palette
 * on purpose: this is a likeness of the radio, and the radio's screen is
 * black whatever the room.
 */
object IcomColours {
    val Bg = Color(0xFF000000)
    val Text = Color(0xFFFFFFFF)
    /** The band that is not selected, and labels such as VFO A and AGC-F. */
    val Grey = Color(0xFF8A8E8A)
    val Faint = Color(0xFF3A3D3B)
    val ModeTop = Color(0xFF1A5A8C)
    val ModeBottom = Color(0xFF002A4A)
    val FilBg = Color(0xFF0A0D0B)
    val FilEdge = Color(0xFF3A3A38)
    val Red = Color(0xFFE8231C)
    val MeterBlue = Color(0xFF2A78C8)
    val MeterRed = Color(0xFFE0302A)
    val MeterOff = Color(0xFF3A3D3B)
    val MeterGreen = Color(0xFF00E000)
    val HeaderDim = Color(0xFF1E2320)
    val CentrePill = Color(0xFF0A6AA0)
    val Arrows = Color(0xFF01ABE6)
    val Graticule = Color(0xFF282B2A)
    val Trace = Color(0xFFB8BAB9)
    val WaterfallFloor = Color(0xFF00034A)
    val KeyTop = Color(0xFF3C403E)
    val KeyBottom = Color(0xFF121614)
    val KeyEdge = Color(0xFF4B504D)
    /** Focus on a TV: the app's ring, not the radio's - the radio has no focus, and a TV must show one. */
    val Focus = Color(0xFF4FC3F7)
    val Pending = Color(0xFFFFB300)
}

/** What the face shows that is not in the radio's state: this app's own view of things. */
data class FaceExtras(
    val pendingHz: Long? = null,
    val step: Long = 100,
    val muted: Boolean = false,
    val keyPage: Int = 0,
    /** The radio is reachable but this app's link to the module is down, in words; null when it is fine. */
    val linkProblem: String? = null,
    /** Which key has focus (index on the page), or -1; and whether the dial has. For the ring. */
    val focusedKey: Int = -1,
    val dialFocused: Boolean = false,
    val utc: String = "",
)

/** Native-pixel rectangles of what can be pressed or focused, for overlays and touch. */
fun dialRect(face: Face): Px = face.band.dial.shift(face.main.x, face.main.y)

fun keyRects(face: Face): List<Px> {
    val k = face.keys
    val gap = face.w * 0.01f
    val perRow = (face.keysPerPage + face.keyRows - 1) / face.keyRows
    val w = (k.w - gap * (perRow - 1)) / perRow
    val h = (k.h - gap * (face.keyRows - 1)) / face.keyRows
    return (0 until face.keysPerPage).map { i ->
        Px(k.x + (i % perRow) * (w + gap), k.y + (i / perRow) * (h + gap), w, h)
    }
}

/**
 * The radio's screen, drawn at the radio's proportions into whatever room
 * it is given. Everything is drawn in one Canvas in the radio's own pixels,
 * scaled once, so a 480 x 272 layout lands on a 1080p TV or a phone exactly
 * in proportion. What can be focused or touched is laid over it by the
 * screen that uses it, at [dialRect] and [keyRects].
 */
@Composable
fun RadioFaceView(
    face: Face,
    st: RadioState?,
    extras: FaceExtras,
    waterfall: Waterfall,
    modifier: Modifier = Modifier,
    overlay: @Composable (scale: Float) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val version by waterfall.version.collectAsState()
    val pixels = remember { arrayOfNulls<IntArray>(1) }
    val bitmap = remember { arrayOfNulls<Bitmap>(1) }
    BoxWithConstraints(modifier.aspectRatio(face.w / face.h)) {
        val scalePx = constraints.maxWidth / face.w
        Canvas(Modifier.fillMaxSize()) {
            version
            withTransform({ scale(scalePx, scalePx, Offset.Zero) }) {
                drawFace(face, st, extras, waterfall, measurer, this@Canvas, scalePx, pixels, bitmap)
            }
        }
        Box(Modifier.fillMaxSize()) { overlay(scalePx) }
    }
}

private fun DrawScope.drawFace(
    face: Face, st: RadioState?, x: FaceExtras, wf: Waterfall, tm: TextMeasurer, density: Density, scale: Float,
    pixels: Array<IntArray?>, bitmap: Array<Bitmap?>,
) {
    drawRect(IcomColours.Bg, Offset.Zero, Size(face.w, face.h))
    val live = st?.connected == true
    val t = Txt(tm, density, scale, this)

    // -- status row: TX at the left (only while transmitting), LAN, the clock.
    val s = face.status
    if (st?.transmitting == true) {
        // The radio's TX box, where the radio puts it, lit only while it is on the air. Shown, never caused.
        val b = face.txBox
        drawRect(IcomColours.Red, Offset(b.x, b.y), Size(b.w, b.h))
        t.draw("TX", b.x + b.w / 2, b.y + b.h / 2, b.h * 0.85f, IcomColours.Text, Align.Centre, bold = true)
    }
    face.tileColumn?.let { drawTiles(face, it, t) }
    // LAN: the radio's own icon for "controlled over the network", lit when the module has the radio.
    val lanW = face.textSize * 2.4f
    val lanH = face.textSize * 1.2f
    val lanX = face.clockRight - face.textSize * 4.2f - lanW
    drawRoundRect(if (live) IcomColours.Text else IcomColours.Faint, Offset(lanX, s.y + (s.h - lanH) / 2), Size(lanW, lanH),
        CornerRadius(2f), style = Stroke(1f))
    t.draw("LAN", lanX + lanW / 2, s.y + s.h / 2, face.textSize * 0.8f, if (live) IcomColours.Text else IcomColours.Faint,
        Align.Centre, bold = true)
    if (x.muted) {
        t.draw("MUTE", lanX - face.textSize * 0.6f, s.y + s.h / 2, face.textSize * 0.9f, IcomColours.Pending, Align.Right, bold = true)
    }
    t.draw(x.utc, face.clockRight, s.y + s.h / 2, face.textSize * 1.35f, IcomColours.Text, Align.Right)

    // -- the band: the main receiver only (no sub band, by decision - Faces.kt).
    drawBand(face, face.main, st, x, t)

    // -- the scope.
    drawScopeHeader(face, wf, t)
    drawSpectrum(face, st, x, wf, t)
    drawWaterfall(face, st, x, wf, pixels, bitmap)
    drawRuler(face, wf, t)
    drawKeys(face, st, x, t)

    // -- whatever stops the picture being live, said over the scope where the radio says "NO DATA".
    val why = when {
        x.linkProblem != null -> x.linkProblem
        st == null -> "Connecting…"
        !st.connected -> "NO RADIO" + (st.error?.let { " · $it" } ?: "")
        else -> null
    }
    if (why != null) {
        val r = face.spectrum
        val bw = r.w * 0.8f
        val bh = face.textSize * 2.2f
        drawRoundRect(Color(0xE0000000), Offset(r.x + (r.w - bw) / 2, r.y + 4), Size(bw, bh), CornerRadius(3f))
        drawRoundRect(IcomColours.Red, Offset(r.x + (r.w - bw) / 2, r.y + 4), Size(bw, bh), CornerRadius(3f), style = Stroke(1f))
        t.draw(why, r.x + r.w / 2, r.y + 4 + bh / 2, face.textSize, IcomColours.Red, Align.Centre, bold = true, maxWidth = bw - 6)
    }
}

/** The band's block: mode and filter badges, meter, frequency, VFO. */
private fun DrawScope.drawBand(face: Face, at: Px, st: RadioState?, x: FaceExtras, t: Txt) {
    val b = face.band
    val live = st?.connected == true
    val mode = b.mode.shift(at.x, at.y)
    val fil = b.filter.shift(at.x, at.y)
    drawRoundRect(Brush.verticalGradient(listOf(IcomColours.ModeTop, IcomColours.ModeBottom), mode.y, mode.bottom),
        Offset(mode.x, mode.y), Size(mode.w, mode.h), CornerRadius(3f))
    t.draw(st?.shownMode ?: "", mode.x + mode.w / 2, mode.y + mode.h / 2, mode.h * 0.62f,
        if (live) IcomColours.Text else IcomColours.Grey, Align.Centre)
    if (b.filterBoxed) {
        drawRoundRect(IcomColours.FilBg, Offset(fil.x, fil.y), Size(fil.w, fil.h), CornerRadius(3f))
        drawRoundRect(IcomColours.FilEdge, Offset(fil.x, fil.y), Size(fil.w, fil.h), CornerRadius(3f), style = Stroke(1f))
    }
    t.draw(st?.filter ?: "", fil.x + fil.w / 2, fil.y + fil.h / 2, fil.h * (if (b.filterBoxed) 0.62f else 0.55f),
        if (live) IcomColours.Text else IcomColours.Grey, Align.Centre)

    drawMeter(face, b.meter.shift(at.x, at.y), st, t)

    // The frequency, right-aligned, and the step mark over the digit the dial moves.
    val hz = x.pendingHz ?: st?.freqHz
    val (big, small) = splitFrequency(hz, face)
    val colour = when {
        x.pendingHz != null -> IcomColours.Pending
        live -> IcomColours.Text
        else -> IcomColours.Grey
    }
    val baseY = at.y + b.freqBaseline
    val right = at.x + b.freqRight
    val smallW = if (small.isEmpty()) 0f else t.width(small, b.smallSize, digits = true)
    val bigW = t.width(big, b.freqSize, digits = true)
    t.drawBaseline(big, right - smallW - bigW, baseY, b.freqSize, colour)
    if (small.isNotEmpty()) t.drawBaseline(small, right - smallW, baseY, b.smallSize, colour)
    if (hz != null) {
        stepDigitFromRight(x.step, face)?.let { fromRight ->
            val all = big + small
            // Walk back from the right over digits only, to the one the step moves.
            var seen = -1
            var idx = all.length
            while (idx > 0 && seen < fromRight) { idx--; if (all[idx].isDigit()) seen++ }
            if (seen == fromRight) {
                val inSmall = idx >= big.length
                val size = if (inSmall) b.smallSize else b.freqSize
                val textBefore = if (inSmall) all.substring(big.length, idx) else all.substring(0, idx)
                val start = if (inSmall) right - smallW else right - smallW - bigW
                val cx = start + t.width(textBefore, size, digits = true) + t.width(all[idx].toString(), size, digits = true) / 2
                val top = baseY - size * 0.86f
                val path = Path().apply {
                    moveTo(cx - size * 0.14f, top - size * 0.13f); lineTo(cx + size * 0.14f, top - size * 0.13f)
                    lineTo(cx, top - size * 0.02f); close()
                }
                drawPath(path, if (x.dialFocused) IcomColours.Focus else IcomColours.Text)
            }
        }
    }
    t.draw(if (st?.vfo == "VFOB") "VFO B" else "VFO A", at.x + b.vfoRight, at.y + b.vfoTop, b.labelSize,
        if (live) IcomColours.Grey else IcomColours.Faint, Align.Right, top = true)
    if (st?.split == true) {
        t.draw("SPLIT", at.x + b.vfoRight, at.y + b.vfoTop + b.labelSize * 1.4f, b.labelSize, IcomColours.Text, Align.Right, top = true)
    }
}

/** The meter: the S scale while receiving, Po while the radio transmits; segmented bar as the radio draws it. */
private fun DrawScope.drawMeter(face: Face, r: Px, st: RadioState?, t: Txt) {
    if (face.meterStyle == MeterStyle.Needle) { drawNeedleMeter(face, r, st, t); return }
    val tx = st?.transmitting == true
    val labelW = face.band.meterLabelW
    val barX = r.x + labelW
    val barW = r.w - labelW
    val scaleY = r.y + r.h * 0.28f
    val barY = r.y + r.h * 0.55f
    val barH = r.h * 0.40f
    val size = r.h * 0.42f
    t.draw(if (tx) "Po" else "S", barX - size * 0.4f, barY + barH / 2, size, IcomColours.Text, Align.Right)
    // The 9700's scope screen squeezes its meter to a mini one and prints only 1, 5 and 9 with red dots
    // for the dB marks (RADIO-DISPLAYS.md 1.5); a full-width meter prints the whole scale.
    val mini = barW < 120f
    val marks: List<Triple<Float, String, Boolean>> = when {
        tx -> listOf(Triple(0f, "0", false), Triple(0.25f, "25", false), Triple(0.5f, "50", false), Triple(1f, "100%", false))
        mini -> sScale(face.s9At).filter { it.second in setOf("1", "5", "9") || it.third }
            .map { if (it.third) Triple(it.first, "•", true) else it }
        else -> sScale(face.s9At)
    }
    marks.forEach { (at, label, red) ->
        val px = barX + at * barW
        t.draw(label, px, scaleY, size * 0.9f, if (red) IcomColours.Red else IcomColours.Text,
            if (at >= 1f && !mini) Align.Right else Align.Centre)
    }
    // The red line under the dB half of the S scale, as the radio has it.
    if (!tx) drawLine(IcomColours.Red, Offset(barX + face.s9At * barW, barY - 1f), Offset(barX + barW, barY - 1f), 1f)
    val frac = when {
        st == null || !st.connected -> null
        tx -> st.powerRaw?.let { (it / 255f).coerceIn(0f, 1f) }
        else -> meterFraction(st.smeter?.let { (it * 241).roundToInt() }, face.s9At)
    }
    val segs = 46
    val segW = barW / segs
    for (i in 0 until segs) {
        val at = (i + 0.5f) / segs
        val on = frac != null && at <= frac
        // Lit segments blue to S9 and red past it, as the IC-705's cover photo shows its meter.
        val lit = if (!tx && at > face.s9At) IcomColours.MeterRed else IcomColours.MeterBlue
        drawRect(if (on) lit else IcomColours.MeterOff,
            Offset(barX + i * segW, barY), Size(segW * 0.55f, barH))
    }
}

private fun DrawScope.drawScopeHeader(face: Face, wf: Waterfall, t: Txt) {
    val h = face.scopeHeader
    val sp = wf.latest
    val size = h.h * 0.9f
    // MAIN (red) or SUB: which receiver the sweep is of - the radio's own pill.
    val pillW = h.h * 3.6f
    drawRoundRect(Color(0xFFD82A24), Offset(h.x + 6f, h.y), Size(pillW, h.h), CornerRadius(2f))
    t.draw(if (sp?.receiver == 1) "SUB" else "MAIN", h.x + 6f + pillW / 2, h.y + h.h / 2, size, IcomColours.Text, Align.Centre)
    t.draw("SPECTRUM SCOPE", h.x + h.w * 0.36f, h.y + h.h / 2, size, IcomColours.Text, Align.Left)
    val modeText = when (sp?.mode?.lowercase()) {
        "center" -> "CENTER"; "fixed" -> "FIXED"; "scroll-c" -> "SCROLL-C"; "scroll-f" -> "SCROLL-F"; else -> null
    }
    if (modeText != null) {
        val mw = h.h * 4.4f
        val mx = h.x + h.w * 0.685f
        drawRoundRect(IcomColours.CentrePill, Offset(mx, h.y), Size(mw, h.h), CornerRadius(2f))
        t.draw(modeText, mx + mw / 2, h.y + h.h / 2, size * 0.9f, IcomColours.Text, Align.Centre)
    }
    // The radio prints its grid here ("Grid 100k/10dB"). The span is known from the sweep's edges; the
    // dB per division is not reported, so only the frequency half is given.
    sp?.let {
        t.draw("Grid ${shortHz(it.spanHz / 10)}", h.right - 2f, h.y + h.h / 2, size, IcomColours.Text, Align.Right)
    }
}

private fun DrawScope.drawSpectrum(face: Face, st: RadioState?, x: FaceExtras, wf: Waterfall, t: Txt) {
    val r = face.spectrum
    val sp = wf.latest
    for (i in 1..9) drawLine(IcomColours.Graticule, Offset(r.x + r.w * i / 10, r.y), Offset(r.x + r.w * i / 10, r.bottom), 0.6f)
    for (i in 1..4) drawLine(IcomColours.Graticule, Offset(r.x, r.y + r.h * i / 5), Offset(r.right, r.y + r.h * i / 5), 0.6f)
    if (sp == null || sp.data.isEmpty()) return
    val n = sp.data.size
    val path = Path()
    for (i in 0 until n) {
        val px = r.x + r.w * i / (n - 1).coerceAtLeast(1)
        val py = r.bottom - r.h * (sp.data[i].toFloat() / sp.max).coerceIn(0f, 1f)
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    val fill = Path().apply { addPath(path); lineTo(r.right, r.bottom); lineTo(r.x, r.bottom); close() }
    // Icom's default waveform type is "Fill" in the model's "current waveform" colour.
    val trace = Color(face.trace)
    drawPath(fill, trace.copy(alpha = 0.85f))
    drawPath(path, trace, style = Stroke(0.7f))
    val half = sp.spanHz / 2
    t.draw("-" + shortHz(half), r.x + 2f, r.y + face.textSize * 0.5f, face.textSize * 0.9f, IcomColours.Text, Align.Left)
    t.draw("+" + shortHz(half), r.right - 2f, r.y + face.textSize * 0.5f, face.textSize * 0.9f, IcomColours.Text, Align.Right)
    marker(face, r, st, x, sp.startHz, sp.endHz)
}

private fun DrawScope.drawWaterfall(face: Face, st: RadioState?, x: FaceExtras, wf: Waterfall,
                                    pixels: Array<IntArray?>, bitmap: Array<Bitmap?>) {
    val r = face.waterfall
    drawRect(IcomColours.WaterfallFloor, Offset(r.x, r.y), Size(r.w, r.h))
    val w = wf.width
    val sp = wf.latest ?: return
    if (w == 0) return
    val rows = wf.rows
    val px = pixels[0]?.takeIf { it.size == w * rows } ?: IntArray(w * rows).also { pixels[0] = it }
    val bmp = bitmap[0]?.takeIf { it.width == w && it.height == rows }
        ?: Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888).also { bitmap[0] = it }
    val real = wf.copyInto(px)
    if (real == 0) return
    bmp.setPixels(px, 0, w, 0, 0, w, rows)
    // About two sweeps per radio pixel of height, as many as the history holds: a strip as short as the
    // 9700's shows the last few seconds, a phone's tall one the whole history. Rows not yet filled stay
    // the floor colour, so a new waterfall grows down from the top as the radio's does.
    val target = minOf(rows, (r.h * 2).roundToInt().coerceAtLeast(1))
    val shown = minOf(real, target)
    drawImage(bmp.asImageBitmap(), IntOffset.Zero, IntSize(w, shown), IntOffset(r.x.roundToInt(), r.y.roundToInt()),
        IntSize(r.w.roundToInt(), (r.h * shown / target).roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Low)
    marker(face, r, st, x, sp.startHz, sp.endHz, alpha = 0.5f)
}

private fun DrawScope.marker(face: Face, r: Px, st: RadioState?, x: FaceExtras, start: Long, end: Long, alpha: Float = 1f) {
    val hz = x.pendingHz ?: st?.freqHz ?: return
    if (end <= start || hz < start || hz > end) return
    val px = r.x + r.w * (hz - start).toFloat() / (end - start)
    drawLine((if (x.pendingHz != null) IcomColours.Pending else IcomColours.Text).copy(alpha = alpha * 0.8f),
        Offset(px, r.y), Offset(px, r.bottom), 0.8f)
}

private fun DrawScope.drawRuler(face: Face, wf: Waterfall, t: Txt) {
    val r = face.ruler
    val sp = wf.latest ?: return
    // Offsets from the centre in kHz, as the radio's centre-mode ruler reads (-400 ... +400).
    val half = sp.spanHz / 2.0
    val step = niceStep(sp.spanHz / 10.0)
    var k = -Math.floor(half / step) * step
    while (k <= half) {
        val px = (r.x + r.w * ((k + half) / sp.spanHz)).toFloat()
        drawLine(IcomColours.Text, Offset(px, r.y), Offset(px, r.y + 2f), 0.8f)
        val label = when {
            k == 0.0 -> "0"
            else -> (if (k > 0) "+" else "-") + trimNum(Math.abs(k) / 1000.0)
        }
        if (px > r.x + face.textSize && px < r.right - face.textSize) {
            t.draw(label, px, r.y + r.h * 0.6f, face.textSize * 0.95f, IcomColours.Text, Align.Centre)
        }
        k += step
    }
}

private fun DrawScope.drawKeys(face: Face, st: RadioState?, x: FaceExtras, t: Txt) {
    val keys = faceKeys(face, x.keyPage)
    keyRects(face).forEachIndexed { i, r ->
        drawRoundRect(Brush.verticalGradient(listOf(IcomColours.KeyTop, IcomColours.KeyBottom), r.y, r.bottom),
            Offset(r.x, r.y), Size(r.w, r.h), CornerRadius(3f))
        drawRoundRect(IcomColours.KeyEdge, Offset(r.x, r.y), Size(r.w, r.h), CornerRadius(3f), style = Stroke(0.8f))
        val k = keys.getOrNull(i) ?: return@forEachIndexed
        val sub = keyValue(k.action, st, x)
        if (sub == null) {
            t.draw(k.label, r.x + r.w / 2, r.y + r.h / 2, r.h * 0.5f, IcomColours.Text, Align.Centre)
        } else {
            t.draw(k.label, r.x + r.w / 2, r.y + r.h * 0.36f, r.h * 0.4f, IcomColours.Text, Align.Centre)
            t.draw(sub, r.x + r.w / 2, r.y + r.h * 0.75f, r.h * 0.3f, IcomColours.Grey, Align.Centre, maxWidth = r.w - 4)
        }
        if (i == x.focusedKey) {
            drawRoundRect(IcomColours.Focus, Offset(r.x - 1f, r.y - 1f), Size(r.w + 2f, r.h + 2f), CornerRadius(4f), style = Stroke(2f))
        }
    }
    if (x.dialFocused) {
        val d = dialRect(face)
        drawRoundRect(IcomColours.Focus, Offset(d.x, d.y + 1f), Size(d.w - 1f, d.h - 2f), CornerRadius(4f), style = Stroke(1.6f))
    }
}

/** The line under a key: what it is set to now, as the radio's own function keys show it. */
fun keyValue(a: KeyAction, st: RadioState?, x: FaceExtras): String? = when (a) {
    KeyAction.Mode -> st?.shownMode
    KeyAction.Filter -> st?.filter
    KeyAction.Band -> st?.let { s -> com.openhamshack.kind.stationflow.bandOf(s.bands, x.pendingHz ?: s.freqHz)?.name }
    KeyAction.Vfo -> if (st?.vfo == "VFOB") "B" else "A"
    KeyAction.Step -> com.openhamshack.kind.stationflow.formatStep(x.step)
    KeyAction.Mute -> if (x.muted) "ON" else "OFF"
    else -> null
}

private fun niceStep(want: Double): Double {
    for (s in doubleArrayOf(100.0, 200.0, 500.0, 1e3, 2e3, 2.5e3, 5e3, 1e4, 2e4, 2.5e4, 5e4, 1e5, 2e5, 2.5e5, 5e5, 1e6)) if (s >= want) return s
    return 1e7
}

private fun trimNum(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)

/** "500k", "2.5k", "1M" - the radio's own short form for spans. */
fun shortHz(hz: Long): String = when {
    hz >= 1_000_000 -> trimNum(hz / 1e6) + "M"
    hz >= 1_000 -> trimNum(hz / 1e3) + "k"
    else -> "$hz"
}

private enum class Align { Left, Centre, Right }

/**
 * Text in the radio's pixels. Sizes are given in radio pixels and turned
 * into sp for the scale the face is drawn at, so a label is the same
 * fraction of the screen on a TV as on the radio. System sans-serif with
 * tabular figures for the readout: no font is shipped (OpenHamShack rule), and
 * tabular digits keep the frequency from shuffling sideways as it changes.
 */
private class Txt(val tm: TextMeasurer, val d: Density, val scale: Float, val ds: DrawScope) {
    private fun style(size: Float, colour: Color, bold: Boolean, digits: Boolean) = TextStyle(
        color = colour,
        fontSize = with(d) { (size * scale).toSp() },
        fontFamily = FontFamily.SansSerif,
        fontWeight = if (bold || digits) FontWeight.Bold else FontWeight.Medium,
        fontFeatureSettings = if (digits) "tnum" else null,
    )

    fun width(s: String, size: Float, digits: Boolean = false): Float =
        tm.measure(s, style(size, Color.White, false, digits)).size.width / scale

    private fun unscaled(block: DrawScope.() -> Unit) =
        ds.withTransform({ scale(1f / scale, 1f / scale, Offset.Zero) }) { block() }

    /** Centred vertically on [y] (or its top at [y]), anchored by [align] at [x]. */
    fun draw(s: String, x: Float, y: Float, size: Float, colour: Color, align: Align, bold: Boolean = false,
             top: Boolean = false, maxWidth: Float? = null) {
        if (s.isEmpty()) return
        val st = style(size, colour, bold, false)
        val layout = tm.measure(s, st, maxLines = 1, softWrap = false,
            constraints = if (maxWidth != null) androidx.compose.ui.unit.Constraints(maxWidth = (maxWidth * scale).roundToInt().coerceAtLeast(1)) else androidx.compose.ui.unit.Constraints())
        val w = layout.size.width / scale
        val h = layout.size.height / scale
        val left = when (align) { Align.Left -> x; Align.Centre -> x - w / 2; Align.Right -> x - w }
        val tp = if (top) y else y - h / 2
        unscaled { drawText(layout, topLeft = Offset(left * scale, tp * scale)) }
    }

    fun drawBaseline(s: String, x: Float, baseline: Float, size: Float, colour: Color) {
        val layout = tm.measure(s, style(size, colour, true, true), maxLines = 1, softWrap = false)
        val base = layout.firstBaseline / scale
        unscaled { drawText(layout, topLeft = Offset(x * scale, (baseline - base) * scale)) }
    }
}


/**
 * The IC-7610's analogue meter: the S arc with its printed scale (1 to 9
 * white, +20 to +60 dB red), the Po and SWR arcs inside it, and a white
 * needle. Only the arcs for readings StationFlow reports are printed: the
 * radio's ID, COMP, ALC and Vd arcs would be scales nothing ever moves along.
 */
private fun DrawScope.drawNeedleMeter(face: Face, r: Px, st: RadioState?, t: Txt) {
    val tx = st?.transmitting == true
    // Pivot below the face, arcs spanning 120 degrees about the vertical, as the 7610's meter is drawn
    // (Basic manual p. 1-7): the S arc's ends sit about two thirds of the way down the meter.
    val cx = r.x + r.w * 0.5f
    val cy = r.y + r.h * 1.38f
    val rad = r.h * 1.30f
    val a0 = 210f
    val sweep = 120f
    fun pt(frac: Float, radius: Float): Offset {
        val a = Math.toRadians((a0 + sweep * frac).toDouble())
        return Offset(cx + radius * Math.cos(a).toFloat(), cy + radius * Math.sin(a).toFloat())
    }
    fun arc(radius: Float, from: Float, to: Float, colour: Color, width: Float) {
        drawArc(colour, a0 + sweep * from, sweep * (to - from), false, Offset(cx - radius, cy - radius),
            Size(radius * 2, radius * 2), style = Stroke(width))
    }
    val live = st?.connected == true
    val ink = if (st == null) IcomColours.Faint else IcomColours.Text
    val red = if (st == null) IcomColours.Faint else IcomColours.Red
    arc(rad, 0f, face.s9At, ink, 2.2f)
    arc(rad, face.s9At, 1f, red, 2.2f)
    sScale(face.s9At).forEach { (at, label, isRed) ->
        drawLine(if (isRed) red else ink, pt(at, rad), pt(at, rad + 5f), 1.4f)
        val p = pt(at, rad + 13f)
        t.draw(label, p.x, p.y, face.textSize * 1.05f, if (isRed) red else ink, Align.Centre)
    }
    val sAt = pt(-0.02f, rad + 16f)
    t.draw("S", sAt.x, sAt.y, face.textSize * 1.1f, ink, Align.Centre, bold = true)
    // Po (0 50 100 %) and SWR (1 1.5 2 3) arcs, as printed on the 7610's meter face.
    val poR = rad * 0.80f
    arc(poR, 0f, 1f, ink, 1.4f)
    listOf(0.5f to "50", 1f to "100").forEach { (at, l) ->
        drawLine(ink, pt(at, poR), pt(at, poR + 4f), 1f)
        val p = pt(at, poR - 8f); t.draw(l, p.x, p.y, face.textSize * 0.8f, ink, Align.Centre)
    }
    val poLabel = pt(0.07f, poR - 10f); t.draw("Po", poLabel.x, poLabel.y, face.textSize * 0.8f, ink, Align.Centre)
    val swrR = rad * 0.62f
    arc(swrR, 0f, 1f, ink, 1.4f)
    listOf(0.25f to "1.5", 0.5f to "2", 0.75f to "3").forEach { (at, l) ->
        drawLine(ink, pt(at, swrR), pt(at, swrR + 4f), 1f)
        val p = pt(at, swrR - 8f); t.draw(l, p.x, p.y, face.textSize * 0.75f, ink, Align.Centre)
    }
    val swrLabel = pt(0.10f, swrR - 10f); t.draw("SWR", swrLabel.x, swrLabel.y, face.textSize * 0.75f, ink, Align.Centre)
    // The needle: the S reading receiving, power transmitting. None at all when nothing is measured.
    val frac = when {
        !live -> null
        tx -> st?.powerRaw?.let { (it / 255f).coerceIn(0f, 1f) }
        else -> meterFraction(st?.smeter?.let { (it * 241).roundToInt() }, face.s9At)
    } ?: return
    drawLine(IcomColours.Text, pt(frac, rad * 0.35f), pt(frac, rad + 3f), 2f)
}

/** The IC-7610's column of function tiles: the names the radio prints, and no values (none is reported). */
private fun DrawScope.drawTiles(face: Face, col: Px, t: Txt) {
    if (face.tiles.isEmpty()) return
    val h = col.h / face.tiles.size
    face.tiles.forEachIndexed { i, name ->
        val y = col.y + i * h
        drawRect(IcomColours.KeyBottom, Offset(col.x, y + 1f), Size(col.w - 6f, h - 2f))
        drawRect(IcomColours.KeyEdge, Offset(col.x, y + 1f), Size(col.w - 6f, h - 2f), style = Stroke(0.8f))
        drawRect(IcomColours.Faint, Offset(col.right - 5f, y + 4f), Size(2f, h - 8f))
        t.draw(name, col.x + (col.w - 6f) / 2, y + h * 0.36f, face.textSize * 1.05f, IcomColours.Grey, Align.Centre)
    }
}

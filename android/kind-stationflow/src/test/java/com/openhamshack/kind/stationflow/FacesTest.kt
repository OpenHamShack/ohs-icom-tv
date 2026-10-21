package com.openhamshack.kind.stationflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.radio.FACES
import com.openhamshack.kind.stationflow.radio.Face
import com.openhamshack.kind.stationflow.radio.KEY_ACTIONS
import com.openhamshack.kind.stationflow.radio.KeyAction
import com.openhamshack.kind.stationflow.radio.Px
import com.openhamshack.kind.stationflow.radio.allKeys
import com.openhamshack.kind.stationflow.radio.compactOf
import com.openhamshack.kind.stationflow.radio.dialRect
import com.openhamshack.kind.stationflow.radio.faceFor
import com.openhamshack.kind.stationflow.radio.faceKeys
import com.openhamshack.kind.stationflow.radio.keyRects
import com.openhamshack.kind.stationflow.radio.meterFraction
import com.openhamshack.kind.stationflow.radio.pageCount
import com.openhamshack.kind.stationflow.radio.splitFrequency
import com.openhamshack.kind.stationflow.radio.stepDigitFromRight

class FacesTest {
    private val nine = FACES.getValue("ic-9700")

    @Test fun `each radio gets its own face, and anything else the plain layout`() {
        assertEquals("ic-9700", faceFor("ic-9700")!!.key)
        assertEquals("ic-7610", faceFor("IC-7610")!!.key)
        assertEquals("ic-705", faceFor("ic-705")!!.key)
        // The IC-7600 is in the module's table but has no face: never a 9700's by default.
        assertNull(faceFor("ic-7600"))
        assertNull(faceFor(null))
    }

    @Test fun `the model key comes through from the state`() {
        val s = RadioState.parse(fixture("tvapi-state-example.json"))
        assertEquals("ic-9700", s.modelKey)
        assertNotNull(faceFor(s.modelKey))
    }

    @Test fun `the frequency reads as the radio prints it`() {
        assertEquals("144.174.00" to "", splitFrequency(144_174_000, nine))
        assertEquals("1296.200.00" to "", splitFrequency(1_296_200_000, nine))
        assertEquals("14.074.00" to "", splitFrequency(14_074_000, FACES.getValue("ic-7610")))
        // Unknown is dashes in the same shape, never a zero frequency.
        assertEquals("---.---.--" to "", splitFrequency(null, nine))
        // The 1 Hz readout the manual shows as "146.520.00" with a small last digit.
        val fine = nine.copy(resolutionHz = 1, smallDigits = 1)
        assertEquals("146.520.00" to "0", splitFrequency(146_520_000, fine))
    }

    @Test fun `the step mark sits over the digit the step moves`() {
        assertEquals(0, stepDigitFromRight(10, nine))       // 10 Hz: the last digit
        assertEquals(1, stepDigitFromRight(100, nine))
        assertEquals(2, stepDigitFromRight(1_000, nine))    // 1 kHz: where the radio's own mark sits
        assertEquals(2, stepDigitFromRight(5_000, nine))
        assertEquals(3, stepDigitFromRight(12_500, nine))   // 12.5 kHz: its leading 1, the 10 kHz digit
        assertNull(stepDigitFromRight(1, nine))             // finer than the readout: no mark
    }

    @Test fun `the meter puts S9 and S9+60 where the radio does`() {
        assertEquals(0f, meterFraction(0, 0.5f)!!, 1e-6f)
        assertEquals(0.5f, meterFraction(120, 0.5f)!!, 1e-6f)
        assertEquals(1f, meterFraction(241, 0.5f)!!, 1e-6f)
        assertEquals(1f, meterFraction(255, 0.5f)!!, 1e-6f)
        // No reading is no bar, not an empty one that reads as S0.
        assertNull(meterFraction(null, 0.5f))
    }

    @Test fun `no key on any face, page or phone layout can transmit`() {
        val safe = setOf(KeyAction.Page, KeyAction.Mode, KeyAction.Filter, KeyAction.Band, KeyAction.Vfo,
            KeyAction.Step, KeyAction.Tune, KeyAction.Decodes, KeyAction.Sstv, KeyAction.Mute)
        // KeyAction itself is the allow-list: a new member must be added here deliberately.
        assertEquals(safe, KeyAction.entries.toSet())
        allKeys().forEach { k ->
            assertTrue("${k.label} is ${k.action}", k.action in safe)
            assertTrue("${k.label} reads like a transmit key",
                listOf("TX", "PTT", "TRANSMIT", "TUNE", "POWER", "VOX").none { k.label.uppercase().contains(it) })
        }
    }

    @Test fun `every action is on some page of every face`() {
        FACES.values.forEach { f ->
            val shown = (0 until pageCount(f)).flatMap { faceKeys(f, it) }.map { it.action }.toSet()
            KEY_ACTIONS.forEach { assertTrue("${f.key} lacks ${it.label}", it.action in shown) }
            assertEquals(f.keysPerPage, faceKeys(f, 0).size)
        }
    }

    @Test fun `every region is on the radio's screen, at the radio's size and on a phone`() {
        val faces = FACES.values + FACES.values.map { compactOf(it, 0.48f) }
        faces.forEach { f ->
            val regions = listOf(f.status, f.txBox, f.main, f.scopeHeader, f.spectrum, f.waterfall, f.ruler,
                f.keys, dialRect(f)) + keyRects(f) + listOfNotNull(f.tileColumn)
            regions.forEach { r -> assertInside(f, r) }
            // Top to bottom in the radio's order: bands, then the scope, then the keys.
            assertTrue(f.main.y < f.scopeHeader.y && f.scopeHeader.y < f.spectrum.y)
            assertTrue(f.spectrum.y < f.waterfall.y && f.waterfall.y < f.ruler.y && f.ruler.y < f.keys.y)
        }
    }

    @Test fun `the phone layout keeps the radio's top half and shows every key at once`() {
        val c = compactOf(nine, 0.48f)
        assertEquals(nine.main, c.main)
        assertTrue(c.spectrum.h > nine.spectrum.h)
        assertEquals(KEY_ACTIONS.map { it.action }, faceKeys(c, 0).map { it.action })
        assertEquals(1, pageCount(c))
    }

    @Test fun `the band block and the scope have the room the sub band had`() {
        // No sub band, by decision: nothing is left empty between the band and the scope where it was
        // (the 9700's sub block was 64 px). The 7610's own screen has 13 px there, which it keeps.
        val faces = FACES.values + FACES.values.map { compactOf(it, 0.48f) }
        faces.forEach { f -> assertTrue("${f.key}: a gap above the scope", f.scopeHeader.y - f.main.bottom <= 16f) }
        // The 7610's single band runs from its tiles to the right edge, not across half the screen.
        val seven = FACES.getValue("ic-7610")
        assertEquals(seven.w, seven.main.right, 0.5f)
        assertTrue(seven.main.x >= seven.tileColumn!!.right)
        // Its frequency has the width the sub band left: right of the VFO/mode/filter line, not under it.
        assertTrue(dialRect(seven).x > seven.band.filter.right + seven.main.x)
    }

    private fun assertInside(f: Face, r: Px) {
        val e = 0.5f
        assertTrue("${f.key}: $r is off a ${f.w}x${f.h} screen",
            r.x >= -e && r.y >= -e && r.right <= f.w + e && r.bottom <= f.h + e)
    }
}

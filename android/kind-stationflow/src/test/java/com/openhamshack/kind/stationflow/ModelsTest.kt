package com.openhamshack.kind.stationflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.kind.stationflow.api.Heard
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.api.Spectrum
import com.openhamshack.kind.stationflow.api.SstvPicture
import com.openhamshack.kind.stationflow.api.objects

class ModelsTest {
    @Test fun `the contract's own example state`() {
        val s = RadioState.parse(fixture("tvapi-state-example.json"))
        assertEquals(144_174_000L, s.freqHz)
        assertEquals("USB-D", s.shownMode)
        assertEquals("FIL1", s.filter)
        // Raw 64 of the radio's 0-255, over 241 (S9+60) for the bar.
        assertEquals(64 / 241.0, s.smeter!!, 1e-9)
        assertEquals(-25.2, s.smeterDb!!, 1e-9)
        // The final contract has no auth block: the host's level applies.
        assertNull(s.auth)
        assertEquals(listOf("2m", "70cm", "23cm"), s.bands.map { it.name })
        assertTrue(s.connected)
        assertFalse(s.transmitting)
        assertEquals(12000, s.audioRate)
    }

    @Test fun `StationFlow 1_23_0, before the TV API`() {
        val s = RadioState.parse(fixture("stationflow-1.23.0-state.json"))
        assertEquals("Shack 9700", s.name)
        assertEquals(432_200_000L, s.freqHz)
        assertEquals("VFOB", s.vfo)
        assertTrue(s.split)
        assertEquals(181 / 241.0, s.smeter!!, 1e-9)
        assertNull(s.auth)
    }

    @Test fun `a raw meter at S9+60 or beyond fills the bar, never more`() {
        assertEquals(1.0, RadioState.smeterFraction(241.0), 1e-9)
        assertEquals(1.0, RadioState.smeterFraction(255.0), 1e-9)
        assertEquals(0.0, RadioState.smeterFraction(0.0), 1e-9)
        assertEquals(120 / 241.0, RadioState.smeterFraction(120.0), 1e-9)   // S9
    }

    @Test fun `a radio switch is the top-level id, not a boolean`() {
        assertTrue(RadioState.parse(JSONObject("""{"selected":"r","switching":"r2","radios":{"r":{"id":"r"}}}""")).switching)
        assertFalse(RadioState.parse(JSONObject("""{"selected":"r","switching":null,"radios":{"r":{"id":"r"}}}""")).switching)
        assertEquals(1, Spectrum.parse(JSONObject("""{"start":1,"end":2,"data":[1],"receiver":1}"""))!!.receiver)
    }

    @Test fun `unknown stays unknown, never zero`() {
        val s = RadioState.parse(JSONObject("""{"selected":"r","radios":{"r":{"id":"r","freq":null,"smeter":null}}}"""))
        assertNull(s.freqHz)
        assertNull(s.smeter)
        assertNull(s.shownMode)
    }

    @Test fun `a decode tunes to the dial that heard it, in USB-D`() {
        val rows = fixture("fakeflow-digi-history.json").optJSONArray("decodes").objects().map { Heard.fromDecode(it) }
        assertTrue(rows.isNotEmpty())
        for (h in rows) {
            assertEquals("USB-D", h.tuneMode)
            assertEquals(h.rfHz!! - h.audioHz!!, h.tuneHz)
            assertTrue(h.call!!.isNotBlank())
        }
    }

    @Test fun `a placeholder JS8 caller is not a callsign`() {
        val h = Heard.fromDecode(JSONObject("""{"mode":"JS8","message":"x","from":"<....>","freq_hz":1000}"""))
        assertNull(h.call)
        assertNull(h.tuneHz)   // no dial_hz: nothing to tune to
    }

    @Test fun `a spot's dial is its RF less its audio offset`() {
        val spots = fixture("fakeflow-log-spots.json").optJSONArray("spots").objects().mapNotNull { Heard.fromSpot(it) }
        assertTrue(spots.isNotEmpty())
        spots.forEach { assertEquals(it.rfHz!! - it.audioHz!!, it.tuneHz) }
    }

    @Test fun `APRS stations tune to the channel in FM`() {
        val st = fixture("fakeflow-aprs.json").optJSONArray("stations").objects().mapNotNull { Heard.fromAprs(it, 144_800_000) }
        assertEquals(3, st.size)
        st.forEach { assertEquals(144_800_000L, it.tuneHz); assertEquals("FM", it.tuneMode) }
        assertTrue(st.any { it.text.contains("Digipeater") })
    }

    @Test fun `SSTV pictures and their paths`() {
        val p = SstvPicture.parseList(fixture("fakeflow-sstv.json").optJSONArray("pictures"))
        assertEquals(3, p.size)
        assertEquals("api/sstv/picture/${p[0].id}.png", p[0].path)
        assertEquals("Robot36", p[0].mode)
    }

    @Test fun `a scope frame, and the socket's opening stats are not one`() {
        val f = Spectrum.parse(JSONObject("""{"start":144149000,"end":144199000,"max":160,"data":[1,2,3],"mode":"center"}"""))!!
        assertEquals(50_000L, f.spanHz)
        assertEquals(3, f.data.size)
        assertNull(Spectrum.parse(JSONObject("""{"enabled":true,"points":475,"start":1,"end":2}""")))
    }
}

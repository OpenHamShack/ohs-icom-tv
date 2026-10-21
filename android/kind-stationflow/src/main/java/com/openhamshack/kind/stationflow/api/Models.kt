package com.openhamshack.kind.stationflow.api

import org.json.JSONArray
import org.json.JSONObject
import com.openhamshack.core.net.AuthState

/*
 * StationFlow's payloads, read leniently. The module is under active
 * development and adds fields often; a field this app does not know is
 * ignored and a field it expects but does not find reads as "unknown" (null),
 * never as a crash or as a confident zero. A frequency of 0 on a TV across
 * the room reads as a fault in the radio, not in the app.
 */

/** One band button the radio offers: where its band button lands. */
data class Band(val name: String, val hz: Long)

/** The selected radio, merged with the module-level fields that matter here. */
data class RadioState(
    val title: String = "",
    val version: String = "",
    val radioId: String? = null,
    val name: String = "",
    val model: String = "",
    /**
     * The model's key ("ic-9700", "ic-7610", "ic-705", ...), which picks the
     * radio-shaped display (ui/radio/Faces.kt). Null before the module says,
     * and from a module old enough not to send `model`.
     */
    val modelKey: String? = null,
    val connected: Boolean = false,
    /** "connecting", "connected", "failed", "disconnected", ... - the module's own word. */
    val linkState: String = "",
    val error: String? = null,
    val switching: Boolean = false,
    val freqHz: Long? = null,
    val mode: String? = null,
    /** Mode with the DATA flag: "USB-D". What the radio's own screen shows. */
    val modeLabel: String? = null,
    val dataMode: Boolean = false,
    /** "FIL1".."FIL3" */
    val filter: String? = null,
    val vfo: String = "VFOA",
    val split: Boolean = false,
    /** 0..1 of the meter's scale: the radio's raw 0-255 over 241 (S9+60). */
    val smeter: Double? = null,
    /** dB relative to S9, the module's own conversion. */
    val smeterDb: Double? = null,
    /** True while the radio is transmitting - shown, never caused, by this app. */
    val transmitting: Boolean = false,
    /** The radio's raw RF power meter, 0-255 (TV-API.md section 5), only meaningful while transmitting. */
    val powerRaw: Int? = null,
    /** SWR as the module reports it: 1.0 flat, 99 "high". Only while transmitting. */
    val swr: Double? = null,
    val modes: List<String> = emptyList(),
    val dataModes: List<String> = emptyList(),
    val bands: List<Band> = emptyList(),
    val audio: Boolean = false,
    val audioRate: Int? = null,
    val scope: Boolean = false,
    val digiMode: String? = null,
    /**
     * The module's own word on this session's level, if it ever sends one.
     * TV-API.md (final) has none - the platform tells the host - so this is
     * normally null and the host's level applies (StationFlowKind.open).
     */
    val auth: AuthState? = null,
) {
    val shownMode: String? get() = modeLabel ?: mode

    companion object {
        /**
         * The /api/state payload (and every push on /api/ws): module fields at
         * the top, radios keyed by id under "radios", "selected" naming the
         * one in use. The selected entry is what this app shows, as the
         * module's own page does.
         */
        fun parse(o: JSONObject): RadioState {
            val selected = o.optStringOrNull("selected")
            val radios = o.optJSONObject("radios")
            val r: JSONObject = (selected?.let { radios?.optJSONObject(it) })
                ?: radios?.let { m -> m.keys().asSequence().firstOrNull()?.let { m.optJSONObject(it) } }
                // A pre-1.2x module, or a fake: the radio's fields flat at the top.
                ?: o
            val digi = r.optJSONObject("digi")
            return RadioState(
                title = o.optString("title"),
                version = o.optString("version"),
                radioId = r.optStringOrNull("id") ?: selected,
                name = r.optString("name").ifBlank { o.optString("title") },
                model = r.optJSONObject("model")?.optString("name").orEmpty(),
                modelKey = r.optJSONObject("model")?.optStringOrNull("key")?.lowercase(),
                connected = r.optBoolean("connected"),
                linkState = r.optString("link_state"),
                error = r.optStringOrNull("error"),
                // Top level: the id being switched to, or null; per radio: a boolean.
                switching = (o.has("switching") && !o.isNull("switching") && o.opt("switching") != false) ||
                    r.optBoolean("switching"),
                freqHz = r.optLongOrNull("freq"),
                mode = r.optStringOrNull("mode"),
                modeLabel = r.optStringOrNull("mode_label"),
                dataMode = r.optBoolean("data_mode"),
                filter = r.optStringOrNull("filter"),
                vfo = r.optString("vfo").ifBlank { "VFOA" },
                split = r.optBoolean("split"),
                smeter = r.optDoubleOrNull("smeter")?.let { smeterFraction(it) },
                smeterDb = r.optDoubleOrNull("smeter_db"),
                transmitting = r.optBoolean("ptt"),
                powerRaw = r.optLongOrNull("power")?.toInt(),
                swr = r.optDoubleOrNull("swr"),
                modes = r.optJSONArray("modes").strings(),
                dataModes = r.optJSONArray("data_modes").strings(),
                bands = r.optJSONArray("bands").objects().mapNotNull { b ->
                    val hz = b.optLongOrNull("hz") ?: return@mapNotNull null
                    Band(b.optString("name").ifBlank { "${hz / 1_000_000} MHz" }, hz)
                },
                audio = r.optBoolean("audio"),
                audioRate = r.optLongOrNull("audio_rate")?.toInt(),
                scope = r.optBoolean("scope"),
                digiMode = digi?.optStringOrNull("mode"),
                auth = (o.optJSONObject("auth") ?: r.optJSONObject("auth"))?.let { AuthState.parse(it) },
            )
        }

        /**
         * The S-meter as 0..1 of the bar: the radio's raw 0-255 (0 = S0,
         * 120 = S9, 241 = S9+60, TV-API.md section 5) over 241, so the bar
         * ends where the radio's own meter does. (A draft of the contract
         * had it as a fraction already; the final one and every release
         * send raw.)
         */
        fun smeterFraction(raw: Double): Double = (raw / 241.0).coerceIn(0.0, 1.0)
    }
}

/** One sweep of the band scope. Levels are 0..[max]. */
data class Spectrum(
    val startHz: Long,
    val endHz: Long,
    val max: Int,
    val data: IntArray,
    val outOfRange: Boolean = false,
    /** "center", "fixed", "scroll-c", ... as the radio reports it. */
    val mode: String? = null,
    /** 0 main, 1 sub (TV-API.md section 8). */
    val receiver: Int = 0,
) {
    val spanHz: Long get() = endHz - startHz

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        /** A /api/scope frame, or null for the socket's opening stats message or an error. */
        fun parse(o: JSONObject): Spectrum? {
            val arr = o.optJSONArray("data") ?: return null
            val start = o.optLongOrNull("start") ?: return null
            val end = o.optLongOrNull("end") ?: return null
            if (end <= start) return null
            val data = IntArray(arr.length()) { arr.optInt(it) }
            return Spectrum(start, end, o.optInt("max", 160).coerceAtLeast(1), data,
                o.optBoolean("out_of_range"), o.optStringOrNull("mode"), o.optInt("receiver", 0))
        }
    }
}

/**
 * Something heard that the operator can tune to: a decode, a station on the
 * roster, an APRS station. One shape so one list and one OK-to-tune serves
 * them all.
 */
data class Heard(
    val kind: Kind,
    /** Stable key for a list: call for a station, time+offset+text for a decode. */
    val key: String,
    val call: String?,
    val text: String,
    /** Seconds since the epoch, server clock. */
    val whenS: Double?,
    val snr: Int? = null,
    /** FT8, JS8, FM (APRS), ... */
    val mode: String? = null,
    /** Where to put the dial to hear it, and in which mode; null if it cannot be tuned to. */
    val tuneHz: Long? = null,
    val tuneMode: String? = null,
    /** The station's RF frequency, for display. */
    val rfHz: Long? = null,
    val audioHz: Int? = null,
    val grid: String? = null,
    val distanceKm: Int? = null,
    val country: String? = null,
    val cq: Boolean = false,
    val forMe: Boolean = false,
    val ageS: Double? = null,
) {
    enum class Kind { Decode, Spot, Aprs }

    companion object {
        /**
         * A decode from /api/digi/ws or /api/digi/history. FT8, FT4, JS8 and
         * WSPR are all worked on upper sideband with DATA on, and the dial
         * that heard it is in the decode (dial_hz) - so tuning to a decode
         * means that dial in USB-D, which puts the station back at the same
         * audio offset the decoder heard it at. Tuning to its RF frequency
         * instead would put it at 0 Hz audio, where nothing decodes it.
         */
        fun fromDecode(o: JSONObject): Heard {
            val dial = o.optLongOrNull("dial_hz")
            val audio = o.optLongOrNull("freq_hz")?.toInt()
            val mode = o.optStringOrNull("mode")
            val msg = o.optString("message")
            return Heard(
                kind = Kind.Decode,
                key = "d:" + (o.optDoubleOrNull("when") ?: 0.0) + ":" + (audio ?: 0) + ":" + msg,
                call = o.optStringOrNull("from")?.takeIf { it.isNotBlank() && !it.startsWith("<") },
                text = msg,
                whenS = o.optDoubleOrNull("when"),
                snr = o.optLongOrNull("snr")?.toInt(),
                mode = mode,
                tuneHz = dial,
                tuneMode = if (dial != null) "USB-D" else null,
                rfHz = o.optLongOrNull("rf_hz") ?: if (dial != null && audio != null) dial + audio else null,
                audioHz = audio,
                grid = o.optStringOrNull("grid")?.ifBlank { null },
                country = o.optStringOrNull("country")?.ifBlank { null },
                cq = o.optBoolean("calling_cq"),
                forMe = o.optBoolean("for_me"),
            )
        }

        /** A station on /api/log/spots: the band as stations, one row per callsign. */
        fun fromSpot(o: JSONObject): Heard? {
            val call = o.optStringOrNull("call") ?: return null
            val rf = o.optLongOrNull("freq_hz")
            val audio = o.optLongOrNull("audio_hz")?.toInt()
            val dial = if (rf != null && audio != null) rf - audio else rf
            return Heard(
                kind = Kind.Spot,
                key = "s:$call",
                call = call,
                text = o.optString("message"),
                whenS = o.optDoubleOrNull("last"),
                snr = o.optLongOrNull("snr")?.toInt(),
                mode = o.optStringOrNull("mode"),
                tuneHz = dial,
                tuneMode = if (dial != null) "USB-D" else null,
                rfHz = rf,
                audioHz = audio,
                grid = o.optStringOrNull("grid"),
                distanceKm = o.optLongOrNull("distance_km")?.toInt(),
                country = o.optStringOrNull("country")?.ifBlank { null },
                cq = o.optStringOrNull("kind") == "cq",
                forMe = o.optBoolean("calling_us"),
                ageS = o.optDoubleOrNull("age_s"),
            )
        }

        /**
         * A station from /api/aprs (or its socket's state). APRS is FM on a
         * fixed channel, so tuning to one means that channel in FM; the
         * frequency is the one the packet was heard on, when the module says.
         */
        fun fromAprs(o: JSONObject, channelHz: Long?): Heard? {
            val call = o.optStringOrNull("call") ?: return null
            val hz = o.optLongOrNull("freq_hz") ?: channelHz
            val text = listOfNotNull(
                o.optStringOrNull("symbol_name")?.ifBlank { null },
                o.optStringOrNull("comment")?.ifBlank { null } ?: o.optStringOrNull("status")?.ifBlank { null },
            ).joinToString(" · ")
            return Heard(
                kind = Kind.Aprs,
                key = "a:$call",
                call = call,
                text = text,
                whenS = o.optDoubleOrNull("last"),
                mode = "APRS",
                tuneHz = hz,
                tuneMode = if (hz != null) "FM" else null,
                rfHz = hz,
                distanceKm = o.optLongOrNull("distance_km")?.toInt(),
                ageS = o.optDoubleOrNull("age_s"),
            )
        }
    }
}

/** One received SSTV picture, as the module's store lists it. */
data class SstvPicture(
    val id: String,
    val mode: String,
    val atS: Double?,
    val width: Int?,
    val height: Int?,
    val complete: Boolean,
    val freqHz: Long?,
) {
    /** Relative to the server's base: the module serves each picture by id. */
    val path: String get() = "api/sstv/picture/$id.png"

    companion object {
        fun parse(o: JSONObject): SstvPicture? {
            val id = o.optStringOrNull("id") ?: o.optStringOrNull("file")?.removeSuffix(".png") ?: return null
            return SstvPicture(
                id = id,
                mode = o.optString("mode"),
                atS = o.optDoubleOrNull("finished_at") ?: o.optDoubleOrNull("at"),
                width = o.optLongOrNull("width")?.toInt(),
                height = o.optLongOrNull("height")?.toInt(),
                complete = o.optBoolean("complete", true),
                freqHz = o.optLongOrNull("freq_hz") ?: o.optLongOrNull("freq"),
            )
        }

        fun parseList(arr: JSONArray?): List<SstvPicture> = arr.objects().mapNotNull { parse(it) }
    }
}

// --- lenient JSON ------------------------------------------------------------

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else opt(key)?.toString()

internal fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toLong()
        is String -> v.toDoubleOrNull()?.toLong()
        else -> null
    }
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

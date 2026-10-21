package com.openhamshack.i18n

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copied ShackFormat against ohs-libs spec/vectors/i18n/format.json
 * (I18N.md §5 item 3), carried as a test resource from the same ohs-libs
 * commit as the copy. ohs-libs' own test reads the vectors from its
 * checkout with JUnit 5; this repo is JUnit 4 and builds alone, so the
 * file travels with the copy and each section is one test that lists every
 * case it got wrong rather than stopping at the first.
 *
 * An expected `error` is a machine code; the English sentence is not
 * compared, because a UI translates the code, not the sentence.
 */
class ShackFormatVectorsTest {
    private val vectors = JSONObject(
        requireNotNull(javaClass.getResourceAsStream("/i18n/format.json")) { "format.json is missing from test resources" }
            .bufferedReader().readText(),
    )

    private fun JSONObject.num(key: String): Number = get(key) as Number
    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    /** Runs [call] for every case in [section]; compares its result with
     *  the case's [want] field, or its error code with the case's `error`. */
    private fun section(section: String, want: String, call: (JSONObject) -> Any) {
        val cases: JSONArray = vectors.getJSONArray(section)
        assertTrue("$section has no cases", cases.length() > 0)
        val wrong = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got: Any = try {
                call(c).toString()
            } catch (e: ShackFormat.FormatError) {
                "error:" + e.code
            }
            val expected = if (c.has("error")) "error:" + c.getString("error") else c.get(want).toString()
            if (got != expected) wrong += "$c\n  want ${quote(expected)}\n  got  ${quote(got.toString())}"
        }
        assertTrue("$section: ${wrong.size} case(s) differ\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    // Spells out the spaces and minus signs a failure would otherwise hide.
    private fun quote(s: String) = s.replace(" ", "<NBSP>").replace(" ", "<NNBSP>").replace("−", "<MINUS>")

    @Test fun frequency() = section("frequency", "text") { c ->
        ShackFormat.frequency(c.num("hz"), c.getString("locale"), c.getString("unit"), c.intOrNull("decimals"))
    }

    @Test fun offset() = section("offset", "text") { c ->
        ShackFormat.offset(c.num("hz"), c.getString("unit"), c.getString("locale"), c.intOrNull("decimals"))
    }

    @Test fun parseFrequency() = section("parse_frequency", "hz") { c ->
        ShackFormat.parseFrequency(c.getString("text"), c.getString("locale"), c.strOrNull("unit"))
    }

    @Test fun number() = section("number", "text") { c ->
        ShackFormat.number(c.num("x"), c.getString("locale"), c.getInt("decimals"))
    }

    @Test fun swr() = section("swr", "text") { c ->
        ShackFormat.swr(c.num("x"), c.getString("locale"))
    }

    @Test fun theVectorsAreTheVersionThisCopyWasWrittenFor() {
        assertEquals(1, vectors.getInt("version"))
    }

    @Test fun aJavaLocaleTagFindsItsSymbols() {
        assertEquals(",", Symbols.of(java.util.Locale.GERMANY.toLanguageTag()).decimal)
        assertEquals("’", Symbols.of("de_CH.UTF-8@euro").group)
    }
}

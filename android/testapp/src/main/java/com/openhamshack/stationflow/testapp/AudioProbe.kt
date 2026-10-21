package com.openhamshack.stationflow.testapp

import android.util.Log
import com.openhamshack.core.audio.PcmPlayer
import com.openhamshack.core.audio.ToneProbe

/**
 * Debug builds: say in logcat what the speaker is being given - its
 * strongest frequency, level and the player's buffer - every 3 s. An
 * emulator has no speaker to listen to, and fakeflow sends a 1000 Hz tone,
 * so "AudioProbe peak=1000Hz" is the end-to-end proof that the audio arrives,
 * decodes and reaches AudioTrack.write.
 */
object AudioProbe {
    fun attach(player: PcmPlayer) {
        val buf = ShortArray(4096)
        var n = 0
        var last = 0L
        var total = 0L
        val since = System.currentTimeMillis()
        player.tap = { block, count, rate ->
            total += count
            val take = minOf(count, buf.size - n)
            System.arraycopy(block, 0, buf, n, take)
            n += take
            val now = System.currentTimeMillis()
            if (n == buf.size) {
                if (now - last > 3000) {
                    last = now
                    val st = player.stats.value
                    Log.i("AudioProbe", "peak=${ToneProbe.peakHz(buf, n, rate)}Hz rms=%.3f rate=$rate buffered=${st.bufferedMs}ms underruns=${st.underruns} droppedMs=${st.droppedMs} played/s=${total * 1000 / maxOf(1, now - since)}"
                        .format(ToneProbe.rms(buf, n)))
                }
                n = 0
            }
        }
    }
}

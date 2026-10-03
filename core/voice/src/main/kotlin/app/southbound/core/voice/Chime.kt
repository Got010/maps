package app.southbound.core.voice

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** The two tones guidance plays before speaking, so a glance at the road isn't needed to know which is which. */
enum class ChimeKind {
    /** Two soft rising notes before a turn. */
    INSTRUCTION,

    /** Three quick high notes before an alert. */
    ALERT,
}

/**
 * Generates the chimes as PCM in code: no audio assets to ship. Pure, so
 * lengths and levels are unit tested.
 */
object Chime {

    /** Silence between the chime and the voice. */
    const val GAP_MS = 90

    private const val LEVEL = 0.35

    /** One note: frequency in Hz and length in milliseconds. */
    private data class Note(val hz: Double, val ms: Int, val pauseMs: Int = 0)

    private fun notes(kind: ChimeKind): List<Note> = when (kind) {
        // A fifth upwards, E5 to B5.
        ChimeKind.INSTRUCTION -> listOf(Note(659.3, 110), Note(987.8, 160))
        // A5 three times, short and spaced.
        ChimeKind.ALERT -> listOf(Note(1760.0, 70, 50), Note(1760.0, 70, 50), Note(1760.0, 90))
    }

    fun render(kind: ChimeKind, sampleRate: Int): ShortArray {
        val out = ArrayList<Short>()
        for (note in notes(kind)) {
            val n = note.ms * sampleRate / 1000
            // 8 ms fade in and out so the note doesn't click.
            val fade = min(n / 2, 8 * sampleRate / 1000).coerceAtLeast(1)
            for (i in 0 until n) {
                val envelope = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                // A touch of the octave makes it a chime rather than a beep.
                val t = i.toDouble() / sampleRate
                val wave = 0.8 * sin(2 * PI * note.hz * t) + 0.2 * sin(4 * PI * note.hz * t)
                out += (wave * envelope * LEVEL * Short.MAX_VALUE).toInt().toShort()
            }
            repeat(note.pauseMs * sampleRate / 1000) { out += 0 }
        }
        return out.toShortArray()
    }

    /** [speech] with the chime in front, at the speech's own sample rate. */
    fun before(kind: ChimeKind, speech: Pcm): Pcm {
        val chime = render(kind, speech.sampleRate)
        val gap = GAP_MS * speech.sampleRate / 1000
        val samples = ShortArray(chime.size + gap + speech.samples.size)
        chime.copyInto(samples)
        speech.samples.copyInto(samples, chime.size + gap)
        return Pcm(samples, speech.sampleRate)
    }
}

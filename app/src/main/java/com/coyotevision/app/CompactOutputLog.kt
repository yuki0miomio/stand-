package com.coyotevision.app

import kotlin.math.abs

/** Observes successful submissions only; never changes device commands or intensity limits. */
internal class CompactOutputLog {
    data class Sample(val at: Long, val strength: Int, val base: Int, val hands: Float = 0f,
        val feet: Float = 0f, val handWeight: Int = 0, val footWeight: Int = 0,
        val context: String = "", val channel: String = "A", val wave: String = "") {
        val extra get() = (strength - base).coerceAtLeast(0)
    }
    data class Change(val sample: Sample, val sharp: Boolean, val from: Int, val source: String)
    private var written: Sample? = null
    private val recent = ArrayDeque<Sample>()
    private var alerted = false
    private var alertPeak = 0

    fun reset() { written = null; recent.clear(); alerted = false; alertPeak = 0 }

    fun update(value: Sample): Change? {
        val previous = written
        val boundary = previous == null || value.context != previous.context ||
            value.channel != previous.channel || value.wave != previous.wave || value.base != previous.base
        if (boundary) { recent.clear(); alerted = false; alertPeak = 0 }
        while (recent.isNotEmpty() && value.at - recent.first().at > 2000) recent.removeFirst()
        val low = recent.minByOrNull { it.extra }
        val hand = value.hands >= .5f && value.handWeight > 0
        val foot = value.feet >= .5f && value.footWeight > 0
        if (value.extra <= alertPeak - 4 || (!hand && !foot)) alerted = false
        val sharp = !boundary && !alerted && low != null && value.extra - low.extra >= 8 && (hand || foot)
        recent.addLast(value)
        if (alerted) alertPeak = maxOf(alertPeak, value.extra)
        // At most 2 seconds of submitted frames; a held pose never repeats an identical entry.
        val changed = previous != null && (value.strength != previous.strength ||
            abs(value.hands - previous.hands) >= .2f || abs(value.feet - previous.feet) >= .2f)
        val due = previous != null && value.at - previous.at >= 2000 &&
            (abs(value.strength - previous.strength) >= 5 ||
                abs(value.hands - previous.hands) >= .2f || abs(value.feet - previous.feet) >= .2f ||
                value.at - previous.at >= 10_000)
        if (!boundary && !sharp && !(changed && due) && !(value.strength == 0 && previous?.strength != 0)) return null
        if (sharp) { alerted = true; alertPeak = value.extra }
        written = value
        return Change(value, sharp, low?.strength ?: previous?.strength ?: value.strength,
            listOfNotNull(if (hand) "手部" else null, if (foot) "脚步" else null).joinToString("、"))
    }
}

/** A pause exits the clean view; only a completed calibration can automatically enter it again. */
internal class MirrorMode {
    var active = false
        private set
    fun calibrated(enabled: Boolean) { active = enabled }
    fun exit() { active = false }
}

/** Routine entries share a minute heading; alerts/errors retain their exact phone time. */
internal class SessionLog(private val capacity: Int = 500) {
    val entries = ArrayDeque<String>()
    private var minute = Long.MIN_VALUE
    fun clear() { entries.clear(); minute = Long.MIN_VALUE }
    fun add(text: String, wallTime: Long, important: Boolean = false) {
        val nextMinute = wallTime / 60_000
        val stamp = if (important || nextMinute != minute) "${DisplayFormat.timestamp(wallTime)}  " else ""
        minute = nextMinute
        entries.addFirst(stamp + text)
        while (entries.size > capacity) entries.removeLast()
    }
}

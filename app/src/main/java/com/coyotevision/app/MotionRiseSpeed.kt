package com.coyotevision.app

/** Protocol strength units per second. Endurance uses its own fixed speed for the whole target. */
object MotionRiseSpeed {
    const val DEFAULT = 4
    const val TRAINING = 30
    fun valid(value: Int) = value in 1..200
    fun forMode(ordinary: Int, training: Boolean) = if (training) TRAINING else ordinary.takeIf { valid(it) } ?: 0
}

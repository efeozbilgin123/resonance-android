package com.resonance.player

/**
 * JNI playback queue policy from Rust. Actual Android audio output uses Media3.
 */
object RustCore {
    init { System.loadLibrary("resonance_core") }
    @JvmStatic external fun nextIndex(
        current: Int, count: Int, repeatMode: Int, shuffle: Boolean, seed: Long
    ): Int
}

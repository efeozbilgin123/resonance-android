//! Resonance playback queue policy. Android Media3 decodes and streams audio.
//! Rust owns skip/shuffle/repeat decisions and exposes them over JNI.
//! This engine never accesses Spotify audio streams.

use jni::objects::JClass;
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

/// repeat_mode: 0 = off, 1 = all, 2 = one.
/// Returns -1 at end of finite queue.
pub fn next_index(current: i32, count: i32, repeat_mode: i32, shuffle: bool, seed: u64) -> i32 {
    if count <= 0 { return -1; }
    let current = current.clamp(0, count - 1);
    if repeat_mode == 2 { return current; }
    if shuffle {
        if count == 1 { return if repeat_mode == 1 { 0 } else { -1 }; }
        let mut x = seed ^ ((current as u64).wrapping_mul(0x9E3779B97F4A7C15));
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        let jump = 1 + (x.wrapping_mul(0x2545F4914F6CDD1D) % ((count - 1) as u64)) as i32;
        return (current + jump) % count;
    }
    if current + 1 < count { current + 1 }
    else if repeat_mode == 1 { 0 }
    else { -1 }
}

#[no_mangle]
pub extern "system" fn Java_com_resonance_player_RustCore_nextIndex(
    _env: JNIEnv, _class: JClass, current: jint, count: jint,
    repeat_mode: jint, shuffle: jboolean, seed: jlong
) -> jint {
    next_index(current, count, repeat_mode, shuffle != 0, seed as u64)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn finite_playlist_stops() {
        assert_eq!(next_index(3, 4, 0, false, 0), -1);
        assert_eq!(next_index(0, 4, 0, false, 0), 1);
    }
    #[test] fn repeat_all_wraps() { assert_eq!(next_index(3, 4, 1, false, 0), 0); }
    #[test] fn repeat_one_keeps_track() { assert_eq!(next_index(2, 5, 2, false, 0), 2); }
    #[test] fn shuffle_avoids_current() {
        for seed in 0..1000 {
            let n = next_index(2, 8, 1, true, seed);
            assert!(n >= 0 && n < 8 && n != 2);
        }
    }
    #[test] fn empty_queue_returns_none() { assert_eq!(next_index(2, 0, 1, false, 7), -1); }
}

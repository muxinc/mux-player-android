package com.mux.player

import kotlin.math.ceil

/**
 * Returns true if the receiver is not in the given objects.
 *
 * For example `"blue".oneOf("red", "green") == false` and `3.oneOf(3,5,6) == true`
 */
fun <Any> Any.oneOf(vararg these: Any) = these.contains(this)

/**
 * Returns true if the receiver is not in the given objects.
 *
 * For example `3.noneOf(1,4,5) == true` and `3.noneOf(3,5,6) = false`
 */
fun <Any> Any.noneOf(vararg these: Any) = !these.contains(this)

/**
 * Return true if all elements in the given set are equal
 */
fun <Any> allEqual(vararg these: Any): Boolean {
  if (these.isEmpty()) {
    return true
  } else {
    val head = these.first()
    these.slice(1..these.lastIndex).onEach { if (head != it) return false }
    return true
  }
}

/**
 * Clamps an integer between the given min and max
 */
fun Int.clamp(min: Int, max: Int): Int {
  return if(this >= max) {
    max
  } else if(this <= min) {
    min
  } else {
    this
  }
}

/**
 * Convert from a raw pixel dimension to a density-independent (dip) dimension
 */
fun convertPxToDp(px: Int, displayDensity: Float): Int {
  return ceil((px / displayDensity).toDouble()).toInt()
}

/**
 * Gets a Log Tag from the name of the calling class. Can be used in any package that isn't
 * obfuscated (such as muxstats)
 */
@Suppress("unused") // T is used for its class
inline fun <reified T> T.logTag(): String = T::class.java.simpleName

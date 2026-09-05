package io.github.youndie.sborka.internal.fixture

/**
 * `inline`, so its body is copied into every caller and no reference to it survives.
 *
 * The compiler is right that inlining this buys nothing — the point is not the performance, it is
 * that the bytecode ends up with no mention of the function at all, which is the case the scan has to
 * not report.
 */
@Suppress("NOTHING_TO_INLINE")
public inline fun joinsFixtureInline(): Int = 1

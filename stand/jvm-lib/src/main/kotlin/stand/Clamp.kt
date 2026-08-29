package stand

/**
 * Something with a branch in it, so `mutationTest` has a mutation to make and the tests have
 * something to notice.
 */
public fun clamp(
    value: Int,
    min: Int,
    max: Int,
): Int =
    when {
        value < min -> min
        value > max -> max
        else -> value
    }

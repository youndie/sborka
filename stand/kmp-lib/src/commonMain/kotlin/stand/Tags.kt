package stand

/**
 * The same finding OUTSIDE the declared scope: `:kmp-lib` is not in `sborka.perflint.hot`, so this
 * is printed and the build passes. Without it the gate's scope would be exercised only where it
 * fires, and "the rule is off everywhere" would look exactly the same.
 */
public fun isTag(value: String): Boolean = Regex("[a-z]+-[0-9]+").matches(value)

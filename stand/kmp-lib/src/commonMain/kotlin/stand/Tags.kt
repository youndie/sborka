package stand

/**
 * The same finding OUTSIDE the declared scope: `:kmp-lib` is not in `sborka.perflint.hot`, so this
 * is printed and the build passes. Without it the gate's scope would be exercised only where it
 * fires, and "the rule is off everywhere" would look exactly the same.
 */
public fun isTag(value: String): Boolean = Regex("[a-z]+-[0-9]+").matches(value)

/**
 * The pattern that cannot be hoisted: its string is assembled here.
 *
 * The report answers this one differently — `<clinit>` is not available to it — and the stand
 * carries both shapes so that both sentences are printed by a real run rather than only by a unit
 * test. Outside the declared scope, so neither is a gate here.
 */
public fun hasField(
    key: String,
    line: String,
): Boolean = Regex("$key=[0-9]+").matches(line)

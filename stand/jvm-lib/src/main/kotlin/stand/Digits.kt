package stand

/**
 * A chain the perf-lint is supposed to find, kept on purpose.
 *
 * The stand's job is to prove the wiring rather than to be good code: `sborka.perflint.hot` names
 * this module, so the report has to print this method with `[hot]` beside it. Without a finding here
 * the scope would be exercised only by its own absence — which is the shape of check that passes
 * because nothing ran.
 *
 * Four materialisations: a list for `filter`, a list for `map`, the comparator sort, and the string
 * the join builds. `asSequence()` is the fix it would ask for.
 */
public fun heaviest(
    values: List<Int>,
    keep: Int,
): String =
    values
        .filter { it > 0 }
        .map { it * keep }
        .sortedByDescending { it }
        .joinToString(",")

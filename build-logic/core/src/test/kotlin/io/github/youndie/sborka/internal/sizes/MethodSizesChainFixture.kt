package io.github.youndie.sborka.internal.sizes

/**
 * The four chain shapes, compiled by the build that runs the test.
 *
 * WHY THE COMPILER AND NOT A HAND-WRITTEN CLASS FILE: the whole question is what Kotlin emits for a
 * chain, and the answer was not what the rule was first written against. `filter` and `map` are
 * `inline`, so a collection chain leaves no operator calls behind at all — only the containers each
 * link allocates — while `sortedBy` leaves a `sortedWith` and a string chain leaves four calls into
 * `StringsKt`. A fixture written from my idea of the bytecode would have agreed with the wrong rule.
 *
 * The package is `sizes` and not `fixture` for the reason the other fixture here gives: `JoinsTest`
 * asserts over the whole contents of that package.
 */
internal class MethodSizesChainFixture {
    /** Four materialisations: a list per inlined link, plus the comparator sort that is a call. */
    fun eagerChain(numbers: List<Int>): List<String> =
        numbers
            .filter { it > 0 }
            .map { it * 2 }
            .sortedBy { it }
            .map { it.toString() }

    /** One materialisation. A method that builds a collection is not a chain that throws one away. */
    fun oneOperator(numbers: List<Int>): List<Int> = numbers.map { it + 1 }

    /** The fix the finding asks for: a sequence materialises once, at the end. */
    fun lazyChain(numbers: List<Int>): List<Int> =
        numbers
            .asSequence()
            .filter { it > 0 }
            .map { it * 2 }
            .toList()

    /**
     * The string half, and the shape that corrected the rule: no collection operator appears, and an
     * allocation profile of a real service charged this exact body more than any other user method.
     */
    fun stringChain(value: Long): String =
        value
            .toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()

    /** Nothing to materialise. */
    fun arithmetic(
        first: Int,
        second: Int,
    ): Int = first * second + 1
}

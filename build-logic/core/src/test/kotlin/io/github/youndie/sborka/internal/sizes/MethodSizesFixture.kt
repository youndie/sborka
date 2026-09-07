package io.github.youndie.sborka.internal.sizes

/**
 * A big body and a small one, compiled by the same build that runs the test.
 *
 * A PACKAGE OF ITS OWN, and not beside the joins fixtures: `JoinsTest` copies every class file out of
 * that package and asserts on what the scan says about all of them, so a fixture added there for
 * another test changes another test's numbers.
 *
 * The big one is deliberately dull — a long chain of appends whose size comes from the number of
 * calls rather than from anything clever. What matters is that the bytes are the compiler's and not
 * mine: a hand-written class file would test the reader against my idea of what Kotlin emits, which
 * is the assumption the test exists to avoid.
 */
internal class MethodSizesFixture {
    fun small(value: Int): Int = value + 1

    fun big(seed: Int): String {
        val out = StringBuilder()
        out.append("a").append(seed).append('-')
        out.append("b").append(seed + 1).append('-')
        out.append("c").append(seed + 2).append('-')
        out.append("d").append(seed + 3).append('-')
        out.append("e").append(seed + 4).append('-')
        out.append("f").append(seed + 5).append('-')
        out.append("g").append(seed + 6).append('-')
        out.append("h").append(seed + 7).append('-')
        out.append("i").append(seed + 8).append('-')
        out.append("j").append(seed + 9).append('-')
        out.append("k").append(seed + 10).append('-')
        out.append("l").append(seed + 11).append('-')
        out.append("m").append(seed + 12).append('-')
        out.append("n").append(seed + 13).append('-')
        out.append("o").append(seed + 14).append('-')
        out.append("p").append(seed + 15).append('-')
        out.append("q").append(seed + 16).append('-')
        out.append("r").append(seed + 17).append('-')
        out.append("s").append(seed + 18).append('-')
        out.append("t").append(seed + 19).append('-')
        return out.toString()
    }
}

/** No body at all: an abstract method has no `Code` attribute, and so has no size to report. */
internal interface MethodSizesFixturePort {
    fun bodyless(value: Int): Int
}

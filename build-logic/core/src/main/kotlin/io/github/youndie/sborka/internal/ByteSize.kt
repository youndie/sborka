package io.github.youndie.sborka.internal

/**
 * A size budget as a person writes it, in bytes.
 *
 * A budget is the one number in a build file that gets compared to a download, and a download is
 * quoted in megabytes — so the property is written `50MiB` rather than `52428800`, which nobody can
 * check by eye and everybody gets wrong by a factor of 1024 at least once.
 *
 * **The binary units, and only those.** `MiB` is 1024 squared; `MB` in common use is sometimes that
 * and sometimes a million, and a budget that means one of two things is a budget that fails a build
 * for a reason its author will dispute. Refused by name rather than guessed at.
 *
 * PUBLIC, like [MethodSizes] and for the same reason: the conventions are a different module and
 * `internal` does not cross one.
 */
public object ByteSize {
    private val FORM = Regex("""^(\d+)\s*(B|KiB|MiB|GiB)?$""")

    private val UNITS =
        mapOf(
            "B" to 1L,
            "KiB" to 1024L,
            "MiB" to 1024L * 1024,
            "GiB" to 1024L * 1024 * 1024,
        )

    /**
     * Parses `50MiB`, `512KiB`, `1GiB` or a plain count of bytes.
     *
     * [what] names the property in the refusal, because a build that stops on a malformed number has
     * to say which line of `gradle.properties` to fix - the value alone appears in no file the reader
     * can search for.
     */
    public fun parse(
        raw: String,
        what: String,
    ): Long {
        val match =
            FORM.matchEntire(raw.trim())
                ?: error(
                    "$what must be a whole number of bytes, optionally with B, KiB, MiB or GiB - " +
                        "got \"$raw\". Decimal units are not accepted: MB means 1000000 to a disk " +
                        "vendor and 1048576 to a build, and a budget that means either is a build " +
                        "failure its author will argue with.",
                )
        val (number, unit) = match.destructured
        val multiplier = UNITS.getValue(unit.ifEmpty { "B" })
        val value =
            number.toLongOrNull()
                ?: error("$what does not fit in a long: \"$raw\"")
        require(value > 0) { "$what must be greater than zero; a budget of zero fails every build" }
        return value * multiplier
    }
}
